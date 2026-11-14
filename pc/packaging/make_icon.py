"""Draws the app icon (the gradient mark of the UI) and writes packaging/hdrstudio.ico."""
from pathlib import Path

from PIL import Image, ImageDraw

STOPS = [(0.0, (255, 200, 87)), (0.45, (255, 138, 76)), (1.0, (255, 79, 139))]


def colour(t):
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            f = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * f) for a, b in zip(c0, c1))
    return STOPS[-1][1]


def draw(n=512):
    grad = Image.new("RGB", (n, n))
    px = grad.load()
    for y in range(n):
        for x in range(n):
            px[x, y] = colour((x * 0.8 + y * 0.2) / n)
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle((n * 0.06, n * 0.06, n * 0.94, n * 0.94), radius=n * 0.22, fill=255)
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    # a rising highlight curve: the "expanded highlights" of an HDR tone curve
    pts = [(n * (0.24 + 0.52 * t), n * (0.74 - 0.48 * t ** 2.2)) for t in [i / 40 for i in range(41)]]
    d.line(pts, fill=(26, 15, 8, 255), width=round(n * 0.07), joint="curve")
    r = n * 0.065
    d.ellipse((pts[-1][0] - r, pts[-1][1] - r, pts[-1][0] + r, pts[-1][1] + r), fill=(26, 15, 8, 255))
    return img


if __name__ == "__main__":
    out = Path(__file__).with_name("hdrstudio.ico")
    draw().save(out, sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print(out)
