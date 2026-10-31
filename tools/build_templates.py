#!/usr/bin/env python3
"""Сборка/проверка пакета эффектов MayskCamera.

Использование:
  python3 tools/build_templates.py --assets app/src/main/assets/effects

Проверяет templates.json, наличие файлов PNG/JPG, aspect, якоря.
Можно расширить генерацией PNG из SVG/исходников.
"""
from __future__ import annotations
import argparse, json, os, sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    Image = None

REQUIRED_ANCHORS = {"forehead", "eyes", "nose", "mouth", "cheeks", "center"}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--assets", default="app/src/main/assets/effects")
    ap.add_argument("--fix", action="store_true", help="пересчитать aspect из PNG")
    args = ap.parse_args()
    root = Path(args.assets)
    tpl = root / "templates.json"
    if not tpl.exists():
        print("FAIL: no templates.json at", tpl); sys.exit(1)
    data = json.loads(tpl.read_text(encoding="utf-8"))
    faces = data.get("faces") or []
    errors = []
    fixed = 0
    for f in faces:
        fid = f.get("id", "?")
        thumb = root.parent / f.get("thumb", "") if not str(f.get("thumb","")).startswith("effects/") else root.parent / f["thumb"]
        # thumbs path is relative to assets/
        tpath = root.parent / f["thumb"] if "thumb" in f else None
        if tpath and not tpath.exists():
            # try under effects/
            t2 = root / "thumbs" / f"{fid}.jpg"
            if not t2.exists():
                errors.append(f"{fid}: missing thumb {f.get('thumb')}")
        for i, p in enumerate(f.get("pieces") or []):
            rel = p.get("png", "")
            pp = root.parent / rel if rel.startswith("effects/") else root / "face" / os.path.basename(rel)
            if not pp.exists():
                # alternate
                pp = root / "face" / os.path.basename(rel)
            if not pp.exists():
                errors.append(f"{fid}[{i}]: missing png {rel}")
                continue
            if Image and args.fix:
                im = Image.open(pp)
                w, h = im.size
                new_a = round(h / float(w), 5)
                if abs(new_a - float(p.get("aspect", 1))) > 1e-4:
                    p["aspect"] = new_a
                    fixed += 1
            anc = p.get("anchor", "center")
            if anc not in REQUIRED_ANCHORS:
                errors.append(f"{fid}[{i}]: bad anchor {anc}")
            if "at" not in p or len(p["at"]) < 2:
                errors.append(f"{fid}[{i}]: bad at")
    if args.fix and fixed:
        tpl.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"updated aspect on {fixed} pieces")
    print(f"faces: {len(faces)}")
    if errors:
        print("ERRORS:")
        for e in errors[:40]:
            print(" ", e)
        if len(errors) > 40:
            print(f"  ... +{len(errors)-40} more")
        sys.exit(2)
    print("OK")

if __name__ == "__main__":
    import os
    main()
