#!/usr/bin/env python3
"""
Builds the offline dictionary shipped with the app, from Princeton WordNet 3.1.

Why this exists: asking a language model what a word means invites invention. A real
dictionary either has the word or it does not, and "not in the dictionary" is a correct
answer that a model will rarely give you on its own.

The WordNet data is pulled from Maven Central (the extjwnl packaging) rather than
princeton.edu, because it is a stable, versioned artifact.

Output: app/src/main/assets/dictionary.db.gz

Usage:
    python3 tools/build_dictionary.py

Requires network access on first run only; the downloaded jar is cached alongside.
"""

from __future__ import annotations

import gzip
import os
import shutil
import sqlite3
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

WORDNET_JAR_URL = (
    "https://repo.maven.apache.org/maven2/net/sf/extjwnl/"
    "extjwnl-data-wn31/1.2/extjwnl-data-wn31-1.2.jar"
)
DATA_PREFIX = "net/sf/extjwnl/data/wordnet/wn31/"

# WordNet part-of-speech file names mapped to the short tags stored in the database.
PARTS = {"noun": "n", "verb": "v", "adj": "adj", "adv": "adv"}

# Irregular inflections, so "ran" finds "run" and "children" finds "child".
EXCEPTION_FILES = {"noun.exc": "n", "verb.exc": "v", "adj.exc": "adj", "adv.exc": "adv"}

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "dictionary.db.gz"


def fetch_wordnet(workdir: Path) -> Path:
    jar = workdir / "wordnet.jar"
    if not jar.exists():
        print(f"downloading {WORDNET_JAR_URL}")
        urllib.request.urlopen(WORDNET_JAR_URL)  # Fail fast with a clear error.
        with urllib.request.urlopen(WORDNET_JAR_URL) as response, jar.open("wb") as out:
            shutil.copyfileobj(response, out)

    extracted = workdir / "wn"
    if not extracted.exists():
        with zipfile.ZipFile(jar) as archive:
            archive.extractall(extracted)
    return extracted / DATA_PREFIX


def read_synsets(data_dir: Path) -> dict[tuple[str, str], tuple[list[str], str]]:
    """(pos, offset) -> (words in the synset, gloss)."""
    synsets: dict[tuple[str, str], tuple[list[str], str]] = {}
    for name, tag in PARTS.items():
        path = data_dir / f"data.{name}"
        with path.open(encoding="latin-1") as handle:
            for line in handle:
                if line.startswith("  "):  # Licence header lines.
                    continue
                head, _, gloss = line.partition("|")
                fields = head.split()
                if len(fields) < 4:
                    continue
                offset = fields[0]
                word_count = int(fields[3], 16)
                words = [fields[4 + 2 * i].replace("_", " ") for i in range(word_count)]
                synsets[(tag, offset)] = (words, gloss.strip())
    return synsets


def read_senses(data_dir: Path, synsets) -> list[tuple[str, str, int, str, str]]:
    """
    Rows of (word, pos, rank, definition, synonyms).

    Driven by the index files because they list a word's synsets in WordNet's estimated
    frequency order — so "run" leads with the common sense, not an arbitrary one.
    """
    rows = []
    for name, tag in PARTS.items():
        path = data_dir / f"index.{name}"
        with path.open(encoding="latin-1") as handle:
            for line in handle:
                if line.startswith("  "):
                    continue
                fields = line.split()
                if len(fields) < 6:
                    continue
                lemma = fields[0].replace("_", " ")
                pointer_count = int(fields[3])
                # lemma pos synset_cnt p_cnt [ptrs...] sense_cnt tagsense_cnt offsets...
                offsets = fields[4 + pointer_count + 2:]
                for rank, offset in enumerate(offsets):
                    entry = synsets.get((tag, offset))
                    if not entry:
                        continue
                    words, gloss = entry
                    synonyms = [w for w in words if w.lower() != lemma.lower()]
                    rows.append((lemma.lower(), tag, rank, gloss, ", ".join(synonyms)))
    return rows


def read_exceptions(data_dir: Path) -> list[tuple[str, str, str]]:
    """Irregular forms: (inflected, base, pos).

    The part of speech matters: "ran" is a verb form, so the verb senses of "run" should
    lead, not the baseball noun.
    """
    pairs = []
    for filename, tag in EXCEPTION_FILES.items():
        path = data_dir / filename
        if not path.exists():
            continue
        with path.open(encoding="latin-1") as handle:
            for line in handle:
                parts = line.split()
                if len(parts) >= 2:
                    inflected = parts[0].replace("_", " ").lower()
                    for base in parts[1:]:
                        pairs.append((inflected, base.replace("_", " ").lower(), tag))
    return pairs


def build(rows, exceptions, destination: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        db_path = Path(tmp) / "dictionary.db"
        con = sqlite3.connect(db_path)
        con.executescript(
            """
            PRAGMA journal_mode = OFF;
            CREATE TABLE sense (
                word       TEXT NOT NULL,
                pos        TEXT NOT NULL,
                rank       INTEGER NOT NULL,
                definition TEXT NOT NULL,
                synonyms   TEXT NOT NULL
            );
            CREATE TABLE morph (
                form TEXT NOT NULL,
                base TEXT NOT NULL,
                pos  TEXT NOT NULL
            );
            """
        )
        con.executemany("INSERT INTO sense VALUES (?,?,?,?,?)", rows)
        con.executemany("INSERT INTO morph VALUES (?,?,?)", exceptions)
        con.executescript(
            """
            CREATE INDEX idx_sense_word ON sense(word);
            CREATE INDEX idx_morph_form ON morph(form);
            """
        )
        con.commit()
        con.execute("VACUUM")
        con.close()

        destination.parent.mkdir(parents=True, exist_ok=True)
        with db_path.open("rb") as raw, gzip.open(destination, "wb", compresslevel=9) as out:
            shutil.copyfileobj(raw, out)

        print(f"raw: {db_path.stat().st_size / 1e6:.1f} MB")
    print(f"packed: {destination.stat().st_size / 1e6:.1f} MB -> {destination}")


def main() -> int:
    with tempfile.TemporaryDirectory() as workdir:
        cache = Path(os.environ.get("WORDNET_CACHE", workdir))
        cache.mkdir(parents=True, exist_ok=True)
        data_dir = fetch_wordnet(cache)

        synsets = read_synsets(data_dir)
        rows = read_senses(data_dir, synsets)
        exceptions = read_exceptions(data_dir)

        words = len({r[0] for r in rows})
        print(f"synsets {len(synsets):,}  senses {len(rows):,}  words {words:,}  "
              f"irregular forms {len(exceptions):,}")

        build(rows, exceptions, OUTPUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
