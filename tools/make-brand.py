from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = '/system/fonts/NotoSansCJK-Regular.ttc'

def font(size):
    return ImageFont.truetype(FONT, size)

W, H = 1200, 720
img = Image.new('RGB', (W, H), (7, 11, 16))
d = ImageDraw.Draw(img)

top = (7, 11, 16)
bottom = (10, 20, 32)
for y in range(H):
    t = y / (H - 1)
    c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    d.line([(0, y), (W, y)], fill=c)

glow = Image.new('RGBA', (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse([60, 150, 560, 650], fill=(60, 220, 190, 26))
glow = glow.filter(ImageFilter.GaussianBlur(90))
img = Image.alpha_composite(img.convert('RGBA'), glow).convert('RGB')
d = ImageDraw.Draw(img)

icon = Image.open('assets/icon-512.png').convert('RGBA').resize((320, 320), Image.LANCZOS)
mask = Image.new('L', (320, 320), 0)
md = ImageDraw.Draw(mask)
md.rounded_rectangle([0, 0, 320, 320], radius=72, fill=255)
img.paste(icon, (96, 200), mask)
d = ImageDraw.Draw(img)

d.rounded_rectangle([96, 200, 416, 520], radius=72, outline=(28, 45, 60), width=2)

name_font = font(96)
d.text((470, 230), '弧光歌词', font=name_font, fill=(238, 244, 251), stroke_width=1, stroke_fill=(238, 244, 251))

en_font = font(26)
x = 476
for ch in 'ARCLYRIC':
    d.text((x, 372), ch, font=en_font, fill=(110, 243, 197))
    x += d.textlength(ch, font=en_font) + 10

tag_font = font(30)
d.text((474, 424), '把歌词戴在腕上', font=tag_font, fill=(154, 169, 188))

chip_font = font(19)
chips = [((110, 243, 197), '#6EF3C5'), ((79, 216, 255), '#4FD8FF'), ((255, 196, 107), '#FFC46B')]
cx = 474
for color, label in chips:
    d.rounded_rectangle([cx, 520, cx + 26, 546], radius=8, fill=color)
    d.text((cx + 38, 521), label, font=chip_font, fill=(154, 169, 188))
    cx += 190

d.text((474, 586), 'Vela OS · 小米手环 · 同步歌词 / 歌词本', font=font(21), fill=(99, 116, 138))

d.line([(96, 640), (1104, 640)], fill=(28, 45, 60), width=1)
d.text((96, 656), 'MUSIC  ·  LYRICS  ·  WRIST', font=font(18), fill=(79, 96, 112))

img.save('assets/brand-preview.png', optimize=True)
print('saved')
