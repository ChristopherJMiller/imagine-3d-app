"""Tile PNG renders into one contact sheet: python3 sheet.py out.png a.png b.png ..."""
import sys
from PIL import Image, ImageDraw

out, files = sys.argv[1], sys.argv[2:]
imgs = [Image.open(f).convert("RGB") for f in files]
w, h = imgs[0].size
cols = min(4, len(imgs))
rows = (len(imgs) + cols - 1) // cols
sheet = Image.new("RGB", (cols * w, rows * h), "white")
for i, (f, im) in enumerate(zip(files, imgs)):
    x, y = (i % cols) * w, (i // cols) * h
    sheet.paste(im, (x, y))
    ImageDraw.Draw(sheet).text((x + 8, y + 8), f.rsplit("/", 1)[-1], fill="black")
sheet.save(out)
