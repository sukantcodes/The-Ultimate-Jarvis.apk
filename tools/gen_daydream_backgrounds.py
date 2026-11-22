#!/usr/bin/env python3
"""
Generates the JARVIS "Daydream" backgrounds for the Transparent (Crystal Mirror) theme.

Every image is 2160 x 3840 (4K, portrait) and drawn procedurally, so there is no photo licence to worry about.
Usage:  python3 gen_daydream.py <out_dir> [scene_id ...]
Writes  <out_dir>/<id>.webp   (full size)  and  <out_dir>/<id>_t.webp  (small preview for the picker).
"""
import sys, os
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

FW, FH = 2160, 3840
F32 = np.float32

# ---------------------------------------------------------------- helpers
def up(a, w=FW, h=FH):
    return np.asarray(Image.fromarray(np.ascontiguousarray(a, dtype=F32), 'F').resize((w, h), Image.BICUBIC), dtype=F32)

def vnoise(w, h, cx, cy, seed):
    g = np.random.default_rng(seed).random((cy + 2, cx + 2)).astype(F32)
    return np.asarray(Image.fromarray(g, 'F').resize((w, h), Image.BICUBIC), dtype=F32)

def fbm(cx, cy, octaves, seed, gain=0.5, w=FW, h=FH, lw=540, lh=960):
    """Fractal noise 0..1. Computed on a small grid and resized, so it is fast and smooth."""
    acc = np.zeros((lh, lw), F32); amp = 1.0; tot = 0.0
    for i in range(octaves):
        acc += amp * vnoise(lw, lh, cx * 2 ** i, cy * 2 ** i, seed + 31 * i)
        tot += amp; amp *= gain
    acc /= tot
    lo, hi = np.percentile(acc, 1), np.percentile(acc, 99)
    acc = np.clip((acc - lo) / (hi - lo + 1e-6), 0, 1)
    return up(acc, w, h) if (w, h) != (lw, lh) else acc

def sstep(a, b, x):
    t = np.clip((x - a) / (b - a + 1e-9), 0, 1)
    return (t * t * (3 - 2 * t)).astype(F32)

def mix(a, b, t):
    return a + (b - a) * t

def col(r, g, b):
    return np.array([r, g, b], F32) / 255.0

def vgrad(stops, h=FH, w=FW):
    y = np.linspace(0, 1, h, dtype=F32)
    pos = [s[0] for s in stops]
    chans = [np.interp(y, pos, [s[1][c] / 255.0 for s in stops]).astype(F32) for c in range(3)]
    img = np.empty((h, w, 3), F32)
    for c in range(3):
        img[:, :, c] = chans[c][:, None]
    return img

def yy_xx(h=FH, w=FW):
    y = np.linspace(0, 1, h, dtype=F32)[:, None]
    x = np.linspace(0, 1, w, dtype=F32)[None, :]
    return y, x

def glow(cx, cy, rx, ry=None, h=FH, w=FW, power=2.0):
    """Soft radial glow 0..1 centred at fractional (cx, cy); rx/ry are fractions of the width."""
    ry = ry or rx
    y, x = yy_xx(h, w)
    dx = (x - cx) * (w / float(w)) / rx
    dy = (y - cy) * (h / float(w)) / ry
    d = np.sqrt(dx * dx + dy * dy)
    return np.exp(-(d ** power)).astype(F32)

def add_color(img, mask, c, k=1.0):
    img += mask[:, :, None] * c[None, None, :] * k

def blend(img, mask, c):
    img[:] = img * (1 - mask[:, :, None]) + mask[:, :, None] * c[None, None, :]

def stars(n, seed, ymax=1.0, ymin=0.0, bright=1.0, fade_from=None):
    r = np.random.default_rng(seed)
    big = Image.new('L', (FW, FH), 0); d = ImageDraw.Draw(big)
    halo = Image.new('L', (FW, FH), 0); dh = ImageDraw.Draw(halo)
    for _ in range(n):
        x = r.random() * FW; y = (ymin + r.random() * (ymax - ymin)) * FH
        b = (r.random() ** 3) * bright
        rad = 1.0 + r.random() ** 6 * 4.5
        v = int(np.clip(60 + 195 * b, 0, 255))
        d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=v)
        if rad > 3.2:
            g = rad * 3.2
            dh.ellipse([x - g, y - g, x + g, y + g], fill=int(60 * b + 20))
    s = np.asarray(big.filter(ImageFilter.GaussianBlur(0.9)), dtype=F32) / 255.0
    hl = np.asarray(halo.filter(ImageFilter.GaussianBlur(14)), dtype=F32) / 255.0
    return np.clip(s + hl * 0.8, 0, 1)

def ridge(seed, base, amp, rough=5, w=FW):
    """1D skyline: returns fractional y (0..1) per column."""
    r = np.random.default_rng(seed)
    out = np.zeros(w, F32); a = 1.0; tot = 0.0
    for i in range(rough):
        n = 3 * 2 ** i
        pts = r.random(n + 3).astype(F32)
        xs = np.linspace(0, n + 2, w)
        out += a * np.interp(xs, np.arange(n + 3), pts); tot += a; a *= 0.5
    out /= tot
    out = (out - out.min()) / (out.max() - out.min() + 1e-6)
    return base + (out - 0.5) * amp

def finish(img, vig=0.28, warm=None, grain=0.0045, seed=7):
    h, w, _ = img.shape
    y, x = yy_xx(h, w)
    r = np.sqrt(((x - 0.5) * 1.0) ** 2 + ((y - 0.5) * 0.56) ** 2)
    v = 1 - vig * np.clip(r / 0.56, 0, 1) ** 2
    img *= v[:, :, None]
    # gentle filmic shoulder so highlights never clip into flat white
    img = 1 - np.exp(-img * 1.35)
    img /= (1 - np.exp(-1.35))
    img = np.clip(img, 0, 1)
    rng = np.random.default_rng(seed)
    for c in range(3):
        img[:, :, c] += rng.normal(0, grain, (h, w)).astype(F32)
    return np.clip(img, 0, 1)

def save(img, out_dir, sid):
    u8 = (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)
    im = Image.fromarray(u8, 'RGB')
    p = os.path.join(out_dir, sid + '.webp')
    im.save(p, 'WEBP', quality=88, method=5)
    t = im.resize((360, 640), Image.LANCZOS)
    t.save(os.path.join(out_dir, sid + '_t.webp'), 'WEBP', quality=84, method=5)
    lum = float(np.mean(0.2126 * img[:, :, 0] + 0.7152 * img[:, :, 1] + 0.0722 * img[:, :, 2]))
    print('%-16s %6.0f KB  mean luma %.2f' % (sid, os.path.getsize(p) / 1024, lum), flush=True)

def clouds_lit(seed, cx, cy, cover, soft, light_dir=-1):
    """Soft, wide cloud banks: returns density 0..1 and a gentle underlight 0..1 (no hard rim)."""
    n = fbm(cx, cy, 5, seed, gain=0.46)
    d = sstep(cover, cover + soft, n)
    small = Image.fromarray(d.astype(F32), 'F').resize((108, 192), Image.BOX)
    ds = np.asarray(small.resize((FW, FH), Image.BICUBIC), dtype=F32)
    lit = np.clip((np.roll(ds, 110 * light_dir, axis=0) - ds) * 1.5 + 0.50, 0, 1)
    return d, lit

# ---------------------------------------------------------------- scenes
def lavender_dusk():
    img = vgrad([(0, (20, 14, 52)), (0.30, (52, 34, 104)), (0.55, (128, 70, 140)), (0.74, (224, 118, 144)),
                 (0.88, (255, 170, 140)), (1, (255, 205, 160))])
    y, x = yy_xx()
    add_color(img, glow(0.5, 0.80, 0.55, 0.16), col(255, 176, 120), 0.50)
    s = stars(2600, 11, ymax=0.55, bright=1.0)
    img += (s * (1 - sstep(0.15, 0.58, y)))[:, :, None] * 0.9
    for i, (seed, cover, y0, y1, tint, k) in enumerate([
        (21, 0.50, 0.28, 0.66, col(255, 150, 170), 0.55),
        (22, 0.44, 0.50, 0.88, col(255, 190, 150), 0.62),
        (23, 0.48, 0.70, 0.98, col(255, 215, 175), 0.50)]):
        d, lit = clouds_lit(seed, 2, 7 + i * 2, cover, 0.34)
        band = sstep(y0 - 0.12, y0 + 0.02, y) * (1 - sstep(y1 - 0.02, y1 + 0.12, y))
        dens = d * band
        shade = mix(col(86, 52, 124), tint, lit[:, :, None])
        img[:] = img * (1 - dens[:, :, None] * 0.82) + shade * dens[:, :, None] * 0.82 * (0.7 + 0.5 * k)
    return finish(img, 0.30)

def aurora_lake():
    H = int(FH * 0.60)
    sky = vgrad([(0, (4, 8, 28)), (0.45, (8, 22, 56)), (0.85, (14, 56, 84)), (1, (26, 86, 110))], H, FW)
    y = np.linspace(0, 1, H, dtype=F32)[:, None]
    x = np.linspace(0, 1, FW, dtype=F32)[None, :]
    st = stars(3000, 5, ymax=0.60)[:H]
    sky += st[:, :, None] * (1 - sstep(0.5, 1.0, y))[:, :, None]
    ribbons = [(0.30, 0.10, col(60, 255, 170), 1.00), (0.42, 0.13, col(90, 220, 255), 0.85),
               (0.22, 0.08, col(190, 110, 255), 0.75), (0.52, 0.09, col(70, 255, 200), 0.55)]
    for i, (yc, thick, c, k) in enumerate(ribbons):
        warp = fbm(2 + i, 2, 4, 40 + i, w=FW, h=H, lw=540, lh=int(960 * 0.6)) - 0.5
        wave = 0.07 * np.sin(x * (5.0 + i * 1.7) + i * 2.0) + warp * 0.30
        d = (y - (yc + wave)) / thick
        prof = np.exp(-np.where(d > 0, d * 3.2, -d * 1.1) ** 2).astype(F32)
        rays = 0.55 + 0.45 * fbm(40 + i * 6, 1, 3, 60 + i, w=FW, h=H, lw=540, lh=int(960 * 0.6))
        sky += (prof * rays)[:, :, None] * c[None, None, :] * (0.85 * k)
    horizon = col(40, 120, 140)
    sky += (np.exp(-((y - 1.0) / 0.10) ** 2))[:, :, None] * horizon[None, None, :] * 0.35
    # tree line
    tl = ridge(77, 0.965, 0.07, 7) * H
    yi = np.arange(H, dtype=F32)[:, None]
    tmask = sstep(0, 1, (yi - tl[None, :]) / 2.0)
    sky = sky * (1 - tmask[:, :, None]) + col(3, 8, 14)[None, None, :] * tmask[:, :, None]
    # water: mirrored sky, rippled and darkened
    mirror = sky[::-1][: FH - H + 0]
    wh = FH - H
    mirror = np.asarray(Image.fromarray((np.clip(mirror, 0, 1) * 255).astype(np.uint8)).resize((FW, wh), Image.LANCZOS), dtype=F32) / 255.0
    rip = fbm(10, 70, 4, 91, w=FW, h=wh, lw=540, lh=int(960 * 0.4))
    sh = ((rip - 0.5) * 70).astype(np.int32)
    xs = (np.arange(FW)[None, :] + sh) % FW
    ys = np.arange(wh)[:, None] * np.ones((1, FW), np.int32)
    mirror = mirror[ys, xs]
    yw = np.linspace(0, 1, wh, dtype=F32)[:, None]
    fade = 0.80 * (1 - yw) ** 1.4 + 0.05
    water = mirror * fade[:, :, None] * 0.92 + vgrad([(0, (6, 20, 34)), (1, (3, 10, 22))], wh, FW) * 0.55
    water += (sstep(0.55, 1.0, rip) * 0.06 * (1 - yw))[:, :, None]
    img = np.concatenate([sky, water], axis=0)
    return finish(img, 0.32)

def cotton_sky():
    img = vgrad([(0, (70, 52, 150)), (0.30, (132, 84, 190)), (0.58, (236, 132, 190)), (0.80, (250, 176, 196)),
                 (1, (150, 200, 240))])
    y, x = yy_xx()
    add_color(img, glow(0.30, 0.62, 0.7, 0.28), col(255, 212, 230), 0.34)
    add_color(img, glow(0.85, 0.22, 0.55, 0.22), col(150, 190, 255), 0.30)
    layers = [(31, 0.50, col(255, 205, 225), (0.0, 0.55), 0.50), (32, 0.46, col(255, 240, 248), (0.25, 0.80), 0.70),
              (33, 0.44, col(210, 232, 255), (0.55, 1.05), 0.78)]
    for i, (seed, cover, tint, (y0, y1), k) in enumerate(layers):
        d, lit = clouds_lit(seed, 2, 6 + 2 * i, cover, 0.36, light_dir=1)
        band = sstep(y0 - 0.15, y0 + 0.05, y) * (1 - sstep(y1 - 0.05, y1 + 0.15, y))
        dens = (d * band * k).astype(F32)
        shade = mix(col(150, 100, 190), tint, lit[:, :, None])
        img[:] = img * (1 - dens[:, :, None]) + shade * dens[:, :, None]
    s = stars(1200, 8, ymax=0.3)
    img += (s * (1 - sstep(0.05, 0.32, y)))[:, :, None] * 0.55
    img *= 0.66
    return finish(img, 0.30)

def misty_peaks():
    img = vgrad([(0, (22, 18, 58)), (0.28, (70, 48, 120)), (0.50, (178, 96, 150)), (0.62, (250, 170, 150)),
                 (0.72, (255, 206, 170)), (1, (255, 206, 170))])
    y, x = yy_xx()
    s = stars(2200, 3, ymax=0.40)
    img += (s * (1 - sstep(0.05, 0.42, y)))[:, :, None] * 0.9
    moon = glow(0.74, 0.17, 0.045, 0.045, power=4.0)
    add_color(img, glow(0.74, 0.17, 0.30, 0.30), col(255, 220, 235), 0.28)
    blend(img, np.clip(moon * 1.4, 0, 1), col(255, 248, 240))
    layers = [(0.60, 0.20, col(150, 96, 150), 0.50), (0.67, 0.18, col(116, 76, 134), 0.42),
              (0.75, 0.16, col(84, 58, 118), 0.34), (0.84, 0.14, col(52, 38, 92), 0.24), (0.93, 0.10, col(22, 16, 50), 0.0)]
    yi = np.arange(FH, dtype=F32)[:, None] / FH
    for i, (base, amp, c, haze) in enumerate(layers):
        rl = ridge(200 + i * 13, base, amp * (0.55 + 0.1 * i), 6)
        m = sstep(0, 1, (yi - rl[None, :]) * FH / 2.2)
        depth = sstep(0.0, 0.30, (yi - rl[None, :]))
        layer_col = c[None, None, :] * (0.78 + 0.30 * (1 - depth))[:, :, None]
        mist = col(255, 200, 190)
        layer_col = mix(layer_col, mist[None, None, :], (haze * (1 - depth) * 0.45)[:, :, None])
        img[:] = img * (1 - m[:, :, None]) + layer_col * m[:, :, None]
        # mist band between layers
        mb = np.exp(-((yi - (rl[None, :] + 0.012)) / 0.030) ** 2) * (haze * 0.22)
        img += mb[:, :, None] * col(255, 190, 190)[None, None, :] * 0.5
    return finish(img, 0.30)

def ocean_dream():
    hy = 0.52
    img = vgrad([(0, (10, 22, 66)), (0.22, (28, 58, 128)), (0.40, (112, 96, 170)), (0.50, (255, 156, 128)),
                 (0.52, (255, 190, 130)), (0.53, (30, 70, 120)), (0.70, (12, 40, 86)), (1, (6, 20, 52))])
    y, x = yy_xx()
    sky = y < hy
    add_color(img, glow(0.50, hy - 0.01, 0.55, 0.09) * sky, col(255, 168, 110), 0.85)
    s = stars(1600, 14, ymax=0.30)
    img += (s * (1 - sstep(0.03, 0.30, y)))[:, :, None] * 0.7
    d, lit = clouds_lit(61, 2, 14, 0.52, 0.34)
    cm = d * sstep(0.10, 0.28, y) * (1 - sstep(hy - 0.04, hy, y)) * 0.8
    shade = mix(col(70, 60, 130), col(255, 170, 140), lit[:, :, None])
    img[:] = img * (1 - cm[:, :, None]) + shade * cm[:, :, None]
    sun = glow(0.5, hy - 0.005, 0.045, 0.045, power=3.0) * sky
    blend(img, np.clip(sun * 1.2, 0, 1), col(255, 240, 210))
    # sea: sun path + wave texture
    w1 = fbm(6, 90, 5, 71)
    w2 = fbm(14, 150, 4, 72)
    sea = (y >= hy).astype(F32)
    depth = np.clip((y - hy) / (1 - hy), 0, 1)
    path_w = 0.035 + 0.30 * depth ** 1.2
    path = np.exp(-(((x - 0.5) / path_w) ** 2)) * sea
    tex = sstep(0.52, 0.95, w1 * 0.6 + w2 * 0.5)
    add_color(img, path * (0.15 + 0.85 * tex) * (1 - 0.55 * depth), col(255, 190, 130), 0.95)
    add_color(img, sea * tex * 0.12 * (1 - depth), col(140, 160, 255), 1.0)
    add_color(img, glow(0.5, hy + 0.01, 0.9, 0.012) * sea, col(255, 180, 130), 0.30)
    return finish(img, 0.30)

def neon_bokeh():
    img = vgrad([(0, (8, 8, 36)), (0.4, (28, 14, 76)), (0.75, (62, 22, 104)), (1, (18, 14, 56))])
    for (cx, cy, rx, c, k) in [(0.15, 0.20, 0.50, col(255, 70, 170), 0.35), (0.90, 0.38, 0.45, col(60, 190, 255), 0.35),
                               (0.30, 0.78, 0.55, col(150, 80, 255), 0.40), (0.85, 0.92, 0.40, col(255, 150, 90), 0.28)]:
        add_color(img, glow(cx, cy, rx, rx * 1.2), c, k)
    r = np.random.default_rng(99)
    palette = [col(255, 90, 190), col(80, 210, 255), col(170, 110, 255), col(255, 170, 100), col(120, 255, 220)]
    layer = np.zeros((FH, FW, 3), F32)
    for i in range(70):
        rad = int(40 + (r.random() ** 2.2) * 250)
        cx = int(r.random() * FW); cy = int(r.random() * FH)
        c = palette[int(r.integers(0, len(palette)))]
        a = 0.07 + 0.15 * r.random()
        x0, x1 = max(0, cx - rad - 2), min(FW, cx + rad + 3)
        y0, y1 = max(0, cy - rad - 2), min(FH, cy + rad + 3)
        if x1 <= x0 or y1 <= y0: continue
        yy = np.arange(y0, y1, dtype=F32)[:, None] - cy
        xx = np.arange(x0, x1, dtype=F32)[None, :] - cx
        dd = np.sqrt(xx * xx + yy * yy) / rad
        disc = sstep(1.0, 0.94, dd) * (0.55 + 0.45 * dd ** 3)
        layer[y0:y1, x0:x1] += disc[:, :, None] * c[None, None, :] * a
    pil = Image.fromarray((np.clip(layer, 0, 1) * 255).astype(np.uint8))
    soft = np.asarray(pil.filter(ImageFilter.GaussianBlur(3.6)), dtype=F32) / 255.0
    img += soft * 1.5
    img += stars(500, 2, bright=0.8)[:, :, None] * 0.35
    return finish(img, 0.30)

def golden_dunes():
    img = vgrad([(0, (40, 24, 74)), (0.22, (112, 58, 120)), (0.38, (232, 120, 120)), (0.46, (255, 190, 130)),
                 (0.5, (255, 214, 156)), (1, (255, 214, 156))])
    y, x = yy_xx()
    add_color(img, glow(0.72, 0.43, 0.55, 0.13), col(255, 196, 130), 0.70)
    s = stars(1500, 4, ymax=0.26)
    img += (s * (1 - sstep(0.04, 0.27, y)))[:, :, None] * 0.7
    sun = glow(0.72, 0.425, 0.05, 0.05, power=3.0)
    blend(img, np.clip(sun * 1.3, 0, 1), col(255, 244, 214))
    yi = np.arange(FH, dtype=F32)[:, None] / FH
    layers = [(0.50, 0.050, col(214, 124, 112), col(120, 66, 112)), (0.58, 0.070, col(236, 146, 106), col(142, 74, 108)),
              (0.68, 0.090, col(248, 166, 100), col(150, 78, 100)), (0.80, 0.110, col(255, 186, 112), col(150, 78, 92)),
              (0.93, 0.120, col(255, 204, 130), col(140, 70, 80))]
    for i, (base, amp, lit_c, sh_c) in enumerate(layers):
        r = np.random.default_rng(300 + i)
        ph = r.random() * 6.28
        xs = np.linspace(0, 1, FW, dtype=F32)
        rl = base + amp * 0.55 * (0.62 * np.sin(xs * (1.15 + i * 0.35) * 3.14159 + ph) + 0.30 * np.sin(xs * (2.4 + i * 0.5) * 3.14159 + ph * 2.1) + 0.12 * np.sin(xs * 4.6 + ph * 0.7))
        slope = np.gradient(rl) * FW / (amp * 3.0 + 1e-6)
        m = sstep(0, 1, (yi - rl[None, :]) * FH / 2.4)
        sl = np.clip(0.55 + slope * 0.28, 0.12, 0.95)[None, :]
        c = mix(sh_c[None, None, :], lit_c[None, None, :], sl[:, :, None])
        depth = np.clip((yi - rl[None, :]) / 0.22, 0, 1)
        c = c * (1.0 - 0.18 * depth)[:, :, None]
        img[:] = img * (1 - m[:, :, None]) + c * m[:, :, None]
        crest = np.exp(-((yi - rl[None, :]) / 0.004) ** 2) * 0.35 * (i + 1) / 5
        img += crest[:, :, None] * col(255, 224, 170)[None, None, :]
    img *= 0.86
    return finish(img, 0.30)

def nebula():
    img = vgrad([(0, (4, 4, 20)), (0.5, (10, 8, 34)), (1, (6, 6, 26))])
    base = fbm(2, 3, 6, 501)
    warp = fbm(3, 4, 5, 502)
    n = fbm(2, 3, 6, 503)
    field = np.clip(0.55 * base + 0.60 * warp * n + 0.1, 0, 1)
    a = sstep(0.38, 0.85, field)
    b = sstep(0.50, 0.95, fbm(2, 3, 5, 504) * 0.7 + warp * 0.4)
    c = sstep(0.55, 0.98, fbm(3, 4, 5, 505))
    add_color(img, a, col(110, 60, 230), 0.85)
    add_color(img, b, col(240, 80, 180), 0.55)
    add_color(img, c, col(60, 150, 255), 0.55)
    core = glow(0.62, 0.40, 0.40, 0.22)
    add_color(img, core * a, col(255, 190, 230), 0.60)
    s = stars(5200, 17, bright=1.0)
    img += s[:, :, None] * (1.0 - 0.35 * a[:, :, None]) * 1.0
    return finish(img, 0.32)

SCENES = [('dd_lavender_dusk', lavender_dusk), ('dd_aurora_lake', aurora_lake), ('dd_cotton_sky', cotton_sky),
          ('dd_misty_peaks', misty_peaks), ('dd_ocean_dream', ocean_dream), ('dd_neon_bokeh', neon_bokeh),
          ('dd_golden_dunes', golden_dunes), ('dd_nebula', nebula)]

if __name__ == '__main__':
    out = sys.argv[1]; only = set(sys.argv[2:])
    os.makedirs(out, exist_ok=True)
    for sid, fn in SCENES:
        if only and sid not in only: continue
        save(fn(), out, sid)
