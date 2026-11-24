import base64
import io
import json
import secrets
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from service import AccountService


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "account.sqlite"
        self.app = AccountService(self.path)

    def post(self, route, data, token=""):
        raw = json.dumps(data).encode()
        status = []
        body = self.app({"PATH_INFO": "/v1/" + route, "REQUEST_METHOD": "POST",
                         "REMOTE_ADDR": "127.0.0.1", "CONTENT_LENGTH": str(len(raw)),
                         "wsgi.input": io.BytesIO(raw), "HTTP_AUTHORIZATION": "Bearer " + token},
                        lambda code, headers: status.append(int(code[:3])))
        return status[0], json.loads(body[0])

    def login(self, name, new=True):
        code, session = self.post("register" if new else "login", {"username": name, "password": "fixture-password"})
        self.assertEqual(code, 200)
        return session

    def test_login_persistence_and_device_logout(self):
        a = self.login("Alice")
        self.app = AccountService(self.path)
        tv = self.login("alice", False)
        self.assertEqual((a["id"], a["dataKey"]), (tv["id"], tv["dataKey"]))
        self.assertNotEqual(a["token"], tv["token"])
        self.assertEqual(self.post("login", {"username":"alice","password":"wrong-password"})[0], 401)
        self.assertEqual(self.post("register", {"username":"ALICE","password":"fixture-password"})[0], 409)
        self.assertEqual(self.post("logout", {}, a["token"])[0], 200)
        index = {"scope":"b"*64, "after":""}
        self.assertEqual(self.post("index", index, a["token"])[0], 401)
        self.assertEqual(self.post("index", index, tv["token"])[0], 200)
        with sqlite3.connect(self.path) as db:
            db.execute("UPDATE sessions SET expires=0")
        self.assertEqual(self.post("index", index, tv["token"])[0], 401)

    def test_cas_rewind_delete_and_account_isolation(self):
        token = self.login("user1")["token"]
        tv = self.login("user1", False)["token"]
        stranger = self.login("user2")["token"]
        address = {"scope":"a"*64,"item":"b"*64}
        def change(old, revision):
            return dict(address, expected=old, revision=revision, label="b"*40, payload="c"*50)
        rev = lambda: base64.b64encode(secrets.token_bytes(32)).decode()
        r1,r2,r3 = rev(),rev(),rev()
        self.assertEqual(self.post("write", change("-",r1), token), (200, {"applied": True}))
        self.assertEqual(self.post("read", address, tv)[1]["revision"], r1)
        self.assertEqual(self.post("read", address, stranger), (200, {}))
        self.assertEqual(self.post("write", change(r1,"-"), stranger), (200, {"applied":False}))
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda r:self.post("write",change(r1,r),tv)[1]["applied"], [r2,r3]))
        self.assertCountEqual(results,[True,False])
        winning = self.post("read", address, token)[1]["revision"]
        self.assertEqual(self.post("write", change(winning,"-"), tv)[1], {"applied":True})
        self.assertEqual(self.post("index", dict(scope=address["scope"],after=""),token)[1]["items"],[])

    def test_index_pagination_and_input_limits(self):
        token = self.login("testuser")["token"]
        scope = "a"*64
        for i in range(35):
            data = dict(scope=scope,item=format(i,"064x"),revision="A"*43+"=",expected="-",label="b"*40,payload="c"*50)
            self.assertEqual(self.post("write",data,token)[1], {"applied":True})
        index = self.post("index",dict(scope=scope,after=""),token)[1]
        self.assertEqual(len(index["items"]),32)
        self.assertEqual(len(self.post("index",dict(scope=scope,after=index["next"]),token)[1]["items"]),3)
        self.assertEqual(self.post("index",dict(scope="other",after=""),token)[0],400)
        self.assertEqual(self.post("register",dict(username="invalid.",password="password"))[0],400)
        self.assertEqual(self.post("login",dict(username="testuser",password="x"*140000))[0],413)
