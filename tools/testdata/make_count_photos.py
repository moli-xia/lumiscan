"""
Synthetic photos for the 拍照计数 tests (androidTest assets): rebar and pipe bundles filling the
frame, logs, pills on wood with shadows, coins on unevenly lit paper. Uneven light, texture, touching
objects and JPEG noise on purpose. Needs Python 3 with opencv-python and numpy:
    python3 tools/testdata/make_count_photos.py <out dir>
Writes truth.json with the number of objects in each photo.
"""
import json
import os
import sys

import cv2
import numpy as np

out = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(out, exist_ok=True)
truth = {}

def noise_tex(h, w, scale, rng, octaves=4):
    acc = np.zeros((h, w), np.float32); amp = 1.0; tot = 0
    for o in range(octaves):
        s = max(1, int(scale / (2 ** o)))
        small = rng.random((h // s + 2, w // s + 2)).astype(np.float32)
        acc += amp * cv2.resize(small, (w + s * 2, h + s * 2), interpolation=cv2.INTER_CUBIC)[:h, :w]
        tot += amp; amp *= 0.5
    acc /= tot
    return (acc - acc.mean()) / (acc.std() + 1e-6)

def light(h, w, rng, strength=0.45):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    cx, cy = rng.uniform(-0.3, 1.3) * w, rng.uniform(-0.3, 1.3) * h
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / np.hypot(w, h)
    g = 1.0 - strength * d / d.max()
    return g[..., None]

def finish(img, rng, name, n, blur=1.2, noise=4.0, q=85):
    img = cv2.GaussianBlur(img, (0, 0), blur)
    img = img + rng.normal(0, noise, img.shape)
    img = np.clip(img, 0, 255).astype(np.uint8)
    s = 1200 / max(img.shape[:2])
    if s < 1: img = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    cv2.imwrite(f"{out}/{name}.jpg", img, [cv2.IMWRITE_JPEG_QUALITY, q])
    truth[name] = n

def packed(w, h, r, rng, region, jitter=0.12, rvar=0.08, gap=1.04):
    """Hexagonal packing of circles inside polygon region mask."""
    pts = []
    dy = 2 * r * gap * np.sqrt(3) / 2
    row = 0; y = -r
    while y < h + r:
        off = (row % 2) * r * gap
        x = -r + off
        while x < w + r:
            px = x + rng.normal(0, jitter * r); py = y + rng.normal(0, jitter * r)
            rr = r * (1 + rng.normal(0, rvar))
            if region(px, py): pts.append((px, py, rr))
            x += 2 * r * gap
        y += dy; row += 1
    return pts

# 1. rebar bundle filling most of the frame
def rebar(seed, name, r=30, W=1200, H=1600):
    rng = np.random.default_rng(seed)
    bg = 90 + 25 * noise_tex(H, W, 60, rng)
    img = np.stack([bg * 0.95, bg, bg * 1.05], -1)
    cx, cy, R = W / 2 + rng.uniform(-40, 40), H / 2 + rng.uniform(-60, 60), min(W, H) * 0.52
    pts = packed(W, H, r, rng, lambda x, y: (x - cx) ** 2 + ((y - cy) * 0.8) ** 2 < R ** 2)
    # dark interior (gaps between bars)
    hull = np.zeros((H, W), np.uint8)
    for (x, y, rr) in pts: cv2.circle(hull, (int(x), int(y)), int(rr * 1.15), 255, -1)
    hull = cv2.morphologyEx(hull, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (31, 31)))
    gapc = 25 + 12 * noise_tex(H, W, 8, rng)
    img[hull > 0] = np.stack([gapc] * 3, -1)[hull > 0]
    tex = noise_tex(H, W, 6, rng)
    for (x, y, rr) in pts:
        m = np.zeros((H, W), np.uint8); cv2.circle(m, (int(x), int(y)), int(rr), 255, -1)
        base = np.array([rng.uniform(95, 160)] * 3) * np.array([0.75, 0.9, 1.15]) if rng.random() < 0.5 else np.array([rng.uniform(130, 200)] * 3)
        sel = m > 0
        shade = (1 + 0.18 * tex[sel])[:, None]
        img[sel] = base[None, :] * shade
        cv2.circle(img, (int(x), int(y)), int(rr), tuple(float(v) * 0.55 for v in base), 2)
        # ribs/markings
        if rng.random() < 0.4:
            cv2.line(img, (int(x - rr * 0.6), int(y)), (int(x + rr * 0.6), int(y + rr * 0.1)), tuple(float(v) * 0.7 for v in base), 2)
    img = img * light(H, W, rng, 0.55)
    n = sum(1 for (x, y, rr) in pts if 0 <= x < W and 0 <= y < H)
    finish(img, rng, name, n)

# 2. pipe ends (rings with dark holes)
def pipes(seed, name, r=55, W=1200, H=1600):
    rng = np.random.default_rng(seed)
    bg = 150 + 20 * noise_tex(H, W, 80, rng)
    img = np.stack([bg, bg, bg], -1)
    cx, cy = W / 2, H / 2
    pts = packed(W, H, r, rng, lambda x, y: abs(x - cx) < W * 0.42 and abs(y - cy) < H * 0.4, jitter=0.05, rvar=0.03, gap=1.01)
    for (x, y, rr) in pts:
        cv2.circle(img, (int(x), int(y)), int(rr), (170, 172, 176), -1)
        cv2.circle(img, (int(x), int(y)), int(rr), (60, 60, 60), 3)
        cv2.circle(img, (int(x + rr * 0.05), int(y + rr * 0.08)), int(rr * 0.78), (35, 35, 38), -1)
    img = img * light(H, W, rng, 0.5)
    finish(img, rng, name, len(pts))

# 3. white pills on wood, some touching, with shadows
def pills(seed, name, n=45, W=1500, H=1500, rad=(34, 34)):
    rng = np.random.default_rng(seed)
    g = noise_tex(H, W, 40, rng)
    grain = np.sin(np.linspace(0, 60, W)[None, :] + 3 * noise_tex(H, W, 120, rng)) * 0.5
    wood = 120 + 18 * g + 22 * grain
    img = np.stack([wood * 0.55, wood * 0.75, wood * 1.0], -1)
    pts = []
    while len(pts) < n:
        if pts and rng.random() < 0.35:  # touch a previous pill
            px, py, a, b, ang = pts[rng.integers(len(pts))]
            t = rng.uniform(0, 2 * np.pi)
            x, y = px + np.cos(t) * (a + rad[0]) * 0.98, py + np.sin(t) * (a + rad[0]) * 0.98
        else:
            x, y = rng.uniform(80, W - 80), rng.uniform(80, H - 80)
        a = rad[0] * rng.uniform(0.95, 1.05); b = rad[1] * rng.uniform(0.95, 1.05)
        if not (60 < x < W - 60 and 60 < y < H - 60): continue
        if all(np.hypot(x - p[0], y - p[1]) > (a + p[2]) * 0.96 for p in pts):
            pts.append((x, y, a, b, rng.uniform(0, 180)))
    sh = np.zeros((H, W), np.float32)
    for (x, y, a, b, ang) in pts:
        cv2.ellipse(sh, (int(x + 8), int(y + 10)), (int(a), int(b)), ang, 0, 360, 1.0, -1)
    sh = cv2.GaussianBlur(sh, (0, 0), 7)
    img = img * (1 - 0.45 * sh[..., None])
    for (x, y, a, b, ang) in pts:
        cv2.ellipse(img, (int(x), int(y)), (int(a), int(b)), ang, 0, 360, (225, 228, 232), -1)
        cv2.ellipse(img, (int(x), int(y)), (int(a), int(b)), ang, 0, 360, (185, 188, 192), 2)
        cv2.line(img, (int(x - a * 0.6), int(y)), (int(x + a * 0.6), int(y)), (190, 192, 196), 2)
    img = img * light(H, W, rng, 0.5)
    finish(img, rng, name, n)

# 4. dark nuts/coins on white paper, strong uneven light
def coins(seed, name, n=28, W=1600, H=1200):
    rng = np.random.default_rng(seed)
    img = np.full((H, W, 3), 238.0)
    img += 6 * noise_tex(H, W, 30, rng)[..., None]
    pts = []
    while len(pts) < n:
        r = rng.uniform(38, 46)
        x, y = rng.uniform(r + 20, W - r - 20), rng.uniform(r + 20, H - r - 20)
        close = [p for p in pts if np.hypot(x - p[0], y - p[1]) < r + p[2] - 1]
        if close: continue
        pts.append((x, y, r))
    for (x, y, r) in pts:
        cv2.circle(img, (int(x + 5), int(y + 6)), int(r), (190, 190, 190), -1)
    img = cv2.GaussianBlur(img, (0, 0), 5)
    for (x, y, r) in pts:
        col = (60, 85, 140) if rng.random() < 0.5 else (95, 95, 100)
        cv2.circle(img, (int(x), int(y)), int(r), col, -1)
        cv2.circle(img, (int(x), int(y)), int(r * 0.8), tuple(c * 1.35 for c in col), 2)
        cv2.line(img, (int(x - 6), int(y - 14)), (int(x + 2), int(y + 14)), tuple(c * 1.4 for c in col), 4)
    img = img * light(H, W, rng, 0.75)
    finish(img, rng, name, n)

# 5. boxes/cartons from above: rounded squares in a grid touching, thin dark gaps
def boxes(seed, name, W=1400, H=1400, cols=7, rows=7):
    rng = np.random.default_rng(seed)
    img = np.full((H, W, 3), 50.0)
    cw = W * 0.9 / cols; ch = H * 0.9 / rows; n = 0
    for i in range(rows):
        for j in range(cols):
            x0 = W * 0.05 + j * cw + rng.normal(0, 2); y0 = H * 0.05 + i * ch + rng.normal(0, 2)
            col = np.array([120, 160, 200]) * rng.uniform(0.9, 1.1)
            cv2.rectangle(img, (int(x0 + 3), int(y0 + 3)), (int(x0 + cw - 3), int(y0 + ch - 3)), col.tolist(), -1)
            cv2.rectangle(img, (int(x0 + cw * 0.2), int(y0 + ch * 0.4)), (int(x0 + cw * 0.8), int(y0 + ch * 0.55)), (40, 60, 160), -1)
            n += 1
    img = img * light(H, W, rng, 0.5)
    finish(img, rng, name, n)

# 6. log ends: bigger circles, varied size, ring texture
def logs(seed, name, W=1600, H=1200, r=70):
    rng = np.random.default_rng(seed)
    bg = 110 + 30 * noise_tex(H, W, 50, rng)
    img = np.stack([bg * 0.7, bg * 0.85, bg * 0.8], -1)
    pts = packed(W, H, r, rng, lambda x, y: abs(x - W / 2) < W * 0.4 and abs(y - H / 2) < H * 0.38, jitter=0.15, rvar=0.12, gap=1.03)
    for (x, y, rr) in pts:
        cv2.circle(img, (int(x), int(y)), int(rr), (60, 70, 85), -1)
        cv2.circle(img, (int(x), int(y)), int(rr * 0.9), (120, 170, 205), -1)
        for k in range(1, 7):
            cv2.circle(img, (int(x + rng.normal(0, 2)), int(y + rng.normal(0, 2))), int(rr * 0.9 * k / 7), (95, 140, 180), 2)
        if rng.random() < 0.5:
            cv2.line(img, (int(x), int(y)), (int(x + rr * 0.8), int(y + rr * 0.3)), (70, 100, 130), 3)
    img = img * light(H, W, rng, 0.5)
    finish(img, rng, name, len(pts))

for i in range(3): rebar(10 + i, f"rebar{i}", r=[40, 50, 32][i])
for i in range(2): pipes(20 + i, f"pipes{i}", r=[55, 42][i])
for i in range(2): pills(30 + i, f"pills{i}", n=[45, 30][i])
for i in range(2): coins(40 + i, f"coins{i}", n=[28, 18][i])
boxes(50, "boxes0")
logs(60, "logs0")
json.dump(truth, open(f"{out}/truth.json", "w"), indent=1)
print(truth)
