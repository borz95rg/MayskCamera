#!/usr/bin/env python3
"""
Миниатюры карточек эффектов: иллюстрированный портрет + сам эффект в своих точках лица.

  * шаблоны (assets/effects/templates.json) — кусочки PNG ставятся по тем же «at / w / align»,
    что и в камере;
  * 3D-маски (assets/ar_models/*.glb) — рендерятся z-буфером поверх портрета; невидимая
    «голова» закрывает части за лицом, как в приложении.

Запуск из корня репозитория:  python3 tools/thumbs/make_thumbs.py
"""
import json, os, sys
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, "tools", "ar_models"))
from portrait import portrait, paste_piece, card, P, U, N  # noqa: E402
import generate_models as gm  # noqa: E402

SS = 2  # суперсэмплинг 3D

def templates():
    cat = json.load(open(os.path.join(ASSETS, "effects", "templates.json"), encoding="utf-8"))
    for f in cat["faces"]:
        img = portrait()
        for pc in f["pieces"]:
            piece = Image.open(os.path.join(ASSETS, pc["png"])).convert("RGBA")
            paste_piece(img, piece, pc["at"], pc["w"], pc["align"])
        card(img, os.path.join(ASSETS, f["thumb"]))
        print("tpl", f["id"])

def raster_3d(parts, occluders):
    """Ортографический рендер (вид спереди) в RGBA размером N×N."""
    W = N * SS
    zbuf = np.full((W, W), -1e9, dtype=np.float32)
    rgb = np.zeros((W, W, 3), dtype=np.float32)
    alpha = np.zeros((W, W), dtype=np.float32)
    L = np.array([-0.35, 0.55, 1.0]); L /= np.linalg.norm(L)
    H = L + np.array([0, 0, 1.0]); H /= np.linalg.norm(H)
    ys, xs = np.mgrid[0:W, 0:W]

    def draw(p, depth_only, bias=0.0):
        pos = np.array(p.pos, dtype=np.float64); nrm = np.array(p.nrm, dtype=np.float64); col = np.array(p.col, dtype=np.float64)
        sx = (P(0, 0)[0] + pos[:, 0] * U) * SS
        sy = (P(0, 0)[1] - pos[:, 1] * U) * SS
        sz = pos[:, 2] - bias
        idx = np.array(p.idx).reshape(-1, 3)
        blend = (not depth_only) and p.alpha < 0.999
        for a, b, c in idx:
            x0, x1, x2 = sx[a], sx[b], sx[c]; y0, y1, y2 = sy[a], sy[b], sy[c]
            minx = max(int(np.floor(min(x0, x1, x2))), 0); maxx = min(int(np.ceil(max(x0, x1, x2))), W - 1)
            miny = max(int(np.floor(min(y0, y1, y2))), 0); maxy = min(int(np.ceil(max(y0, y1, y2))), W - 1)
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
            if depth_only:
                zb[m] = z[m]; continue
            n = nrm[a] + nrm[b] + nrm[c]; n /= max(np.linalg.norm(n), 1e-9)
            diff = abs(float(n @ L)); spec = abs(float(n @ H)) ** 24 * 0.28
            cc = (col[a, :3] + col[b, :3] + col[c, :3]) / 3 * (0.38 + 0.62 * diff) + spec
            cc = np.clip(cc, 0, 1)
            region = rgb[miny:maxy + 1, minx:maxx + 1]; al = alpha[miny:maxy + 1, minx:maxx + 1]
            if blend:
                k = p.alpha
                region[m] = region[m] * (1 - k) + cc * k
                al[m] = al[m] * (1 - k) + k
            else:
                region[m] = cc; al[m] = 1.0; zb[m] = z[m]

    for o in occluders: draw(o, True, bias=0.06)
    opaque = [p for p in parts if p.alpha >= 0.999]; trans = [p for p in parts if p.alpha < 0.999]
    for p in opaque: draw(p, False)
    for p in trans: draw(p, False)
    out = np.dstack([rgb * 255, alpha * 255]).astype(np.uint8)
    return Image.fromarray(out, "RGBA").resize((N, N), Image.LANCZOS)

def models():
    cat_path = os.path.join(ASSETS, "ar_models", "catalog.json")
    catalog = json.load(open(cat_path, encoding="utf-8"))
    by_id = {mid: fn for mid, title, c, fn in gm.MODELS}
    head = gm.Part("head", (0.86, 0.72, 0.62)).add(*gm.ellipsoid(np.array([0, 0.02, -0.58]), (0.84, 1.3, 0.75), seg=32, rings=20))
    for e in catalog:
        fn = by_id.get(e["id"])
        if fn is None: continue
        img = portrait(bg=("#2b1840", "#120a1e"))
        layer = raster_3d(fn(), [head])
        img.alpha_composite(layer)
        old = os.path.join(ASSETS, e["preview"])
        e["preview"] = os.path.splitext(e["preview"])[0] + ".jpg"
        card(img, os.path.join(ASSETS, e["preview"]))
        if old.endswith(".png") and os.path.exists(old): os.remove(old)   # старые PNG-превью
        print("3d ", e["id"])
    json.dump(catalog, open(cat_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

def none_card():
    """Карточка «Без эффекта» — чистый портрет."""
    card(portrait(), os.path.join(ASSETS, "effects", "thumbs", "none.jpg"))

if __name__ == "__main__":
    none_card()
    templates()
    models()
