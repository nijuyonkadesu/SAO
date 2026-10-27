#!/usr/bin/env python3
"""Refresh packaged notices from the locked encoder dependency graph (build-time only)."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
module = root / "gif-encoder"
metadata = json.loads(subprocess.check_output([
    "cargo", "metadata", "--locked", "--format-version", "1",
    "--manifest-path", str(module / "src/main/rust/Cargo.toml"),
]))
sections = ["SAO GIF encoder — third-party notices\n"]
for package in sorted(metadata["packages"], key=lambda item: item["name"]):
    if package["source"] is None:
        continue
    folder = Path(package["manifest_path"]).parent
    header = f'{package["name"]} {package["version"]} — {package["license"]}\n{package.get("repository") or ""}'
    if package["name"] == "gifski":
        texts = ["Copyright 2017 Kornel Lesiński\n" + (module / "GIFSKI-LICENSE").read_text()]
    elif package["name"] == "gif-dispose":
        # This crate declares MIT OR Apache-2.0 but omits license files from its
        # published archive. Its author's loop9 crate supplies the same MIT terms.
        loop9 = next(item for item in metadata["packages"] if item["name"] == "loop9")
        texts = [(Path(loop9["manifest_path"]).parent / "LICENSE").read_text()]
    else:
        # Choose MIT or CC0 where the crate expressly offers a dual license.
        choices = [folder / name for name in ("LICENSE-MIT", "LICENSE-CC0", "COPYRIGHT", "LICENSE")]
        chosen = next((path for path in choices if path.is_file()), None)
        if chosen is None:
            raise RuntimeError(f"Review license files for {header}")
        texts = [chosen.read_text()]
        third_party = folder / "LICENSE-THIRD-PARTY"
        if third_party.is_file():
            texts.append(third_party.read_text())
    sections.append(header + "\n\n" + "\n\n".join(texts))
output = module / "src/main/assets/licenses/gifski.txt"
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("\n\n".join(sections).rstrip() + "\n", encoding="utf-8")
