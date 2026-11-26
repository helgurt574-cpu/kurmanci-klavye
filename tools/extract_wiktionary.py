#!/usr/bin/env python3
"""
Builds tools/tr_ku_wiktionary.txt from kaikki.org Wiktionary extracts (CC BY-SA 4.0).

    python3 -I tools/extract_wiktionary.py /path/to/ku-extract.jsonl.gz /path/to/tr-extract.jsonl.gz

Downloads: https://kaikki.org/dictionary/downloads/ku/ku-extract.jsonl.gz
           https://kaikki.org/dictionary/downloads/tr/tr-extract.jsonl.gz

Three sources of Turkish → Kurmanji pairs:
  A. ku.wiktionary Kurmanji entries, their Turkish translations ("Werger" tables)
  B. ku.wiktionary Turkish entries, their Kurmanji glosses
  C. tr.wiktionary Turkish entries, their Kurdish translations (Latin script only, so no Sorani)
Translations confirmed by more sources rank higher; entries are written most-confirmed first, and the
app treats file order as priority for prefix completion.
"""
import collections
import gzip
import json
import pathlib
import re
import sys

OUT = pathlib.Path(__file__).resolve().parent / "tr_ku_wiktionary.txt"

TR_WORD = re.compile(r"^[a-zçğıöşüâîû]+(?: [a-zçğıöşüâîû]+){0,2}$")
KU_WORD = re.compile(r"^[a-zçêîşûA-ZÇÊÎŞÛ’']+(?: [a-zçêîşûA-ZÇÊÎŞÛ’']+){0,3}$")
PARENS = re.compile(r"\([^)]*\)|\[[^\]]*\]")
MAX_VALUES = 4
SOURCE_WEIGHT = {"A": 1.0, "B": 1.2, "C": 0.8}


def tr_lower(s: str) -> str:
    return s.replace("I", "ı").replace("İ", "i").lower()


def clean_tr(s: str):
    s = tr_lower(PARENS.sub("", s)).strip().strip(".,;:!?")
    s = re.sub(r"\s+", " ", s)
    return s if TR_WORD.match(s) and len(s) <= 30 else None


def clean_ku(s: str):
    s = PARENS.sub("", s).strip().strip(".,;:!?-").strip()
    s = re.sub(r"\s+", " ", s)
    if not KU_WORD.match(s) or len(s) > 35:
        return None
    # Keep proper-noun capitalization, lowercase everything else (Kurmanji uses plain i/I).
    return s if s[:1].isupper() and not s.isupper() and " " not in s else s.lower()


def read(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            yield json.loads(line)


def main(ku_path, tr_path):
    # pairs[tr][ku] -> {source: count}
    pairs = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))

    for d in read(ku_path):
        lc = d.get("lang_code")
        if lc == "ku":
            ku = clean_ku(d.get("word", ""))
            if not ku:
                continue
            for t in d.get("translations") or []:
                if t.get("lang_code") == "tr":
                    tr = clean_tr(t.get("word", ""))
                    if tr:
                        pairs[tr][ku]["A"] += 1
        elif lc == "tr":
            tr = clean_tr(d.get("word", ""))
            if not tr:
                continue
            for sense in d.get("senses") or []:
                if "form-of" in (sense.get("tags") or []):
                    continue
                for gloss in sense.get("glosses") or []:
                    # Glosses are comma-separated synonym lists; long items are definitions, not translations.
                    for part in re.split(r"[,;/]", gloss):
                        ku = clean_ku(part)
                        if ku:
                            pairs[tr][ku]["B"] += 1

    for d in read(tr_path):
        if d.get("lang_code") != "tr":
            continue
        tr = clean_tr(d.get("word", ""))
        if not tr:
            continue
        for t in d.get("translations") or []:
            if t.get("lang_code") in ("ku", "kmr"):
                ku = clean_ku(t.get("word", ""))
                if ku:
                    pairs[tr][ku]["C"] += 1

    entries = []
    for tr, kus in pairs.items():
        scored = []
        for ku, src in kus.items():
            if ku.lower() == tr:  # untranslated loan echoes ("radyo = radyo") add nothing
                continue
            score = sum(SOURCE_WEIGHT[s] for s in src) + 0.1 * sum(src.values())
            scored.append((score, ku))
        if not scored:
            continue
        scored.sort(key=lambda x: (-x[0], len(x[1]), x[1]))
        values = [ku for _, ku in scored[:MAX_VALUES]]
        entries.append((scored[0][0], tr, values))

    entries.sort(key=lambda e: (-e[0], e[1]))
    with OUT.open("w", encoding="utf-8") as f:
        f.write("# ÜRETİLMİŞ: tools/extract_wiktionary.py. Kaynak: Wiktionary (ku, tr) via kaikki.org, CC BY-SA 4.0.\n")
        f.write("# Elle düzeltmeleri tools/tr_ku.txt dosyasına yazın; o dosya her zaman önceliklidir.\n")
        for _, tr, values in entries:
            f.write(f"{tr} = {'|'.join(values)}\n")
    print(f"{len(entries)} kayıt → {OUT.name}")


if __name__ == "__main__":
    main(*sys.argv[1:3])
