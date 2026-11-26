#!/usr/bin/env python3
"""
Merges tools/tr_ku.txt (hand-curated) and tools/tr_ku_wiktionary.txt (generated) into
app/src/main/assets/tr_ku.tsv. Both use "türkçe = kurmancî1|kurmancî2". Hand-curated entries come first
and their translations stay first; file order is the app's priority for prefix completion.
"""
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parent.parent
sources = [root / "tools" / "tr_ku.txt", root / "tools" / "tr_ku_wiktionary.txt"]
dst = root / "app" / "src" / "main" / "assets" / "tr_ku.tsv"

lines = [
    "# Türkçe → Kurmancî sözlüğü. Biçim: türkçe<TAB>kurmancî1|kurmancî2 (ilk karşılık en iyisi).",
    "# ÜRETİLMİŞ DOSYA: tools/tr_ku.txt dosyasını düzenleyip tools/build_dictionary.py çalıştırın.",
    "# Kayıtların çoğu Wiktionary'den (CC BY-SA 4.0), kaikki.org üzerinden.",
]
HEADER = len(lines)
merged = {}  # insertion order = priority
errors = 0
for src in sources:
    if not src.exists():
        continue
    for n, raw in enumerate(src.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.count("=") != 1:
            print(f"{src.name}:{n}: tam bir '=' bekleniyordu: {raw}", file=sys.stderr)
            errors += 1
            continue
        tr, ku = (p.strip() for p in line.split("="))
        values = [v.strip() for v in ku.split("|") if v.strip()]
        if not tr or not values:
            print(f"{src.name}:{n}: boş taraf: {raw}", file=sys.stderr)
            errors += 1
            continue
        key = " ".join(tr.replace("I", "ı").replace("İ", "i").lower().split())
        existing = merged.setdefault(key, [])
        existing += [v for v in values if v.lower() not in {e.lower() for e in existing}]

for key, values in merged.items():
    lines.append(f"{key}\t{'|'.join(values[:6])}")

if errors:
    sys.exit(1)
dst.parent.mkdir(parents=True, exist_ok=True)
dst.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"{len(lines) - HEADER} kayıt → {dst.relative_to(root)}")
