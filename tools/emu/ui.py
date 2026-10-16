#!/usr/bin/env python3
"""Tiny adb UI driver for the emulator.

  ui.py tap "New idea"          tap the element whose text/content-desc contains the string
  ui.py type "hello"            type into the focused field
  ui.py texts                   list visible texts
  ui.py wait "Design it" [secs] wait until a text appears
  ui.py key BACK|ENTER
  ui.py hidekb                  dismiss the keyboard only if it is showing
"""
import re, subprocess, sys, time
import xml.etree.ElementTree as ET


def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
    return ET.fromstring(xml) if xml.strip().startswith("<") else None


def nodes(root):
    for n in root.iter("node"):
        label = (n.get("text") or "") or (n.get("content-desc") or "")
        b = re.findall(r"\d+", n.get("bounds", ""))
        if label and len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            yield label, ((x1 + x2) // 2, (y1 + y2) // 2), n


def find(text, exact=False):
    root = dump()
    if root is None:
        return None
    for label, center, n in nodes(root):
        if (label == text) if exact else (text.lower() in label.lower()):
            return center
    return None


def main():
    cmd, *args = sys.argv[1:]
    if cmd == "tap":
        c = find(args[0], exact=len(args) > 1 and args[1] == "exact")
        if not c:
            sys.exit(f"not found: {args[0]}")
        subprocess.run(["adb", "shell", "input", "tap", str(c[0]), str(c[1])])
        print("tapped", args[0], c)
    elif cmd == "type":
        text = args[0].replace(" ", "%s").replace("'", "\\'")
        subprocess.run(["adb", "shell", "input", "text", text])
    elif cmd == "texts":
        root = dump()
        for label, c, _ in nodes(root):
            print(c, label[:100].replace("\n", " "))
    elif cmd == "wait":
        timeout = float(args[1]) if len(args) > 1 else 60
        t0 = time.time()
        while time.time() - t0 < timeout:
            if find(args[0]):
                print("found", args[0], f"after {time.time() - t0:.0f}s")
                return
            time.sleep(2)
        sys.exit(f"timeout waiting for {args[0]}")
    elif cmd == "hidekb":
        shown = subprocess.run(["adb", "shell", "dumpsys", "input_method"], capture_output=True, text=True).stdout
        if "mInputShown=true" in shown or "isInputViewShown=true" in shown:
            subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_BACK"])
    elif cmd == "key":
        subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_" + args[0]])
    elif cmd == "swipe":
        subprocess.run(["adb", "shell", "input", "swipe", *args])


if __name__ == "__main__":
    main()
