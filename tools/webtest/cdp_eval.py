#!/usr/bin/env python3
"""Evaluate an expression in an already-running page (e.g. the app's WebView via adb forward).

  python3 tools/webtest/cdp_eval.py http://127.0.0.1:9444 'document.title'
"""
import json, sys
import requests, websocket

base, expr = sys.argv[1], sys.argv[2]
page = next(t for t in requests.get(base + "/json").json() if t["type"] == "page")
ws = websocket.create_connection(page["webSocketDebuggerUrl"], suppress_origin=True)
ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {"expression": expr, "awaitPromise": True, "returnByValue": True}}))
while True:
    m = json.loads(ws.recv())
    if m.get("id") == 1:
        print(json.dumps(m.get("result", {}).get("result", {}).get("value", m), indent=1)[:4000])
        break
