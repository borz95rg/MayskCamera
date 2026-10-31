#!/usr/bin/env python3
"""JVM-независимая проверка templates.json + файлов (можно гонять в CI)."""
import json, os, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "effects"

def test_parse_and_files():
    data = json.loads((ROOT / "templates.json").read_text(encoding="utf-8"))
    faces = data["faces"]
    assert len(faces) >= 30, f"expected >=30 faces, got {len(faces)}"
    ids = set()
    for f in faces:
        assert f["id"] not in ids, f"duplicate id {f['id']}"
        ids.add(f["id"])
        assert f.get("title"), f"{f['id']} no title"
        assert f.get("category"), f"{f['id']} no category"
        assert f.get("pieces"), f"{f['id']} no pieces"
        for p in f["pieces"]:
            png = ROOT.parent / p["png"] if p["png"].startswith("effects/") else ROOT / "face" / Path(p["png"]).name
            if not png.exists():
                png = ROOT / "face" / Path(p["png"]).name
            assert png.exists(), f"missing {p['png']}"
            assert "at" in p and len(p["at"]) >= 2
            assert "w" in p and p["w"] > 0
            assert "aspect" in p and p["aspect"] > 0
            # kids optional
            if "atKids" in p:
                assert len(p["atKids"]) >= 2
    print(f"OK {len(faces)} faces, {len(ids)} unique ids")

if __name__ == "__main__":
    test_parse_and_files()
