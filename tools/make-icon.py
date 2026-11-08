from PIL import Image, ImageDraw, ImageFilter
import math, os

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)

def make(size):
    S = 4
    W = size * S
    img = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    top = (6, 10, 16)
    bottom = (10, 21, 33)
    for y in range(W):
        t = y / max(1, W - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        d.line([(0, y), (W, y)], fill=c)

    cx = cy = W / 2
    u = W / 192.0

    halo = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    r = 62 * u
    hd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(52, 190, 168, 26))
    halo = halo.filter(ImageFilter.GaussianBlur(34 * u / 4))
    img = Image.alpha_composite(img, halo)
    d = ImageDraw.Draw(img)

    rec_r = 64 * u
    d.ellipse([cx - rec_r, cy - rec_r, cx + rec_r, cy + rec_r], fill=(11, 20, 29, 255), outline=(20, 34, 47, 255), width=max(1, int(0.8 * u)))

    for gr in (52, 38):
        rr = gr * u
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=(23, 39, 53, 255), width=max(1, int(0.55 * u)))

    label_r = 29 * u
    d.ellipse([cx - label_r, cy - label_r, cx + label_r, cy + label_r], fill=(14, 25, 35, 255), outline=(33, 61, 78, 255), width=max(1, int(0.7 * u)))

    cyan = (79, 216, 255)
    mint = (110, 243, 197)
    arc_r = 64 * u
    stroke = 6 * u
    start, end = -68, 76
    steps = 140
    glow = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(steps):
        a1 = math.radians(start + (end - start) * i / steps)
        a2 = math.radians(start + (end - start) * (i + 1) / steps)
        p1 = (cx + math.cos(a1) * arc_r, cy + math.sin(a1) * arc_r)
        p2 = (cx + math.cos(a2) * arc_r, cy + math.sin(a2) * arc_r)
        c = lerp(cyan, mint, i / steps)
        gd.line([p1, p2], fill=c[:3] + (150,), width=int(stroke))
    glow = glow.filter(ImageFilter.GaussianBlur(14 * u / 4))
    img = Image.alpha_composite(img, glow)
    d = ImageDraw.Draw(img)

    for i in range(steps + 1):
        a = math.radians(start + (end - start) * i / steps)
        p = (cx + math.cos(a) * arc_r, cy + math.sin(a) * arc_r)
        rr = stroke / 2
        d.ellipse([p[0] - rr, p[1] - rr, p[0] + rr, p[1] + rr], fill=lerp(cyan, mint, i / steps))

    def bar(x0, y0, x1, y1, color):
        d.rounded_rectangle([x0 * u, y0 * u, x1 * u, y1 * u], radius=(y1 - y0) * u / 2, fill=color)

    bar(82, 88.5, 108, 93.5, (110, 243, 197, 255))
    bar(82, 97.5, 100, 101.5, (86, 114, 140, 255))

    return img.resize((size, size), Image.LANCZOS)

os.makedirs('src/common/images', exist_ok=True)
os.makedirs('assets', exist_ok=True)
make(192).save('src/common/images/icon.png', optimize=True)
make(512).save('assets/icon-512.png', optimize=True)
make(96).save('assets/icon-96.png', optimize=True)
print('done')
