"""Pianduoduo account and playback-history WSGI application."""
import base64
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import sqlite3
import time
import uuid
from contextlib import closing

MAX_BODY = 128 * 1024
SESSION_LIFETIME = 90 * 86400
SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, salt BLOB NOT NULL,
    password_hash BLOB NOT NULL, data_key TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY, owner TEXT NOT NULL, expires INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_owner ON sessions(owner);
CREATE TABLE IF NOT EXISTS history (
    owner TEXT NOT NULL, scope TEXT NOT NULL, item TEXT NOT NULL, revision TEXT NOT NULL,
    label TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(owner, scope, item)
);
CREATE TABLE IF NOT EXISTS attempts (bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL);
"""
class Problem(Exception):
    def __init__(self, status, message):
        self.status = status
        self.message = message


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def password_hash(value, salt):
    return hashlib.scrypt(value.encode(), salt=salt, n=32768, r=8, p=1, maxmem=64 << 20, dklen=32)


def field(data, name, pattern):
    value = data.get(name)
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise Problem(400, "请求参数不完整")
    return value


class AccountService:
    def __init__(self, database):
        if not hasattr(hashlib, "scrypt"):
            raise RuntimeError("An OpenSSL-enabled Python with hashlib.scrypt is required")
        self.database = database
        with closing(self.connect()) as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)

    def connect(self):
        db = sqlite3.connect(self.database, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        return db

    def limit_attempts(self, db, address, name, register, now):
        # Each reservation commits before the expensive password operation and survives a failed login.
        db.execute("BEGIN IMMEDIATE")
        db.execute("DELETE FROM attempts WHERE expires <= ?", (now,))
        for key, limit in [("ip:" + address, 90), ("user:" + name, 30)] + ([("register:" + address, 12)] if register else []):
            bucket = digest(key)
            db.execute("INSERT INTO attempts VALUES (?,1,?) ON CONFLICT(bucket) DO UPDATE SET count=count+1", (bucket, now + 900))
            if db.execute("SELECT count FROM attempts WHERE bucket=?", (bucket,)).fetchone()[0] > limit:
                db.commit()
                raise Problem(429, "尝试次数过多，请稍后再试")
        db.commit()

    def authenticate(self, db, data, register, address, now):
        name = field(data, "username", r"[A-Za-z0-9_]{4,32}").lower()
        password = data.get("password")
        if not isinstance(password, str) or not 8 <= len(password) <= 128:
            raise Problem(400, "密码需要 8 至 128 个字符")
        self.limit_attempts(db, address, name, register, now)
        old = db.execute("SELECT * FROM users WHERE name=?", (name,)).fetchone()
        salt = secrets.token_bytes(16) if register or old is None else old["salt"]
        hashed = password_hash(password, salt)
        db.execute("BEGIN IMMEDIATE")
        if register:
            owner = str(uuid.uuid4())
            key = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")
            try:
                db.execute("INSERT INTO users VALUES (?,?,?,?,?)", (owner, name, salt, hashed, key))
            except sqlite3.IntegrityError:
                raise Problem(409, "账号名已被使用")
        elif old is None or not hmac.compare_digest(old["password_hash"], hashed):
            raise Problem(401, "账号或密码不正确")
        else:
            owner, key = old["id"], old["data_key"]
        token = secrets.token_urlsafe(32)
        db.execute("DELETE FROM sessions WHERE expires <= ?", (now,))
        db.execute("INSERT INTO sessions VALUES (?,?,?)", (digest(token), owner, now + SESSION_LIFETIME))
        db.commit()
        return {"id": owner, "username": name, "token": token, "dataKey": key}

    def dispatch(self, db, route, data, environ):
        now = int(time.time())
        if route in ("register", "login"):
            address = environ.get("REMOTE_ADDR", "")
            if address and address == os.environ.get("PDD_TRUSTED_PROXY") and environ.get("HTTP_X_REAL_IP"):
                try:
                    address = str(ipaddress.ip_address(environ["HTTP_X_REAL_IP"]))
                except ValueError:
                    pass
            return self.authenticate(db, data, route == "register", address, now)
        auth = environ.get("HTTP_AUTHORIZATION", "")
        if not re.fullmatch(r"Bearer [A-Za-z0-9_-]{43}", auth):
            raise Problem(401, "请重新登录")
        token = digest(auth[7:])
        # Logout and a concurrent write are serialized, including their session checks.
        write = route in ("write", "logout")
        db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
        session = db.execute("SELECT owner FROM sessions WHERE token_hash=? AND expires>?", (token, now)).fetchone()
        if session is None:
            raise Problem(401, "登录已过期，请重新登录")
        owner = session["owner"]
        if route == "logout":
            db.execute("DELETE FROM sessions WHERE token_hash=?", (token,))
            db.commit()
            return {}
        scope = field(data, "scope", r"[0-9a-f]{64}")
        if route == "index":
            after = field(data, "after", r"(?:[0-9a-f]{64})?")
            rows = db.execute("SELECT item,revision,label FROM history WHERE owner=? AND scope=? AND item>? ORDER BY item LIMIT 33", (owner,scope,after)).fetchall()
            return {"items": [dict(row) for row in rows[:32]], "next": rows[31]["item"] if len(rows)>32 else ""}
        item = field(data, "item", r"[0-9a-f]{64}")
        args = (owner, scope, item)
        row = db.execute("SELECT revision,payload FROM history WHERE owner=? AND scope=? AND item=?", args).fetchone()
        if route == "read":
            return dict(row) if row else {}
        expected = field(data, "expected", r"(?:-|[A-Za-z0-9+/]{43}=)")
        if (row["revision"] if row else "-") != expected:
            return {"applied": False}
        rev = field(data, "revision", r"(?:-|[A-Za-z0-9+/]{43}=)")
        if rev == "-":
            db.execute("DELETE FROM history WHERE owner=? AND scope=? AND item=?", args)
        else:
            label = field(data, "label", r"[A-Za-z0-9_-]{38,32768}")
            payload = field(data, "payload", r"[A-Za-z0-9_-]{38,98304}")
            if row is None and db.execute("SELECT COUNT(*) FROM history WHERE owner=?", (owner,)).fetchone()[0] >= 2000:
                raise Problem(409, "账号观看记录已达上限")
            db.execute("INSERT INTO history VALUES (?,?,?,?,?,?) ON CONFLICT(owner,scope,item) DO UPDATE SET revision=excluded.revision,label=excluded.label,payload=excluded.payload", (*args,rev,label,payload))
        db.commit()
        return {"applied": True}

    def __call__(self, environ, start_response):
        try:
            path = environ.get("PATH_INFO","")
            if path not in {"/v1/" + name for name in ("register","login","logout","index","read","write")}:
                raise Problem(404, "接口不存在")
            if environ.get("REQUEST_METHOD") != "POST":
                raise Problem(405, "请使用 POST")
            try:
                size = int(environ.get("CONTENT_LENGTH") or 0)
                if size <= 0 or size > MAX_BODY:
                    raise Problem(413, "请求大小不正确")
                data = json.loads(environ["wsgi.input"].read(size))
            except (ValueError, UnicodeError, RecursionError):
                raise Problem(400, "无效的请求")
            if not isinstance(data, dict):
                raise Problem(400, "无效的请求")
            with closing(self.connect()) as db:
                result = self.dispatch(db, path[4:], data, environ)
            status = 200
        except Problem as err:
            status, result = err.status, {"error": err.message}
        except sqlite3.Error:
            status, result = 503, {"error": "账号服务暂时繁忙"}
        from http import HTTPStatus
        body = json.dumps(result, ensure_ascii=False).encode()
        start_response(f"{status} {HTTPStatus(status).phrase}", [("Content-Type","application/json; charset=utf-8"),("Cache-Control","no-store"),("Content-Length",str(len(body)))])
        return [body]


def create_app():
    return AccountService(os.environ["PDD_ACCOUNT_DB"])


if __name__ == "__main__":
    from wsgiref.simple_server import make_server
    with make_server("127.0.0.1", int(os.environ.get("PORT", "8787")), create_app()) as http:
        http.serve_forever()
