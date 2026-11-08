#!/usr/bin/env python3
"""生成手环端使用的 PNG 图标（透明背景，4x 超采样后缩放）。

Vela 的 <image> 不支持着色，所以每个颜色单独出一张。
运行：python3 tools/gen-icons.py
输出：src/common/images/ic_*.png
"""

import math
import os

from PIL import Image, ImageDraw

SIZE = 32          # 最终尺寸（像素，显示时约 16-24dp）
SS = 4             # 超采样倍数
CANVAS = SIZE * SS

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'common', 'images')

SIDE = (0xb9, 0xc6, 0xd6, 255)     # 次级按钮
MAIN = (0x04, 0x24, 0x1c, 255)     # 主按钮（薄荷底上的深色）
DIM = (0x7d, 0x8e, 0xa3, 255)      # 次要
SOFT = (0x9a, 0xa9, 0xbc, 255)     # 文字同级


def canvas():
    img = Image.new('RGBA', (CANVAS, CANVAS), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img)


def save(img, name):
    img = img.resize((SIZE, SIZE), Image.LANCZOS)
    path = os.path.join(OUT, name)
    img.save(path, optimize=True)
    print('wrote', path)


def tri(draw, points, color):
    draw.polygon([(x * SS, y * SS) for x, y in points], fill=color)


def rect(draw, box, color, radius=0):
    x0, y0, x1, y1 = [v * SS for v in box]
    if radius:
        draw.rounded_rectangle([x0, y0, x1, y1], radius=radius * SS, fill=color)
    else:
        draw.rectangle([x0, y0, x1, y1], fill=color)


def play(color, name):
    img, d = canvas()
    tri(d, [(10, 7), (10, 25), (26, 16)], color)
    save(img, name)


def pause(color, name):
    img, d = canvas()
    rect(d, (9, 7, 14, 25), color, radius=1.5)
    rect(d, (18, 7, 23, 25), color, radius=1.5)
    save(img, name)


def step(color, name, direction):
    """direction=1 下一首，-1 上一首"""
    img, d = canvas()
    if direction > 0:
        tri(d, [(7, 7), (7, 25), (18, 16)], color)
        tri(d, [(16, 7), (16, 25), (27, 16)], color)
        rect(d, (26, 7, 27.5, 25), color)
    else:
        tri(d, [(25, 7), (25, 25), (14, 16)], color)
        tri(d, [(16, 7), (16, 25), (5, 16)], color)
        rect(d, (4.5, 7, 6, 25), color)
    save(img, name)


def circular(color, name, clockwise):
    """回退/快进：带箭头的圆弧"""
    img, d = canvas()
    box = [6 * SS, 6 * SS, 26 * SS, 26 * SS]
    if clockwise:
        d.arc(box, start=-55, end=235, fill=color, width=int(2.6 * SS))
        tri(d, [(24.5, 5.5), (28, 12), (20, 11)], color)
    else:
        d.arc(box, start=125, end=415, fill=color, width=int(2.6 * SS))
        tri(d, [(7.5, 5.5), (4, 12), (12, 11)], color)
    save(img, name)


def book(color, name):
    img, d = canvas()
    rect(d, (6, 5.5, 26, 26.5), color, radius=2.5)
    # 挖空内侧，做出书页的框
    rect(d, (9, 8.5, 23, 23.5), (0, 0, 0, 0), radius=1)
    rect(d, (12, 12, 20.5, 13.6), color)
    rect(d, (12, 16, 20.5, 17.6), color)
    rect(d, (12, 20, 18, 21.6), color)
    rect(d, (8.5, 8.5, 10, 23.5), color)
    save(img, name)


def gear(color, name):
    img, d = canvas()
    cx = cy = 16
    for i in range(8):
        a = math.radians(i * 45)
        x = cx + math.cos(a) * 9.2
        y = cy + math.sin(a) * 9.2
        d.ellipse([(x - 3.1) * SS, (y - 3.1) * SS, (x + 3.1) * SS, (y + 3.1) * SS], fill=color)
    d.ellipse([8 * SS, 8 * SS, 24 * SS, 24 * SS], fill=color)
    d.ellipse([13 * SS, 13 * SS, 19 * SS, 19 * SS], fill=(0, 0, 0, 0))
    save(img, name)


def chevron(color, name):
    img, d = canvas()
    d.line([(20 * SS, 8 * SS), (11 * SS, 16 * SS), (20 * SS, 24 * SS)],
           fill=color, width=int(3.2 * SS), joint='curve')
    save(img, name)


def plus(color, name):
    img, d = canvas()
    d.ellipse([3 * SS, 3 * SS, 29 * SS, 29 * SS], outline=color, width=int(2.2 * SS))
    d.line([(16 * SS, 9.5 * SS), (16 * SS, 22.5 * SS)], fill=color, width=int(2.6 * SS))
    d.line([(9.5 * SS, 16 * SS), (22.5 * SS, 16 * SS)], fill=color, width=int(2.6 * SS))
    save(img, name)


def main():
    os.makedirs(OUT, exist_ok=True)
    play(MAIN, 'ic_play.png')
    pause(MAIN, 'ic_pause.png')
    step(SIDE, 'ic_prev.png', -1)
    step(SIDE, 'ic_next.png', 1)
    circular(DIM, 'ic_rewind.png', False)
    circular(DIM, 'ic_forward.png', True)
    book(SOFT, 'ic_book.png')
    gear(SOFT, 'ic_gear.png')
    chevron(SOFT, 'ic_back.png')
    plus(SOFT, 'ic_add_lyric.png')


if __name__ == '__main__':
    main()
