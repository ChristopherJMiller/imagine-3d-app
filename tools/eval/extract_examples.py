"""Pull the ```openscad blocks out of the system prompt so they can be compile-checked."""
import re, sys, pathlib
text = pathlib.Path(sys.argv[1]).read_text()
out = pathlib.Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
for i, block in enumerate(re.findall(r"```openscad\n(.*?)```", text, re.S), 1):
    (out / f"example{i}.scad").write_text(block)
    print(out / f"example{i}.scad")
