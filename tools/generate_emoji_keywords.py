#!/usr/bin/env python3
"""Build the offline emoji index from pinned gemoji aliases and English CLDR keywords."""
import json
from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "gemoji": ("github/gemoji", "fadaeaf1f1a9be82b321316a6c5502e43138b2f6", "db/emoji.json"),
    "cldr": ("unicode-org/cldr-json", "91c267402229a59e3ef2774544f001bf959e8809", "cldr-json/cldr-annotations-full/annotations/en/annotations.json"),
}


def fetch(source, path=None):
    repo, commit, default_path = SOURCES[source]
    return urlopen(f"https://raw.githubusercontent.com/{repo}/{commit}/{path or default_path}", timeout=30).read()


def generate():
    emojis = json.loads(fetch("gemoji"))
    annotations = json.loads(fetch("cldr"))["annotations"]["annotations"]
    annotations = {key.replace("\ufe0f", ""): value for key, value in annotations.items()}
    lines = ["# emoji\tdescription\taliases (| separated)\tkeywords (| separated)"]
    for entry in emojis:
        annotation = annotations.get(entry["emoji"].replace("\ufe0f", ""), {})
        description = annotation.get("tts", [entry["description"]])[0]
        keywords = sorted(set(annotation.get("default", []) + entry["tags"] + [description, entry["description"]]))
        fields = [entry["emoji"], description, "|".join(entry["aliases"]), "|".join(keywords)]
        assert all("\n" not in field and "\t" not in field for field in fields)
        lines.append("\t".join(fields))
    output = ROOT / "app/src/main/assets/emoji/keywords-en.tsv"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    license_text = []
    for name, (repo, commit, _) in SOURCES.items():
        license_data = fetch(name, "LICENSE").decode()
        license_dir = ROOT / "third_party/emoji-keywords" / name
        license_dir.mkdir(parents=True, exist_ok=True)
        (license_dir / "LICENSE").write_text(license_data, encoding="utf-8")
        license_text.append(f"{repo}, commit {commit}\n\n{license_data}")
    (ROOT / "app/src/main/assets/licenses/emoji-keywords.txt").write_text("\n\n".join(license_text), encoding="utf-8")
    print(f"Generated {len(emojis)} emoji entries ({output.stat().st_size:,} bytes)")


if __name__ == "__main__":
    generate()
