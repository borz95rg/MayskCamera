#!/usr/bin/env python3
"""
Миниатюры карточек эффектов: фото модели из макета + сам эффект в своих точках лица.

Положение головы на фото (source/model_face.png) измерено один раз по контрольным точкам
(уголки глаз, кончик носа, губы, подбородок, лоб) и сведено к «осям головы» в пикселях:
точка модели (x, y, z) в лицевых единицах → O + x·R + y·U + z·F. Это те же лицевые единицы,
что и в камере (1.0 = расстояние между внешними уголками глаз), поэтому:
  * кусочки шаблонов (templates.json) ставятся по своим «at / w / align» с наклоном головы;
  * 3D-модели (ar_models/*.glb) проецируются по тем же осям, z-буфер с «невидимой головой»
    закрывает дальние части — как в приложении.

Запуск из корня репозитория:  python3 tools/thumbs/make_thumbs.py
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageFilter, ImageEnhance

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
sys.path.insert(0, os.path.join(ROOT, "tools", "ar_models"))
import generate_models as gm  # noqa: E402

# ---------------------------------------------------------------- фото и оси головы
PHOTO = os.path.join(HERE, "source", "model_face.png")
TOP = 170                    # сколько пикселей «стены» дорисовать сверху (под ушки и короны)
O = np.array([172.5, 178.25 + TOP])        # середина между внешними уголками глаз
R = np.array([135.0, -26.5])               # +1 по x (вправо по лицу)
U = np.array([-12.9, -100.8])              # +1 по y (вверх по лицу)
F = np.array([31.6, -41.5])                # +1 по z (к камере)
S = float(np.linalg.norm(R))               # пикселей на лицевую единицу (по ширине лица)
ROLL = math.degrees(math.atan2(R[1], R[0]))
SIN_YAW = F[0] / S                         # голова повёрнута: правая сторона лица дальше

def P(x, y, z=0.0):
    return O + x * R + y * U + z * F

def base():
    """Фото с дорисованной сверху стеной (растянутая и размытая верхняя полоса)."""
    ph = Image.open(PHOTO).convert("RGB")
    W, H = ph.size
    strip = ph.crop((0, 0, W, 6)).resize((W, TOP), Image.BILINEAR).filter(ImageFilter.GaussianBlur(14))
    img = Image.new("RGB", (W, H + TOP))
    img.paste(strip, (0, 0)); img.paste(ph, (0, TOP))
    # мягкий шов
    seam = img.crop((0, TOP - 12, W, TOP + 12)).filter(ImageFilter.GaussianBlur(4))
    img.paste(seam, (0, TOP - 12))
    img = ImageEnhance.Contrast(img).enhance(1.04)
    return img.convert("RGBA")

def card(img, path, w=320, h=400):
    """Карточка 4:5 вокруг головы (с местом под ушки и короны)."""
    cx, top = O[0] + 0.1 * S, O[1] - 1.85 * S
    bw = min(2.2 * S, img.width); bh = bw * h / w
    x0 = max(0.0, min(img.width - bw, cx - bw / 2)); y0 = max(0.0, min(img.height - bh, top))
    box = (int(x0), int(y0), int(x0 + bw), int(y0 + bh))     # целиком внутри фото — без чёрных полос
    img.crop(box).resize((w, h), Image.LANCZOS).convert("RGB").save(path, quality=90)

# ---------------------------------------------------------------- шаблоны
def paste_piece(img, piece, at, w, align):
    pw = max(1, int(w * S)); ph = max(1, int(piece.height * pw / piece.width))
    r = piece.resize((pw, ph), Image.LANCZOS)
    z = at[2] if len(at) > 2 else 0.0
    if align == "bottom":
        # низ кусочка в точке → центр выше на половину высоты (вдоль оси «вверх» лица)
        c = P(at[0], at[1], z) + (ph / 2) * (U / np.linalg.norm(U))
    else:
        c = P(at[0], at[1], z)
    rot = r.rotate(-ROLL, resample=Image.BICUBIC, expand=True)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    layer.paste(rot, (int(c[0] - rot.width / 2), int(c[1] - rot.height / 2)), rot)
    img.alpha_composite(layer)

def templates():
    cat = json.load(open(os.path.join(ASSETS, "effects", "templates.json"), encoding="utf-8"))
    for f in cat["faces"]:
        img = base()
        for pc in f["pieces"]:
            paste_piece(img, Image.open(os.path.join(ASSETS, pc["png"])).convert("RGBA"), pc["at"], pc["w"], pc["align"])
        card(img, os.path.join(ASSETS, f["thumb"]))
        print("tpl", f["id"])

# ---------------------------------------------------------------- 3D
SS = 3   # суперсэмплинг

def raster_3d(size, parts, occluders):
    W, H = size[0] * SS, size[1] * SS
    zbuf = np.full((H, W), -1e9, dtype=np.float32)
    rgb = np.zeros((H, W, 3), dtype=np.float32); alpha = np.zeros((H, W), dtype=np.float32)
    L = np.array([-0.35, 0.55, 1.0]); L /= np.linalg.norm(L)
    Hh = L + np.array([0, 0, 1.0]); Hh /= np.linalg.norm(Hh)
    ys, xs = np.mgrid[0:H, 0:W]
    cos_yaw = math.sqrt(max(0.0, 1 - SIN_YAW ** 2))

    def draw(p, depth_only, bias=0.0):
        pos = np.array(p.pos, dtype=np.float64); nrm = np.array(p.nrm, dtype=np.float64); col = np.array(p.col, dtype=np.float64)
        scr = O[None, :] + pos[:, 0:1] * R + pos[:, 1:2] * U + pos[:, 2:3] * F
        sx = scr[:, 0] * SS; sy = scr[:, 1] * SS
        sz = pos[:, 2] * cos_yaw - pos[:, 0] * SIN_YAW - bias      # глубина к камере
        idx = np.array(p.idx).reshape(-1, 3)
        blend = (not depth_only) and p.alpha < 0.999
        for a, b, c in idx:
            x0, x1, x2 = sx[a], sx[b], sx[c]; y0, y1, y2 = sy[a], sy[b], sy[c]
            minx = max(int(min(x0, x1, x2)), 0); maxx = min(int(max(x0, x1, x2)) + 1, W - 1)
            miny = max(int(min(y0, y1, y2)), 0); maxy = min(int(max(y0, y1, y2)) + 1, H - 1)
            if minx > maxx or miny > maxy: continue
            den = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
            if abs(den) < 1e-9: continue
            px = xs[miny:maxy + 1, minx:maxx + 1] + 0.5; py = ys[miny:maxy + 1, minx:maxx + 1] + 0.5
            w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / den
            w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / den
            w2 = 1 - w0 - w1
            m = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not m.any(): continue
            z = w0 * sz[a] + w1 * sz[b] + w2 * sz[c]
            zb = zbuf[miny:maxy + 1, minx:maxx + 1]
            m &= z >= zb
            if not m.any(): continue
            if depth_only: zb[m] = z[m]; continue
            n = nrm[a] + nrm[b] + nrm[c]; n /= max(np.linalg.norm(n), 1e-9)
            diff = abs(float(n @ L)); spec = abs(float(n @ Hh)) ** 24 * 0.3
            cc = np.clip((col[a, :3] + col[b, :3] + col[c, :3]) / 3 * (0.4 + 0.6 * diff) + spec, 0, 1)
            region = rgb[miny:maxy + 1, minx:maxx + 1]; al = alpha[miny:maxy + 1, minx:maxx + 1]
            if blend:
                k = p.alpha; region[m] = region[m] * (1 - k) + cc * k; al[m] = al[m] * (1 - k) + k
            else:
                region[m] = cc; al[m] = 1.0; zb[m] = z[m]

    for o in occluders: draw(o, True, bias=0.06)
    for p in [q for q in parts if q.alpha >= 0.999] + [q for q in parts if q.alpha < 0.999]: draw(p, False)
    out = np.dstack([rgb * 255, alpha * 255]).astype(np.uint8)
    return Image.fromarray(out, "RGBA").resize(size, Image.LANCZOS)

def models():
    cat_path = os.path.join(ASSETS, "ar_models", "catalog.json")
    catalog = json.load(open(cat_path, encoding="utf-8"))
    by_id = {mid: fn for mid, title, c, fn in gm.MODELS}
    head = gm.Part("head", (0.86, 0.72, 0.62)).add(*gm.ellipsoid(np.array([0, 0.0, -0.55]), (0.86, 1.3, 0.78), seg=32, rings=20))
    for e in catalog:
        fn = by_id.get(e["id"])
        if fn is None: continue
        img = base()
        img.alpha_composite(raster_3d(img.size, fn(), [head]))
        old = os.path.join(ASSETS, e["preview"])
        e["preview"] = os.path.splitext(e["preview"])[0] + ".jpg"
        card(img, os.path.join(ASSETS, e["preview"]))
        if old.endswith(".png") and os.path.exists(old): os.remove(old)   # старые PNG-превью
        print("3d ", e["id"])
    json.dump(catalog, open(cat_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    card(base(), os.path.join(ASSETS, "effects", "thumbs", "none.jpg"))   # «Без эффекта»
    templates()
    models()
