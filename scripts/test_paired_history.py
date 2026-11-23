"""Run on two disposable Android emulators with the corresponding app and androidTest APKs installed."""
import argparse
import re
import socket
import subprocess
import time
from urllib.parse import urlsplit, urlunsplit

PACKAGE = "com.fongmi.android.tv"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"


def adb(device, *args, data=None):
    return subprocess.run(["adb", "-s", device, *args], input=data, capture_output=True,
                          check=True, timeout=120).stdout


def test(device, name, stage=None):
    args = ["-e", "historyStage", stage] if stage else []
    report = adb(device, "shell", "am", "instrument", "-w", "-e", "class", PACKAGE + name,
                 *args, RUNNER).decode()
    if "OK (" not in report or "FAILURES" in report:
        raise RuntimeError(report)
    print(stage or name, "PASS", flush=True)


def private_file(device, name):
    return adb(device, "exec-out", "run-as", PACKAGE, "cat", "files/" + name).decode().strip()


def tv_home(tv, port):
    adb(tv, "shell", "am", "start", "-n", PACKAGE + "/.ui.activity.HomeActivity")
    for _ in range(30):
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1) as conn:
                conn.sendall(b"GET /device HTTP/1.0\r\nHost: localhost\r\n\r\n")
                if b"200" in conn.recv(120):
                    return
        except OSError:
            pass
        time.sleep(1)
    raise RuntimeError("TV LAN receiver did not start")


def run(phone, tv):
    if phone == tv or not all(re.fullmatch(r"emulator-\d+", device) for device in [phone, tv]):
        raise ValueError("Use two distinct, disposable Android emulators")
    suite = ".history.PairedHistoryTest#acrossDevices"
    test(phone, ".db.AppDatabaseMigrationTest")
    test(tv, suite, "tvStart")
    url = urlsplit(private_file(tv, "history-test-peer"))
    port = int(adb(tv, "forward", "tcp:0", "tcp:" + str(url.port)))
    try:
        code = urlunsplit(("http", "10.0.2.2:" + str(port), url.path, "", url.fragment))
        adb(phone, "shell", "run-as", PACKAGE, "mkdir", "-p", "files")
        adb(phone, "shell", "run-as", PACKAGE, "sh", "-c", "'cat > files/history-test-peer'", data=code.encode())
        for action in ("phonePair", "tvAdvance", "phonePull", "tvConflict", "phoneResolve", "tvCheck"):
            if action.startswith("phone"):
                tv_home(tv, port)
            test(phone if action.startswith("phone") else tv, suite, action)
        if private_file(phone, "history-test-cid") == private_file(tv, "history-test-cid"):
            raise AssertionError("Fixture subscription IDs unexpectedly match")
        suite = ".ui.activity.PairedPlaybackTest#openSameFileAcrossDevices"
        for device, action in [(phone, "mediaPhoneStart"), (tv, "mediaTV"), (phone, "mediaPhoneReturn")]:
            if device == phone:
                tv_home(tv, port)
            test(device, suite, action)
    finally:
        adb(tv, "forward", "--remove", "tcp:" + str(port))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__ + " This replaces their history pairings.")
    parser.add_argument("--phone", required=True)
    parser.add_argument("--tv", required=True)
    options = parser.parse_args()
    run(options.phone, options.tv)
