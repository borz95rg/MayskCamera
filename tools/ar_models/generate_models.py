#!/usr/bin/env python3
"""
Генератор 3D-моделей AR-масок Maysk (GLB / glTF 2.0).

Модели строятся процедурно из примитивов (эллипсоиды, конусы, торы, цилиндры) с вершинными
цветами и нормалями. Это настоящие треугольные 3D-сетки, которые рендерятся в приложении
и следуют за поворотом головы.

Система координат модели («лицевые единицы»):
  * начало — середина между внешними уголками глаз (точки Face Mesh 33 и 263);
  * 1.0 = расстояние между внешними уголками глаз;
  * +X — вправо в кадре, +Y — вверх (к макушке), +Z — к камере (из лица наружу).
Ориентиры: кончик носа ≈ (0, -0.55, 0.45), подбородок ≈ (0, -1.3, 0), линия роста волос ≈ (0, 0.8, 0),
макушка ≈ (0, 1.35, -0.4), уши ≈ (±0.95, -0.1, -0.6).

Части с именем, оканчивающимся на "@mouthOpen", показываются, только когда открыт рот.

Запуск:  python3 generate_models.py <папка assets/ar_models>
"""
import json, math, struct, sys, os
import numpy as np

# ------------------------------------------------------------------ примитивы

def _rot_to(v_from, v_to):
    a = np.array(v_from, float); a /= np.linalg.norm(a)
    b = np.array(v_to, float); b /= np.linalg.norm(b)
    v = np.cross(a, b); c = float(np.dot(a, b))
    if np.linalg.norm(v) < 1e-9:
        return np.eye(3) if c > 0 else np.diag([1, -1, -1.0])
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1 / (1 + c))

def rot_xyz(rx=0, ry=0, rz=0):
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Z @ Y @ X

class Part:
    def __init__(self, name, color, alpha=1.0):
        self.name = name; self.color = color; self.alpha = alpha
        self.pos = []; self.nrm = []; self.idx = []; self.col = []

    def add(self, P, N, I, colors=None):
        base = len(self.pos)
        self.pos += [tuple(p) for p in P]; self.nrm += [tuple(n) for n in N]
        self.idx += [i + base for i in I]
        if colors is None: colors = [self.color] * len(P)
        self.col += [tuple(c) + (self.alpha,) for c in colors]
        return self

def ellipsoid(center, radii, R=np.eye(3), seg=18, rings=12, cut=None):
    """Эллипсоид; cut=(axis, min) — срезать часть (например, полусфера)."""
    P, N, I = [], [], []
    for i in range(rings + 1):
        th = math.pi * i / rings
        for j in range(seg + 1):
            ph = 2 * math.pi * j / seg
            u = np.array([math.sin(th) * math.cos(ph), math.cos(th), math.sin(th) * math.sin(ph)])
            p = u * np.array(radii)
            n = u / np.array(radii); n /= np.linalg.norm(n)
            P.append(R @ p + center); N.append(R @ n)
    for i in range(rings):
        for j in range(seg):
            a = i * (seg + 1) + j; b = a + seg + 1
            I += [a, a + 1, b, b, a + 1, b + 1]
    return P, N, I

def cone(base, tip, r_base, r_tip=0.0, seg=16, squash=1.0, up_hint=(0, 0, 1)):
    base = np.array(base, float); tip = np.array(tip, float)
    axis = tip - base; L = np.linalg.norm(axis); d = axis / L
    a = np.cross(d, up_hint);
    if np.linalg.norm(a) < 1e-6: a = np.cross(d, (1, 0, 0))
    a /= np.linalg.norm(a); b = np.cross(d, a)
    P, N, I = [], [], []
    slope = (r_base - r_tip) / L
    for k, (c, r) in enumerate(((base, r_base), (tip, r_tip))):
        for j in range(seg + 1):
            ph = 2 * math.pi * j / seg
            rad = a * math.cos(ph) + b * math.sin(ph) * squash
            P.append(c + rad * r)
            n = rad + d * slope; N.append(n / np.linalg.norm(n))
    for j in range(seg):
        I += [j, j + 1, seg + 1 + j, seg + 1 + j, j + 1, seg + 2 + j]
    # донышко
    cb = len(P); P.append(base); N.append(-d)
    for j in range(seg + 1):
        ph = 2 * math.pi * j / seg
        P.append(base + (a * math.cos(ph) + b * math.sin(ph) * squash) * r_base); N.append(-d)
    for j in range(seg):
        I += [cb, cb + 2 + j, cb + 1 + j]
    return P, N, I

def cylinder(p0, p1, r, seg=12):
    return cone(p0, p1, r, r, seg)

def torus(center, R_major, r_minor, R=np.eye(3), seg=28, sides=10, scale=(1, 1)):
    P, N, I = [], [], []
    for i in range(seg + 1):
        u = 2 * math.pi * i / seg
        cu, su = math.cos(u), math.sin(u)
        for j in range(sides + 1):
            v = 2 * math.pi * j / sides
            cv, sv = math.cos(v), math.sin(v)
            p = np.array([(R_major + r_minor * cv) * cu * scale[0], (R_major + r_minor * cv) * su * scale[1], r_minor * sv])
            n = np.array([cv * cu, cv * su, sv])
            P.append(R @ p + center); N.append(R @ n)
    for i in range(seg):
        for j in range(sides):
            a = i * (sides + 1) + j; b = a + sides + 1
            I += [a, b, a + 1, a + 1, b, b + 1]
    return P, N, I

def box(center, size, R=np.eye(3)):
    P, N, I = [], [], []
    hx, hy, hz = [s / 2 for s in size]
    faces = [((1, 0, 0), (0, 1, 0), (0, 0, 1)), ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
             ((0, 1, 0), (0, 0, 1), (1, 0, 0)), ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
             ((0, 0, 1), (1, 0, 0), (0, 1, 0)), ((0, 0, -1), (0, 1, 0), (1, 0, 0))]
    h = np.array([hx, hy, hz])
    for n, u, v in faces:
        n, u, v = map(np.array, (n, u, v))
        c = n * h
        base = len(P)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            P.append(R @ (c + u * h * su + v * h * sv) + center); N.append(R @ n)
        I += [base, base + 1, base + 2, base, base + 2, base + 3]
    return P, N, I

def ear(part_out, part_in, x, y, z, w, h, tilt, depth=0.08, roundness=1.0):
    """Ухо животного: плоский конус-лепесток + внутренняя часть другого цвета."""
    R = rot_xyz(0, 0, tilt)
    base = np.array([x, y, z]); tip = base + R @ np.array([0, h, 0])
    part_out.add(*cone(base, tip, w, 0.02 * roundness, squash=depth / w))
    tip_in = base + R @ np.array([0, h * 0.78, 0]) + np.array([0, 0, depth * 0.6])
    part_in.add(*cone(base + np.array([0, 0.05, depth * 0.6]), tip_in, w * 0.62, 0.01, squash=depth * 0.5 / w))

# ------------------------------------------------------------------ модели

C = lambda h: tuple(int(h[i:i + 2], 16) / 255 for i in (1, 3, 5))
NOSE = np.array([0, -0.55, 0.47])

def m_cat():
    fur = Part("ears", C("#9E9E9E")); inner = Part("ears_inner", C("#F8BBD0"))
    ear(fur, inner, -0.55, 1.08, -0.35, 0.32, 0.6, 18); ear(fur, inner, 0.55, 1.08, -0.35, 0.32, 0.6, -18)
    nose = Part("nose", C("#F06292")).add(*ellipsoid(NOSE + [0, 0, 0.02], (0.11, 0.07, 0.06)))
    wh = Part("whiskers", C("#FAFAFA"))
    for s in (-1, 1):
        for k, ang in enumerate((-10, 0, 10)):
            p0 = np.array([0.22 * s, -0.68 - 0.04 * k, 0.42]); d = rot_xyz(0, 0, ang * s) @ np.array([0.55 * s, 0, -0.1])
            wh.add(*cylinder(p0, p0 + d, 0.008, 6))
    return [fur, inner, nose, wh]

def m_dog():
    fur = Part("ears", C("#8D6E63"))
    for s in (-1, 1):
        R = rot_xyz(0, 0, -12 * s)
        fur.add(*ellipsoid(np.array([0.95 * s, 0.35, -0.35]), (0.22, 0.55, 0.1), R))
    nose = Part("nose", C("#212121")).add(*ellipsoid(NOSE + [0, 0.02, 0.04], (0.17, 0.11, 0.1)))
    shine = Part("nose_shine", C("#FFFFFF")).add(*ellipsoid(NOSE + [-0.05, 0.07, 0.13], (0.04, 0.02, 0.02)))
    tongue = Part("tongue@mouthOpen", C("#EC407A")).add(*ellipsoid(np.array([0, -1.2, 0.35]), (0.17, 0.26, 0.06), rot_xyz(15, 0, 0)))
    return [fur, nose, shine, tongue]

def m_bunny():
    fur = Part("ears", C("#FAFAFA")); inner = Part("ears_inner", C("#F8BBD0"))
    for s in (-1, 1):
        R = rot_xyz(-8, 0, -10 * s)
        fur.add(*ellipsoid(np.array([0.32 * s, 1.75, -0.35]), (0.17, 0.75, 0.08), R))
        inner.add(*ellipsoid(np.array([0.32 * s, 1.75, -0.35]) + R @ np.array([0, 0, 0.06]), (0.09, 0.6, 0.04), R))
    nose = Part("nose", C("#F48FB1")).add(*ellipsoid(NOSE + [0, 0, 0.03], (0.09, 0.06, 0.05)))
    teeth = Part("teeth", C("#FFFFFF"))
    for s in (-1, 1): teeth.add(*box(np.array([0.045 * s, -0.98, 0.43]), (0.08, 0.13, 0.03)))
    return [fur, inner, nose, teeth]

def m_fox():
    fur = Part("ears", C("#EF6C00")); inner = Part("ears_inner", C("#FFF3E0"))
    ear(fur, inner, -0.55, 1.08, -0.35, 0.34, 0.72, 24); ear(fur, inner, 0.55, 1.08, -0.35, 0.34, 0.72, -24)
    tips = Part("ear_tips", C("#3E2723"))
    for s in (-1, 1):
        R = rot_xyz(0, 0, -24 * s); b = np.array([0.55 * s, 1.08, -0.35]) + R @ np.array([0, 0.55, 0])
        tips.add(*cone(b, b + R @ np.array([0, 0.18, 0]), 0.09, 0.0, squash=0.3))
    nose = Part("nose", C("#212121")).add(*ellipsoid(NOSE + [0, 0, 0.03], (0.1, 0.07, 0.06)))
    return [fur, inner, tips, nose]

def m_panda():
    black = Part("ears", C("#1A1A1A"))
    for s in (-1, 1): black.add(*ellipsoid(np.array([0.72 * s, 1.12, -0.42]), (0.26, 0.26, 0.12)))
    patches = Part("eye_patches", C("#111111"), 0.92)
    for s in (-1, 1): patches.add(*ellipsoid(np.array([0.3 * s, -0.02, 0.22]), (0.24, 0.17, 0.05), rot_xyz(0, 0, 25 * s)))
    nose = Part("nose", C("#111111")).add(*ellipsoid(NOSE + [0, 0, 0.03], (0.12, 0.08, 0.06)))
    return [black, patches, nose]

def m_tiger():
    fur = Part("ears", C("#FB8C00")); inner = Part("ears_inner", C("#FFFFFF"))
    ear(fur, inner, -0.62, 1.05, -0.4, 0.3, 0.42, 20); ear(fur, inner, 0.62, 1.05, -0.4, 0.3, 0.42, -20)
    stripes = Part("stripes", C("#1B1B1B"))
    for k, (x, y, ang) in enumerate(((0, 0.75, 0), (-0.22, 0.62, 20), (0.22, 0.62, -20), (-0.62, 0.15, 70), (0.62, 0.15, -70))):
        stripes.add(*box(np.array([x, y, 0.2 if abs(x) < 0.5 else 0.02]), (0.05, 0.22, 0.03), rot_xyz(0, 0, ang)))
    nose = Part("nose", C("#4E342E")).add(*ellipsoid(NOSE + [0, 0, 0.03], (0.11, 0.07, 0.06)))
    return [fur, inner, stripes, nose]

def m_bear():
    fur = Part("ears", C("#6D4C41")); inner = Part("ears_inner", C("#A1887F"))
    for s in (-1, 1):
        fur.add(*ellipsoid(np.array([0.7 * s, 1.12, -0.42]), (0.25, 0.25, 0.12)))
        inner.add(*ellipsoid(np.array([0.7 * s, 1.1, -0.32]), (0.14, 0.14, 0.05)))
    muzzle = Part("muzzle", C("#BCAAA4"), 0.95).add(*ellipsoid(np.array([0, -0.72, 0.4]), (0.3, 0.2, 0.1)))
    nose = Part("nose", C("#3E2723")).add(*ellipsoid(NOSE + [0, 0.0, 0.08], (0.13, 0.08, 0.07)))
    return [fur, inner, muzzle, nose]

def m_wolf():
    fur = Part("ears", C("#757575")); inner = Part("ears_inner", C("#E0E0E0"))
    ear(fur, inner, -0.55, 1.1, -0.4, 0.3, 0.68, 14); ear(fur, inner, 0.55, 1.1, -0.4, 0.3, 0.68, -14)
    nose = Part("nose", C("#111111")).add(*ellipsoid(NOSE + [0, 0, 0.04], (0.12, 0.08, 0.07)))
    brows = Part("brows", C("#9E9E9E"))
    for s in (-1, 1): brows.add(*box(np.array([0.3 * s, 0.25, 0.2]), (0.32, 0.06, 0.04), rot_xyz(0, 0, -15 * s)))
    return [fur, inner, nose, brows]

def m_lion():
    mane = Part("mane", C("#E6A23C"))
    for k in range(30):
        a = 2 * math.pi * k / 30
        r_in = np.array([math.cos(a) * 1.05, math.sin(a) * 1.35 - 0.1, -0.45])
        r_out = r_in + np.array([math.cos(a) * 0.55, math.sin(a) * 0.5, -0.1])
        P, N, I = cone(r_in, r_out, 0.22, 0.02, seg=10)
        mane.add(P, N, I, [C("#E6A23C") if k % 2 else C("#C77A1E")] * len(P))
    ears = Part("ears", C("#D4943A"))
    for s in (-1, 1): ears.add(*ellipsoid(np.array([0.62 * s, 1.1, -0.4]), (0.18, 0.18, 0.08)))
    nose = Part("nose", C("#5D4037")).add(*ellipsoid(NOSE + [0, 0, 0.03], (0.12, 0.08, 0.06)))
    return [mane, ears, nose]

def m_deer():
    antler = Part("antlers", C("#8D6E63"))
    for s in (-1, 1):
        p0 = np.array([0.38 * s, 1.0, -0.35]); p1 = p0 + np.array([0.35 * s, 0.7, -0.05])
        antler.add(*cone(p0, p1, 0.05, 0.035, 10))
        for t, (dx, dy) in ((0.4, (-0.05, 0.35)), (0.75, (0.25, 0.25)), (1.0, (0.05, 0.3))):
            q = p0 + (p1 - p0) * t
            antler.add(*cone(q, q + np.array([dx * s, dy, 0.02]), 0.032, 0.012, 8))
    ears = Part("ears", C("#A1887F")); inner = Part("ears_inner", C("#F8BBD0"))
    for s in (-1, 1):
        R = rot_xyz(0, 0, -60 * s)
        ears.add(*ellipsoid(np.array([0.85 * s, 0.75, -0.35]), (0.12, 0.28, 0.06), R))
        inner.add(*ellipsoid(np.array([0.85 * s, 0.75, -0.3]), (0.06, 0.2, 0.03), R))
    nose = Part("nose", C("#3E2723")).add(*ellipsoid(NOSE + [0, 0, 0.03], (0.1, 0.07, 0.06)))
    freck = Part("freckles", C("#FFF8E1"))
    for s in (-1, 1):
        for (x, y) in ((0.42, -0.3), (0.52, -0.38), (0.38, -0.42), (0.5, -0.24)):
            freck.add(*ellipsoid(np.array([x * s, y, 0.18]), (0.025, 0.025, 0.015), seg=8, rings=6))
    return [antler, ears, inner, nose, freck]

def m_raccoon():
    fur = Part("ears", C("#616161")); inner = Part("ears_inner", C("#EEEEEE"))
    ear(fur, inner, -0.6, 1.07, -0.4, 0.26, 0.38, 22); ear(fur, inner, 0.6, 1.07, -0.4, 0.26, 0.38, -22)
    mask = Part("eye_mask", C("#212121"), 0.93)
    mask.add(*torus(np.array([0, -0.02, 0.2]), 0.48, 0.14, rot_xyz(0, 0, 0), seg=32, sides=8, scale=(1.05, 0.38)))
    nose = Part("nose", C("#111111")).add(*ellipsoid(NOSE + [0, 0, 0.03], (0.1, 0.07, 0.06)))
    return [fur, inner, mask, nose]

def m_owl():
    tufts = Part("tufts", C("#795548"))
    for s in (-1, 1):
        for k in range(3):
            b = np.array([(0.45 + 0.1 * k) * s, 0.75 + 0.03 * k, -0.15])
            tufts.add(*cone(b, b + np.array([0.25 * s, 0.45 - 0.08 * k, -0.05]), 0.08, 0.01, 8))
    rings = Part("eye_rings", C("#FFB300"))
    for s in (-1, 1): rings.add(*torus(np.array([0.32 * s, 0.0, 0.22]), 0.2, 0.045, seg=28, sides=8))
    disc = Part("face_disc", C("#D7CCC8"), 0.55)
    for s in (-1, 1): disc.add(*ellipsoid(np.array([0.32 * s, 0.0, 0.19]), (0.3, 0.3, 0.03)))
    beak = Part("beak", C("#FF8F00")).add(*cone(np.array([0, -0.35, 0.42]), np.array([0, -0.68, 0.55]), 0.09, 0.0, 10))
    return [tufts, disc, rings, beak]

def m_glasses():
    frame = Part("frame", C("#111111"))
    for s in (-1, 1):
        frame.add(*torus(np.array([0.3 * s, -0.02, 0.2]), 0.21, 0.025, seg=36, sides=8, scale=(1.15, 0.85)))
        frame.add(*cylinder(np.array([0.55 * s, 0.02, 0.18]), np.array([0.75 * s, 0.04, -0.75]), 0.02, 8))
    frame.add(*cylinder(np.array([-0.08, 0.05, 0.24]), np.array([0.08, 0.05, 0.24]), 0.02, 8))
    lens = Part("lenses", C("#26C6DA"), 0.38)
    for s in (-1, 1): lens.add(*ellipsoid(np.array([0.3 * s, -0.02, 0.2]), (0.24, 0.18, 0.012)))
    return [frame, lens]

def m_crown():
    gold = Part("gold", C("#FFC107"))
    gold.add(*cone(np.array([0, 1.12, -0.4]), np.array([0, 1.35, -0.4]), 0.62, 0.66, 36))
    gems = Part("gems", C("#E91E63"))
    cols = [C("#E91E63"), C("#2196F3"), C("#4CAF50")]
    for k in range(8):
        a = 2 * math.pi * k / 8 + math.pi / 2
        b = np.array([math.cos(a) * 0.62, 1.32, -0.4 + math.sin(a) * 0.62])
        gold.add(*cone(b, b + np.array([0, 0.32, 0]), 0.12, 0.0, 8))
        for e in (ellipsoid(b + np.array([0, 0.36, 0]), (0.05, 0.05, 0.05), seg=10, rings=8),
                  ellipsoid(np.array([math.cos(a) * 0.66, 1.22, -0.4 + math.sin(a) * 0.66]), (0.06, 0.05, 0.03), seg=10, rings=8)):
            gems.add(*e, [cols[k % 3]] * len(e[0]))
    return [gold, gems]

def m_fantasy():
    mask = Part("mask", C("#7B1FA2"), 0.96)
    mask.add(*torus(np.array([0, 0.0, 0.22]), 0.47, 0.17, seg=40, sides=8, scale=(1.08, 0.42)))
    trim = Part("trim", C("#FFD54F"))
    trim.add(*torus(np.array([0, 0.0, 0.26]), 0.47, 0.04, seg=48, sides=6, scale=(1.42, 0.78)))
    feathers = Part("feathers", C("#E040FB"))
    for k, ang in enumerate((-35, -15, 5)):
        b = np.array([0.62, 0.15, 0.1]); R = rot_xyz(0, 0, ang - 10)
        feathers.add(*ellipsoid(b + R @ np.array([0.12, 0.55, 0]), (0.07, 0.5, 0.025), R))
    gems = Part("gems", C("#00E5FF")).add(*ellipsoid(np.array([0, 0.18, 0.28]), (0.06, 0.06, 0.04)))
    return [mask, trim, feathers, gems]

MODELS = [
    ("cat", "Кошка", "Кошки", m_cat), ("dog", "Щенок", "Собаки", m_dog), ("bunny", "Зайка", "Животные", m_bunny),
    ("fox", "Лиса", "Дикие животные", m_fox), ("panda", "Панда", "Животные", m_panda), ("tiger", "Тигр", "Дикие животные", m_tiger),
    ("bear", "Мишка", "Дикие животные", m_bear), ("wolf", "Волк", "Дикие животные", m_wolf), ("lion", "Лев", "Дикие животные", m_lion),
    ("deer", "Олень", "Дикие животные", m_deer), ("raccoon", "Енот", "Дикие животные", m_raccoon), ("owl", "Сова", "Животные", m_owl),
    ("glasses", "Очки", "Очки", m_glasses), ("crown", "Корона", "Короны и головные уборы", m_crown),
    ("fantasy_mask", "Маскарад", "Фантазия", m_fantasy),
]

# ------------------------------------------------------------------ запись GLB

def write_glb(path, parts, title, category):
    bin_ = bytearray(); views = []; accessors = []; meshes_prims = []; materials = []; nodes = []

    def add_view(data, target):
        while len(bin_) % 4: bin_.append(0)
        off = len(bin_); bin_.extend(data)
        views.append({"buffer": 0, "byteOffset": off, "byteLength": len(data), "target": target})
        return len(views) - 1

    for pi, p in enumerate(parts):
        P = np.array(p.pos, np.float32); Nn = np.array(p.nrm, np.float32); Cc = np.array(p.col, np.float32)
        Nn /= np.maximum(np.linalg.norm(Nn, axis=1, keepdims=True), 1e-6)
        I = np.array(p.idx, np.uint16 if len(P) < 65535 else np.uint32)
        vp = add_view(P.tobytes(), 34962); vn = add_view(Nn.tobytes(), 34962); vc = add_view(Cc.tobytes(), 34962)
        vi = add_view(I.tobytes(), 34963)
        a0 = len(accessors)
        accessors += [
            {"bufferView": vp, "componentType": 5126, "count": len(P), "type": "VEC3", "min": P.min(0).tolist(), "max": P.max(0).tolist()},
            {"bufferView": vn, "componentType": 5126, "count": len(P), "type": "VEC3"},
            {"bufferView": vc, "componentType": 5126, "count": len(P), "type": "VEC4"},
            {"bufferView": vi, "componentType": 5123 if I.dtype == np.uint16 else 5125, "count": len(I), "type": "SCALAR"},
        ]
        materials.append({"name": p.name, "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1], "metallicFactor": 0.0, "roughnessFactor": 0.6},
                          "alphaMode": "BLEND" if p.alpha < 0.999 else "OPAQUE", "doubleSided": True})
        meshes_prims.append({"name": p.name, "primitives": [{"attributes": {"POSITION": a0, "NORMAL": a0 + 1, "COLOR_0": a0 + 2},
                                                              "indices": a0 + 3, "material": pi, "mode": 4}]})
        nodes.append({"name": p.name, "mesh": pi})
    gltf = {
        "asset": {"version": "2.0", "generator": "Maysk generate_models.py",
                  "extras": {"maysk": {"title": title, "category": category, "units": "outer-eye-distance", "anchor": "face"}}},
        "scene": 0, "scenes": [{"nodes": list(range(len(nodes)))}], "nodes": nodes, "meshes": meshes_prims,
        "materials": materials, "accessors": accessors, "bufferViews": views, "buffers": [{"byteLength": len(bin_)}],
    }
    js = json.dumps(gltf, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    while len(js) % 4: js += b" "
    while len(bin_) % 4: bin_.append(0)
    total = 12 + 8 + len(js) + 8 + len(bin_)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A)); f.write(js)
        f.write(struct.pack("<II", len(bin_), 0x004E4942)); f.write(bin_)

# ------------------------------------------------------------------ превью (простая растеризация)

def render_preview(parts, path, size=192, yaw=-22, pitch=8):
    from PIL import Image, ImageDraw
    R = rot_xyz(pitch, yaw, 0)
    img = Image.new("RGBA", (size, size), (34, 30, 42, 255)); dr = ImageDraw.Draw(img)
    scale = size / 4.2; cx = size / 2; cy = size * 0.58
    tris = []
    # голова-«манекен» для понимания посадки
    head = Part("head", (0.86, 0.72, 0.62)).add(*ellipsoid(np.array([0, 0.02, -0.58]), (0.8, 1.3, 0.75), seg=24, rings=16))
    nose = Part("nose_proxy", (0.86, 0.72, 0.62)).add(*cone(np.array([0, -0.2, 0.3]), np.array([0, -0.55, 0.45]), 0.1, 0.05, 8))
    light = np.array([0.4, 0.6, 0.7]); light /= np.linalg.norm(light)
    for p in [head, nose] + list(parts):
        P = (R @ np.array(p.pos).T).T; Nn = (R @ np.array(p.nrm).T).T
        for t in range(0, len(p.idx), 3):
            a, b, c = p.idx[t:t + 3]
            n = Nn[a] + Nn[b] + Nn[c]; n /= max(np.linalg.norm(n), 1e-6)
            if n[2] < -0.05 and p.alpha > 0.99 and p.name != "head": pass
            z = (P[a][2] + P[b][2] + P[c][2]) / 3
            col = np.array(p.col[a][:3]); sh = 0.35 + 0.65 * max(0.0, float(np.dot(n, light)))
            rgb = tuple(int(min(255, v * 255 * sh)) for v in col)
            pts = [(cx + P[i][0] * scale, cy - P[i][1] * scale) for i in (a, b, c)]
            tris.append((z, pts, rgb + (int(255 * p.alpha),)))
    tris.sort(key=lambda t: t[0])
    for z, pts, rgba in tris:
        dr.polygon(pts, fill=rgba)
    img.save(path)

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(os.path.join(out, "previews"), exist_ok=True)
    catalog = []
    for mid, title, cat, fn in MODELS:
        parts = fn()
        write_glb(os.path.join(out, mid + ".glb"), parts, title, cat)
        render_preview(parts, os.path.join(out, "previews", mid + ".png"))
        tri = sum(len(p.idx) // 3 for p in parts)
        catalog.append({"id": mid, "title": title, "category": cat, "file": "ar_models/" + mid + ".glb",
                        "preview": "ar_models/previews/" + mid + ".png", "triangles": tri})
        print(f"{mid:14s} {tri:6d} tris  {os.path.getsize(os.path.join(out, mid + '.glb'))//1024} KB")
    with open(os.path.join(out, "catalog.json"), "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=1)
