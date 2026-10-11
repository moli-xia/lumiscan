"""
Synthetic phone photos of an ID card for the 证件扫描 edge test (androidTest assets).
Needs Python 3 with opencv-python and numpy:  python3 tools/testdata/make_card_photos.py <out dir>
Prints the projected sharp corners of the card (where its straight sides meet; the card itself
has rounded corners) — the ground truth used in PipelineTest.
"""
import sys
import cv2
import numpy as np

out = sys.argv[1] if len(sys.argv) > 1 else "."
rng = np.random.default_rng(7)
CW, CH, R = 856, 540, 32  # 85.6 x 54 mm at 10 px/mm, 3.2 mm corner radius


def noise(h, w, scale):
    small = rng.random((h // scale + 2, w // scale + 2)).astype(np.float32)
    n = cv2.resize(small, (w + 2 * scale, h + 2 * scale), interpolation=cv2.INTER_CUBIC)[:h, :w]
    return (n - n.mean()) / (n.std() + 1e-6)


def card():
    yy, xx = np.mgrid[0:CH, 0:CW].astype(np.float32)
    img = np.zeros((CH, CW, 3), np.float32)
    # pale blue-white security print with fine wavy guilloche lines
    img[:] = (238, 236, 228)
    img += (xx / CW * 10)[..., None] * np.array([1.0, 0.4, -0.6])
    wave = (np.sin(xx / 9 + 6 * np.sin(yy / 40)) > 0.94).astype(np.float32)
    img -= wave[..., None] * np.array([18, 10, 4])
    # photo, text lines, number
    cv2.rectangle(img, (560, 70), (790, 360), (205, 205, 210), -1)
    cv2.ellipse(img, (675, 190), (62, 78), 0, 0, 360, (150, 150, 160), -1)
    cv2.ellipse(img, (675, 370), (115, 95), 0, 180, 360, (120, 120, 130), -1)
    cv2.rectangle(img, (560, 360), (790, 362), (238, 236, 228), -1)
    for i, wdt in enumerate([180, 260, 300, 330, 250]):
        cv2.rectangle(img, (60, 80 + i * 56), (60 + wdt, 100 + i * 56), (70, 70, 75), -1)
    cv2.rectangle(img, (220, 440), (780, 470), (40, 40, 45), -1)
    mask = np.zeros((CH, CW), np.uint8)
    cv2.rectangle(mask, (R, 0), (CW - R, CH), 255, -1)
    cv2.rectangle(mask, (0, R), (CW, CH - R), 255, -1)
    for cx, cy in [(R, R), (CW - R, R), (CW - R, CH - R), (R, CH - R)]:
        cv2.circle(mask, (cx, cy), R, 255, -1, lineType=cv2.LINE_AA)
    return img, mask


def photo(name, desk, corners, W=1800, H=2400, shadow=0.35):
    img, mask = card()
    src = np.float32([[0, 0], [CW, 0], [CW, CH], [0, CH]])
    dst = np.float32(corners)
    M = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(img, M, (W, H), flags=cv2.INTER_LINEAR)
    a = cv2.warpPerspective(mask, M, (W, H), flags=cv2.INTER_LINEAR).astype(np.float32)[..., None] / 255
    sh = cv2.GaussianBlur(np.roll(np.roll(a[..., 0], 14, 0), 10, 1), (0, 0), 12)[..., None]
    base = desk * (1 - shadow * sh)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    light = (1.05 - 0.25 * np.hypot(xx - W * 0.3, yy - H * 0.2) / np.hypot(W, H))[..., None]
    res = (base * (1 - a) + warped * a) * light
    res = cv2.GaussianBlur(res, (0, 0), 1.1) + rng.normal(0, 3, res.shape)
    cv2.imwrite(f"{out}/{name}.jpg", np.clip(res, 0, 255).astype(np.uint8), [cv2.IMWRITE_JPEG_QUALITY, 88])
    print(name, [(round(float(x), 1), round(float(y), 1)) for x, y in corners])


W, H = 1800, 2400
wood = 95 + 18 * noise(H, W, 30) + 14 * np.sin(np.linspace(0, 70, W))[None, :]
desk_dark = np.stack([wood * 0.55, wood * 0.75, wood], -1)
light = 205 + 6 * noise(H, W, 40)
desk_light = np.stack([light * 0.98, light, light * 1.01], -1)
# Card filling most of the width (as the guide box asks), tilted in perspective.
photo("card_dark", desk_dark, [(190, 820), (1640, 900), (1590, 1830), (150, 1730)])
# Light desk: low contrast between card and table.
photo("card_light", desk_light, [(130, 900), (1660, 860), (1700, 1840), (170, 1820)], shadow=0.25)
