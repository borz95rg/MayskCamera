#!/usr/bin/env python3
"""
Иллюстрированный портрет для миниатюр эффектов (без фото реальных людей).

Геометрия совпадает с «лицевыми единицами» приложения: начало — середина между внешними
уголками глаз, 1.0 = расстояние между ними, +X вправо, +Y вверх. Поэтому кусочки шаблонов и
3D-модели ставятся на портрет по тем же координатам, что и на живое лицо в камере.
"""
import io
import cairosvg
from PIL import Image

N = 512                 # холст портрета
DY = 30.0             # портрет опущен, чтобы ушки и короны помещались в карточку
CX, CY = 256.0, 236.0 + DY   # середина между внешними уголками глаз (пиксели)
U = 116.0               # пикселей на лицевую единицу

def P(x, y):
    """Лицевые единицы → пиксели холста."""
    return CX + x * U, CY - y * U

def _svg(bg_top, bg_bottom):
    ex = 0.34 * U          # центры глаз
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{N}" height="{N}" viewBox="0 0 {N} {N}">
<defs>
  <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{bg_top}"/><stop offset="1" stop-color="{bg_bottom}"/></linearGradient>
  <radialGradient id="bokeh" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#ffffff" stop-opacity="0.35"/><stop offset="1" stop-color="#ffffff" stop-opacity="0"/></radialGradient>
  <radialGradient id="skin" cx="0.48" cy="0.42" r="0.62"><stop offset="0" stop-color="#f6d9c6"/><stop offset="0.7" stop-color="#ebbfa3"/><stop offset="1" stop-color="#d49d80"/></radialGradient>
  <linearGradient id="neck" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#c98e72"/><stop offset="0.35" stop-color="#e3b195"/></linearGradient>
  <linearGradient id="hair" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5a3424"/><stop offset="0.5" stop-color="#3b2017"/><stop offset="1" stop-color="#25140e"/></linearGradient>
  <linearGradient id="hairhi" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#8a5a3c" stop-opacity="0"/><stop offset="0.5" stop-color="#a87150" stop-opacity="0.55"/><stop offset="1" stop-color="#8a5a3c" stop-opacity="0"/></linearGradient>
  <radialGradient id="iris" cx="0.45" cy="0.4" r="0.6"><stop offset="0" stop-color="#9a6a3e"/><stop offset="0.7" stop-color="#5b3a1f"/><stop offset="1" stop-color="#2c1a0d"/></radialGradient>
  <linearGradient id="lip" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#c4566a"/><stop offset="1" stop-color="#a63b52"/></linearGradient>
  <radialGradient id="cheek" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#f08a8a" stop-opacity="0.35"/><stop offset="1" stop-color="#f08a8a" stop-opacity="0"/></radialGradient>
  <linearGradient id="top" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1d1a24"/><stop offset="1" stop-color="#0f0d14"/></linearGradient>
</defs>
<rect width="{N}" height="{N}" fill="url(#bg)"/>
<circle cx="70" cy="90" r="46" fill="url(#bokeh)"/><circle cx="450" cy="140" r="60" fill="url(#bokeh)"/>
<circle cx="420" cy="420" r="40" fill="url(#bokeh)"/><circle cx="60" cy="380" r="30" fill="url(#bokeh)"/>
<g transform="translate(0,{DY})">
<!-- волосы сзади -->
<path d="M256 66 C140 66 104 160 112 250 C122 330 98 420 86 512 L426 512 C414 420 390 330 394 250 C400 160 362 78 256 78 Z" fill="url(#hair)"/>
<!-- шея и плечи -->
<path d="M222 360 L222 430 C190 446 120 458 92 512 L420 512 C392 458 322 446 290 430 L290 360 Z" fill="url(#neck)"/>
<path d="M92 512 C112 470 170 452 214 444 C236 470 276 470 298 444 C342 452 400 470 420 512 Z" fill="url(#top)"/>
<!-- лицо -->
<path d="M256 96 C334 96 368 158 366 236 C364 302 338 360 296 388 C280 398 232 398 216 388 C174 360 148 302 146 236 C144 158 178 96 256 96 Z" fill="url(#skin)"/>
<path d="M146 236 C148 302 174 360 216 388 C200 360 176 310 170 240 Z" fill="#c98a6d" opacity="0.25"/>
<path d="M366 236 C364 302 338 360 296 388 C312 360 336 310 342 240 Z" fill="#c98a6d" opacity="0.25"/>
</g>
<!-- румянец -->
<ellipse cx="{CX - 0.48 * U:.1f}" cy="{CY + 0.42 * U:.1f}" rx="30" ry="18" fill="url(#cheek)"/>
<ellipse cx="{CX + 0.48 * U:.1f}" cy="{CY + 0.42 * U:.1f}" rx="30" ry="18" fill="url(#cheek)"/>
<!-- брови -->
<path d="M{CX - ex - 30:.1f} {CY - 26:.1f} Q{CX - ex - 4:.1f} {CY - 50:.1f} {CX - ex + 24:.1f} {CY - 38:.1f}" stroke="#4a2c1e" stroke-width="6" fill="none" stroke-linecap="round"/>
<path d="M{CX + ex + 30:.1f} {CY - 26:.1f} Q{CX + ex + 4:.1f} {CY - 50:.1f} {CX + ex - 24:.1f} {CY - 38:.1f}" stroke="#4a2c1e" stroke-width="6" fill="none" stroke-linecap="round"/>
<!-- глаза -->
{_eye(CX - ex, CY)}{_eye(CX + ex, CY, flip=True)}
<!-- нос -->
<path d="M{CX + 6:.1f} {CY + 24:.1f} C{CX + 10:.1f} {CY + 44:.1f} {CX + 14:.1f} {CY + 54:.1f} {CX + 10:.1f} {CY + 62:.1f}" stroke="#c48b70" stroke-width="2.5" fill="none" stroke-linecap="round" opacity="0.6"/>
<path d="M{CX - 16:.1f} {CY + 66:.1f} Q{CX:.1f} {CY + 74:.1f} {CX + 16:.1f} {CY + 66:.1f}" stroke="#b67b62" stroke-width="3" fill="none" stroke-linecap="round"/>
<ellipse cx="{CX + 4:.1f}" cy="{CY + 50:.1f}" rx="6" ry="9" fill="#ffffff" opacity="0.25"/>
<!-- губы -->
<path d="M{CX - 30:.1f} {CY + 108:.1f} C{CX - 18:.1f} {CY + 96:.1f} {CX - 6:.1f} {CY + 96:.1f} {CX:.1f} {CY + 101:.1f} C{CX + 6:.1f} {CY + 96:.1f} {CX + 18:.1f} {CY + 96:.1f} {CX + 30:.1f} {CY + 108:.1f} C{CX + 16:.1f} {CY + 126:.1f} {CX - 16:.1f} {CY + 126:.1f} {CX - 30:.1f} {CY + 108:.1f} Z" fill="url(#lip)"/>
<path d="M{CX - 30:.1f} {CY + 108:.1f} Q{CX:.1f} {CY + 112:.1f} {CX + 30:.1f} {CY + 108:.1f}" stroke="#7d2a3c" stroke-width="2" fill="none"/>
<ellipse cx="{CX + 6:.1f}" cy="{CY + 116:.1f}" rx="9" ry="3" fill="#ffffff" opacity="0.35"/>
<g transform="translate(0,{DY})">
<!-- чёлка и пряди спереди -->
<path d="M256 70 C170 70 128 136 136 224 C148 176 180 150 222 140 C250 134 270 124 286 108 C300 132 336 150 358 176 C366 190 370 204 372 220 C380 136 342 70 256 70 Z" fill="url(#hair)"/>
<path d="M142 200 C132 270 136 360 120 470 C140 430 156 360 160 300 C162 260 156 230 142 200 Z" fill="url(#hair)"/>
<path d="M370 200 C380 270 376 360 392 470 C372 430 356 360 352 300 C350 260 356 230 370 200 Z" fill="url(#hair)"/>
<path d="M190 96 C230 80 280 82 330 116" stroke="url(#hairhi)" stroke-width="10" fill="none" stroke-linecap="round"/>
</g>
</svg>"""

def _eye(x, y, flip=False):
    s = 1 if flip else -1   # наружу от носа
    lashes = "".join(
        f'<path d="M{x + s * dx:.1f} {y + dy:.1f} l{s * lx:.1f} {ly:.1f}" stroke="#1d100a" stroke-width="2.2" stroke-linecap="round"/>'
        for dx, dy, lx, ly in ((8, -13, 3, -6), (15, -10, 5, -5), (21, -5, 7, -3)))
    return f"""
<path d="M{x - 22:.1f} {y + 2:.1f} Q{x:.1f} {y - 19:.1f} {x + 22:.1f} {y + 1:.1f} Q{x:.1f} {y + 15:.1f} {x - 22:.1f} {y + 2:.1f} Z" fill="#fbf6f2"/>
<circle cx="{x:.1f}" cy="{y - 1:.1f}" r="11.5" fill="url(#iris)"/>
<circle cx="{x:.1f}" cy="{y - 1:.1f}" r="5" fill="#120a06"/>
<circle cx="{x + 4:.1f}" cy="{y - 5:.1f}" r="3" fill="#ffffff"/>
<circle cx="{x - 4:.1f}" cy="{y + 3:.1f}" r="1.4" fill="#ffffff" opacity="0.8"/>
<path d="M{x - 23:.1f} {y + 2:.1f} Q{x:.1f} {y - 21:.1f} {x + 23:.1f} {y + 0:.1f}" stroke="#24140d" stroke-width="3.5" fill="none" stroke-linecap="round"/>
{lashes}
<path d="M{x - 20:.1f} {y + 9:.1f} Q{x:.1f} {y + 17:.1f} {x + 20:.1f} {y + 8:.1f}" stroke="#b98670" stroke-width="1.2" fill="none" opacity="0.6"/>"""

def portrait(bg=("#0d3b3a", "#081a1e")):
    png = cairosvg.svg2png(bytestring=_svg(*bg).encode("utf-8"), output_width=N, output_height=N)
    return Image.open(io.BytesIO(png)).convert("RGBA")

def paste_piece(img, piece, at, w, align):
    """Кусочек шаблона по тем же правилам, что в приложении (центр или низ в точке at, ширина w)."""
    pw = max(1, int(w * U)); ph = max(1, int(piece.height * pw / piece.width))
    r = piece.resize((pw, ph), Image.LANCZOS)
    x, y = P(at[0], at[1])
    ox = int(x - pw / 2); oy = int(y - ph) if align == "bottom" else int(y - ph / 2)
    img.alpha_composite(r, (ox, oy)) if 0 <= ox and 0 <= oy and ox + pw <= img.width and oy + ph <= img.height else _paste_clip(img, r, ox, oy)

def _paste_clip(img, r, ox, oy):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    layer.paste(r, (ox, oy), r)
    img.alpha_composite(layer)

def card(img, out_path, size=320):
    """Квадратная миниатюра карточки: лицо и эффект крупно."""
    c = img.crop((16, 0, N - 16, N - 32)).resize((size, size), Image.LANCZOS)
    c.convert("RGB").save(out_path, quality=88)
