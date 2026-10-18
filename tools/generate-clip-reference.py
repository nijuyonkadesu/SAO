#!/usr/bin/env python3
"""Generate local encoder fixtures from checksum-pinned public models, without downloading media.

Run in a development venv with numpy, Pillow, tokenizers and onnxruntime installed:
  python tools/generate-clip-reference.py --model-dir /path/to/clip-default
"""

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parent.parent
REVISION = "ViT-B-32__openai@a857c8de2c07bbcfa6646adfcf31b798845afa1e"
HASHES = {
    "visual/model.onnx": "33a3df41ceef21acdf371af00f6dd0456ec1f9eba24d03a7720f9c3734e40859",
    "textual/model.onnx": "b80cf0af751533a6712d92247f0ddc0c95208748bc59a1a27f33e67be6864e3b",
    "textual/tokenizer.json": "6d9109cc838977f3ca94a379eec36aecc7c807e1785cd729660ca2fc0171fb35",
}
TEXTS = ["happy person wearing a blue shirt", "😊", "  HELLO   world  ", "skill issue",
         "I'm happy!", "👩🏽‍💻", "a cat", "a video of a person", "蓝色衬衫",
         "<|startoftext|> hi", "one two " * 60]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "local-extension/src/androidTest/assets/clip-reference.json")
    args = parser.parse_args()
    for name, expected in HASHES.items():
        with (args.model_dir / name).open("rb") as file:
            if hashlib.file_digest(file, "sha256").hexdigest() != expected:
                raise ValueError(f"Wrong model artifact: {name}")
    tokenizer = Tokenizer.from_file(str(args.model_dir / "textual/tokenizer.json"))
    tokenizer.enable_padding(length=77, pad_id=49407, pad_token="<|endoftext|>")
    tokenizer.enable_truncation(max_length=77)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    text_model = ort.InferenceSession(str(args.model_dir / "textual/model.onnx"), options, providers=["CPUExecutionProvider"])
    cases = []
    for text in TEXTS:
        ids = tokenizer.encode(" ".join(text.split())).ids
        vector = text_model.run(None, {"text": np.array([ids], dtype=np.int32)})[0][0]
        cases.append({"text": text, "ids": ids, "vector": vector.tolist()})
    image = Image.open(ROOT / "local-extension/src/androidTest/assets/happy.png").convert("RGB")
    image = image.resize((224, 224), Image.Resampling.BICUBIC)
    mean = np.array([0.48145466, 0.4578275, 0.40821073], np.float32)
    std = np.array([0.26862954, 0.26130258, 0.27577711], np.float32)
    pixels = np.asarray(image, dtype=np.float32) / 255
    pixels *= 1 / std
    pixels -= mean / std
    visual = ort.InferenceSession(str(args.model_dir / "visual/model.onnx"), options, providers=["CPUExecutionProvider"])
    vector = visual.run(None, {"image": pixels.transpose(2, 0, 1)[None]})[0][0]
    args.output.write_text(json.dumps({"model": REVISION, "textual": cases, "happy": vector.tolist()}))


if __name__ == "__main__":
    main()
