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
    parser.add_argument("--output", type=Path)
    parser.add_argument("--quantized", action="store_true", help="Use the pinned lightweight ONNX encoder pair")
    args = parser.parse_args()
    if args.output is None:
        args.output = ROOT / "local-extension/src/androidTest/assets" / ("clip-8bit-reference.json" if args.quantized else "clip-reference.json")
    if args.quantized:
        generate_quantized(args)
        return
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


def generate_quantized(args):
    hashes = {
        "onnx/vision_model_uint8.onnx": "b95448754a6ae56964ec80c570c80bb9863787ee85f51b664abed8c0e5f22a7a",
        "onnx/text_model_uint8.onnx": "cbe64fd9eaa6324d49006ce99c104107fdf6b26d25bebff96ecbb5d8a24c5e54",
        "tokenizer.json": "f7f3b7af117d467b58374797691a6438d3e6b9e9cef800dfd5dced7f697a90cd",
    }
    for name, expected in hashes.items():
        with (args.model_dir / name).open("rb") as file:
            if hashlib.file_digest(file, "sha256").hexdigest() != expected:
                raise ValueError(f"Wrong model artifact: {name}")
    tokenizer = Tokenizer.from_file(str(args.model_dir / "tokenizer.json"))
    tokenizer.enable_padding(length=77, pad_id=49407, pad_token="<|endoftext|>")
    tokenizer.enable_truncation(max_length=77)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    text_model = ort.InferenceSession(str(args.model_dir / "onnx/text_model_uint8.onnx"), options, providers=["CPUExecutionProvider"])
    visual = ort.InferenceSession(str(args.model_dir / "onnx/vision_model_uint8.onnx"), options, providers=["CPUExecutionProvider"])
    cases = []
    for text in TEXTS:
        ids = tokenizer.encode(" ".join(text.split())).ids
        vector = text_model.run(None, {"input_ids": np.array([ids], dtype=np.int64)})[0][0]
        cases.append({"text": text, "ids": ids, "vector": vector.tolist()})
    images = {}
    pixel_hashes = {}
    for name in ("happy", "gradient"):
        if name == "happy":
            image = Image.open(ROOT / "local-extension/src/androidTest/assets/happy.png").convert("RGB")
        else:
            y, x = np.indices((213, 381))
            image = Image.fromarray(np.stack([(x * 7 + y * 3) % 256, (x + y * 11) % 256, (x * 5 + y) % 256], axis=-1).astype(np.uint8))
        width, height = image.size
        scaled_width = 224 if width < height else int(width / height * 224)
        scaled_height = int(height / width * 224) if width < height else 224
        image = image.resize((scaled_width, scaled_height), Image.Resampling.BICUBIC)
        left, top = (scaled_width - 224) // 2, (scaled_height - 224) // 2
        image = image.crop((left, top, left + 224, top + 224))
        pixels = np.asarray(image, dtype=np.float32) / 255
        pixels *= 1 / np.array([0.26862954, 0.26130258, 0.27577711], np.float32)
        pixels -= np.array([0.48145466, 0.4578275, 0.40821073], np.float32) / np.array([0.26862954, 0.26130258, 0.27577711], np.float32)
        tensor = pixels.transpose(2, 0, 1)[None]
        pixel_hashes[name] = hashlib.sha256(tensor.astype("<f4").tobytes()).hexdigest()
        images[name] = visual.run(None, {"pixel_values": tensor})[0][0].tolist()
    args.output.write_text(json.dumps({"model": "ViT-B-32__openai-uint8@d15189d7028b43f1d3e65039190477f6af591c2a", "textual": cases, "images": images, "pixel_sha256": pixel_hashes}))


if __name__ == "__main__":
    main()
