#!/usr/bin/env python3
"""
Сборка шаблонов эффектов (MayskCamera_Effect_Templates v1.0) в ресурсы приложения.

SVG из пакета нарисованы в разных местах холста 512×512 (очки ниже румян и т.п.), поэтому
каждый шаблон разбит на «кусочки» с собственной привязкой к лицу:
  rect  — область на холсте 512×512 (уточняется по прозрачности),
  at    — точка на лице в «лицевых единицах» (1.0 = расстояние между внешними уголками глаз;
          +X вправо, +Y вверх, +Z к камере; кончик носа ≈ (0,-0.55,0.45), макушка ≈ (0,1.35,-0.4)),
  w     — ширина кусочка в лицевых единицах,
  align — "center" (центр кусочка в точке) или "bottom" (низ кусочка в точке — для ушек и корон).

Результат: assets/effects/templates.json, png-кусочки, превью карточек, фоны (jpg) и рамки (png).
Запуск: python3 build_templates.py <пакет> <app/src/main/assets/effects>
"""
import json, os, sys, io
import cairosvg
from PIL import Image, ImageFilter, ImageDraw

SRC, OUT = sys.argv[1], sys.argv[2]
S = 1024  # растеризуем в 2× (холст 512 → 1024)

FACE = {
    # id: (svg, [(rect, at, w, align)], glow)
    "cat":      ("animal_cat", [((90, 10, 422, 175), (0, 1.12, -0.25), 1.55, "bottom"), ((30, 255, 482, 360), (0, -0.62, 0.42), 1.95, "center")], 0.5),
    "puppy":    ("animal_dog", [((40, 0, 470, 160), (0, 1.05, -0.3), 1.75, "bottom"), ((170, 270, 330, 400), (0, -0.62, 0.45), 0.6, "center")], 0.0),
    "bunny":    ("animal_bunny", [((50, 0, 400, 220), (0, 1.12, -0.3), 1.2, "bottom"), ((236, 300, 276, 340), (0, -0.57, 0.47), 0.13, "center")], 0.0),
    "fox":      ("animal_fox", [((90, 15, 420, 175), (0, 1.12, -0.25), 1.6, "bottom"), ((180, 290, 330, 400), (0, -0.66, 0.45), 0.62, "center")], 0.0),
    "panda":    ("animal_panda", [((80, 80, 430, 440), (0, -0.05, 0.25), 2.25, "center")], 0.0),
    "crown":    ("crown_gold", [((30, 20, 480, 300), (0, 1.02, -0.3), 1.55, "bottom")], 0.7),
    "devil":    ("devil_horns", [((60, 50, 450, 210), (0, 1.0, -0.2), 1.45, "bottom")], 0.7),
    "glasses":  ("neon_glasses", [((40, 205, 470, 340), (0, -0.02, 0.3), 1.45, "center")], 0.7),
    "halo":     ("halo", [((90, 50, 420, 140), (0, 1.6, -0.35), 1.35, "center")], 0.7),
    "flower":   ("flower_crown", [((60, 50, 450, 215), (0, 0.98, -0.2), 1.6, "bottom")], 0.5),
    "fire":     ("fire_crown", [((80, 60, 430, 265), (0, 1.0, -0.25), 1.5, "bottom")], 0.7),
    "ice":      ("ice_crystal", [((90, 50, 420, 435), (0, 0.72, 0.25), 0.5, "center")], 0.7),
    "visor":    ("cyberpunk_visor", [((55, 170, 455, 345), (0, 0.0, 0.32), 1.75, "center")], 0.7),
    "hearts":   ("neon_hearts", [((75, 90, 410, 405), (0.95, 0.85, 0.0), 0.85, "center")], 0.6),
    "butterfly": ("butterfly", [((90, 135, 420, 365), (0.55, 1.05, -0.1), 0.8, "center")], 0.5),
    "sparkles": ("star_sparkles", [((35, 95, 450, 360), (0, 0.95, -0.1), 2.1, "center")], 0.6),
    "freckles": ("face_freckles", [((155, 265, 360, 330), (0, -0.3, 0.36), 0.78, "center")], 0.0),
    "blush":    ("face_blush", [((85, 275, 430, 370), (0, -0.4, 0.3), 1.3, "center")], 0.0),
    "liner":    ("face_makeup_liner", [((85, 225, 425, 278), (0, 0.04, 0.26), 1.08, "center")], 0.0),
    "lipgloss": ("face_lip_gloss", [((185, 360, 310, 425), (0, -0.95, 0.4), 0.52, "center")], 0.0),
    "hearts_cheeks": ("heart_cheeks", [((105, 225, 405, 320), (0, -0.4, 0.32), 1.2, "center")], 0.3),
}
NAMES = {"cat": ("Котик", "Животные"), "puppy": ("Щенок", "Животные"), "bunny": ("Зайка", "Животные"), "fox": ("Лисёнок", "Животные"),
         "panda": ("Панда", "Животные"), "crown": ("Корона", "3D-эффекты"), "devil": ("Рожки", "3D-эффекты"), "glasses": ("Неоновые очки", "3D-эффекты"),
         "halo": ("Нимб", "3D-эффекты"), "flower": ("Венок", "3D-эффекты"), "fire": ("Огненная корона", "3D-эффекты"), "ice": ("Ледяной кристалл", "3D-эффекты"),
         "visor": ("Кибер-визор", "3D-эффекты"), "hearts": ("Сердечки", "Украшения"), "butterfly": ("Бабочка", "Украшения"), "sparkles": ("Блёстки", "Украшения"),
         "freckles": ("Веснушки", "Макияж"), "blush": ("Румянец", "Макияж"), "liner": ("Стрелки", "Макияж"), "lipgloss": ("Блеск для губ", "Макияж"),
         "hearts_cheeks": ("Сердечки на щеках", "Макияж")}
FRAMES = {"film": ("frame_film", "Плёнка"), "flowers": ("frame_flower", "Цветочная рамка"), "location": ("sticker_location", "Геометка")}
BGS = {"bokeh": "Размытый свет", "neon_city": "Неоновый город", "sunset": "Закат", "forest": "Лес", "beach": "Пляж",
       "pink_hearts": "Розовые сердечки", "snow": "Зима", "galaxy": "Галактика"}

def raster(svg_path, w, h):
    png = cairosvg.svg2png(url=svg_path, output_width=w, output_height=h)
    return Image.open(io.BytesIO(png)).convert("RGBA")

def add_glow(im, strength):
    if strength <= 0: return im
    a = im.split()[3]
    glow = Image.new("RGBA", im.size, (0, 0, 0, 0))
    col = im.copy().filter(ImageFilter.GaussianBlur(10))
    al = col.split()[3].point(lambda v: int(min(255, v * 1.6 * strength)))
    col.putalpha(al)
    out = Image.alpha_composite(glow, col)
    return Image.alpha_composite(out, im)

os.makedirs(os.path.join(OUT, "face"), exist_ok=True)
os.makedirs(os.path.join(OUT, "thumbs"), exist_ok=True)
os.makedirs(os.path.join(OUT, "backgrounds"), exist_ok=True)
os.makedirs(os.path.join(OUT, "frames"), exist_ok=True)

def face_thumb(pieces_imgs, path):
    """Превью карточки: стилизованная голова + кусочки шаблона в своих местах."""
    T = 256; img = Image.new("RGBA", (T, T), (36, 30, 46, 255)); d = ImageDraw.Draw(img)
    sc = 62; cx = T / 2; cy = T * 0.56
    def P(x, y): return (cx + x * sc, cy - y * sc)
    d.ellipse([P(-0.82, 1.32), P(0.82, -1.3)], fill=(232, 196, 170, 255))
    for ex in (-0.3, 0.3):
        d.ellipse([P(ex - 0.12, 0.06), P(ex + 0.12, -0.06)], fill=(60, 45, 40, 255))
    d.line([P(0, -0.05), P(0.05, -0.5), P(-0.05, -0.55)], fill=(190, 150, 130, 255), width=3)
    d.arc([P(-0.25, -0.8), P(0.25, -1.05)], 20, 160, fill=(170, 80, 90, 255), width=4)
    for im, at, w, align in pieces_imgs:
        pw = max(1, int(w * sc)); ph = max(1, int(im.height * pw / im.width))
        r = im.resize((pw, ph), Image.LANCZOS)
        x, y = P(at[0], at[1])
        ox = int(x - pw / 2); oy = int(y - ph) if align == "bottom" else int(y - ph / 2)
        img.alpha_composite(r, (ox, oy))
    img.convert("RGB").save(path, quality=90)

catalog = {"faces": [], "frames": [], "backgrounds": []}
for tid, (svg, pieces, glow) in FACE.items():
    full = raster(os.path.join(SRC, "overlays", svg + ".svg"), S, S)
    out_pieces = []; thumbs = []
    for k, (rect, at, w, align) in enumerate(pieces):
        x0, y0, x1, y1 = [v * 2 for v in rect]
        crop = full.crop((x0, y0, x1, y1))
        bb = crop.split()[3].getbbox()
        if bb: crop = crop.crop(bb)
        pad = 24
        canvas = Image.new("RGBA", (crop.width + 2 * pad, crop.height + 2 * pad), (0, 0, 0, 0))
        canvas.alpha_composite(crop, (pad, pad))
        canvas = add_glow(canvas, glow)
        # ширина с учётом поля под свечение
        w_full = w * canvas.width / crop.width
        fn = f"face/{tid}_{k}.png"
        canvas.save(os.path.join(OUT, fn))
        out_pieces.append({"png": "effects/" + fn, "at": list(at), "w": round(w_full, 4), "align": align,
                           "aspect": round(canvas.height / canvas.width, 5)})
        thumbs.append((canvas, at, w_full, align))
    face_thumb(thumbs, os.path.join(OUT, "thumbs", tid + ".jpg"))
    name, cat = NAMES[tid]
    catalog["faces"].append({"id": tid, "title": name, "category": cat, "thumb": "effects/thumbs/" + tid + ".jpg", "pieces": out_pieces})

for fid, (svg, name) in FRAMES.items():
    im = raster(os.path.join(SRC, "overlays", svg + ".svg"), S, S)
    bb = im.split()[3].getbbox(); im = im.crop(bb)
    im.save(os.path.join(OUT, "frames", fid + ".png"))
    t = Image.new("RGB", (256, 256), (36, 30, 46)); r = im.copy(); r.thumbnail((220, 220)); t.paste(r, ((256 - r.width) // 2, (256 - r.height) // 2), r)
    t.save(os.path.join(OUT, "thumbs", "frame_" + fid + ".jpg"), quality=90)
    catalog["frames"].append({"id": fid, "title": name, "png": "effects/frames/" + fid + ".png", "thumb": "effects/thumbs/frame_" + fid + ".jpg",
                              "mode": "corner" if fid == "location" else "stretch"})

for bid, name in BGS.items():
    im = raster(os.path.join(SRC, "backgrounds", "bg_" + bid + ".svg"), 720, 1280).convert("RGB")
    im.save(os.path.join(OUT, "backgrounds", bid + ".jpg"), quality=88)
    t = im.resize((144, 256)); t.save(os.path.join(OUT, "thumbs", "bg_" + bid + ".jpg"), quality=88)
    catalog["backgrounds"].append({"id": bid, "title": name, "jpg": "effects/backgrounds/" + bid + ".jpg", "thumb": "effects/thumbs/bg_" + bid + ".jpg"})

with open(os.path.join(OUT, "templates.json"), "w", encoding="utf-8") as f:
    json.dump(catalog, f, ensure_ascii=False, indent=1)
print(len(catalog["faces"]), "face,", len(catalog["frames"]), "frames,", len(catalog["backgrounds"]), "backgrounds")
