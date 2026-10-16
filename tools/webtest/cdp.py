#!/usr/bin/env python3
"""Minimal headless-Chromium driver for testing the viewer/OpenSCAD web assets.

  python3 tools/webtest/cdp.py URL SCRIPT.js [--screenshot out.png]

SCRIPT.js is evaluated in the page as an async function body; its return value is printed
as JSON. Console messages are echoed.
"""
import argparse, base64, json, pathlib, shutil, subprocess, tempfile, time
import requests, websocket

ap = argparse.ArgumentParser()
ap.add_argument("url")
ap.add_argument("script")
ap.add_argument("--screenshot")
ap.add_argument("--size", default="900,700")
ap.add_argument("--port", type=int, default=9333)
args = ap.parse_args()

profile = tempfile.mkdtemp()
chrome = subprocess.Popen([shutil.which("chromium"), "--headless=new", f"--remote-debugging-port={args.port}",
                           f"--user-data-dir={profile}", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
                           f"--window-size={args.size}", "--no-first-run", "--remote-allow-origins=*", "about:blank"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(100):
        try:
            tabs = requests.get(f"http://127.0.0.1:{args.port}/json", timeout=1).json()
            page = next(t for t in tabs if t["type"] == "page")
            break
        except Exception:
            time.sleep(0.2)
    ws = websocket.create_connection(page["webSocketDebuggerUrl"], timeout=300, suppress_origin=True)
    msg_id = 0

    def call(method, **params):
        global msg_id
        msg_id += 1
        ws.send(json.dumps({"id": msg_id, "method": method, "params": params}))
        while True:
            m = json.loads(ws.recv())
            if m.get("method") == "Runtime.consoleAPICalled":
                print("console:", " ".join(str(a.get("value", a.get("description", ""))) for a in m["params"]["args"])[:400])
            elif m.get("method") == "Runtime.exceptionThrown":
                print("exception:", m["params"]["exceptionDetails"].get("exception", {}).get("description", m["params"])[:400])
            if m.get("id") == msg_id:
                return m

    call("Runtime.enable")
    call("Page.enable")
    call("Page.navigate", url=args.url)
    time.sleep(1.5)
    body = pathlib.Path(args.script).read_text()
    r = call("Runtime.evaluate", expression=f"(async () => {{ {body} }})()", awaitPromise=True, returnByValue=True)
    print("result:", json.dumps(r.get("result", {}).get("result", {}).get("value"), indent=1)[:3000])
    if "exceptionDetails" in r.get("result", {}):
        print("error:", r["result"]["exceptionDetails"])
    if args.screenshot:
        time.sleep(0.5)
        shot = call("Page.captureScreenshot", format="png")
        pathlib.Path(args.screenshot).write_bytes(base64.b64decode(shot["result"]["data"]))
        print("screenshot:", args.screenshot)
finally:
    chrome.terminate()
    shutil.rmtree(profile, ignore_errors=True)
