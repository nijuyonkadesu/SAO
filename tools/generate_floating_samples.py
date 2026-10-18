#!/usr/bin/env python3
"""Regenerate our original geometric test media (requires Pillow; never needed at runtime)."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

DEST = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/floating"
DEST.mkdir(parents=True, exist_ok=True)
SAMPLES = [
    ("happy", "Happy", "#F9CD63", ["happy", "joy", "laugh", "funny"], ["😂", "😄"]),
    ("love", "Love", "#F48EAE", ["love", "heart", "affection"], ["❤️", "🥰"]),
    ("sad", "Sad", "#90BEDF", ["sad", "cry", "tears"], ["😭", "😢"]),
    ("surprised", "Surprised", "#B5A0E5", ["surprise", "wow", "shock"], ["😮", "🤯"]),
    ("angry", "Angry", "#F29D82", ["angry", "annoyed", "rage"], ["😠", "😤"]),
    ("wink", "Wink", "#A9D7B4", ["wink", "playful", "yes", "okay"], ["😉", "👍"]),
]

def face(kind, color, offset=0):
    image = Image.new("RGBA", (512, 512))
    draw = ImageDraw.Draw(image)
    draw.ellipse((46, 46, 466, 466), fill="white")
    draw.ellipse((60, 60, 452, 452), fill=color, outline="#252333", width=10)
    if kind == "love":
        for x in (174, 338):
            draw.ellipse((x-38, 163, x, 203), fill="#B43254")
            draw.ellipse((x, 163, x+38, 203), fill="#B43254")
            draw.polygon([(x-38, 186), (x+38, 186), (x, 240)], fill="#B43254")
    elif kind == "angry":
        draw.line((142, 164, 204, 194), fill="#252333", width=13)
        draw.line((308, 194, 370, 164), fill="#252333", width=13)
        for x in (174, 338): draw.ellipse((x-13, 210, x+13, 244), fill="#252333")
    else:
        for index, x in enumerate((174, 338)):
            if kind == "wink" and index == 1:
                draw.arc((x-30, 187, x+30, 240), 180, 360, fill="#252333", width=12)
            else:
                draw.ellipse((x-14, 183+offset, x+14, 229+offset), fill="#252333")
    if kind == "surprised":
        draw.ellipse((220, 280, 292, 364), fill="#252333")
    elif kind in ("sad", "angry"):
        draw.arc((174, 295, 338, 410), 200, 340, fill="#252333", width=13)
        if kind == "sad":
            for x in (139, 371):
                draw.polygon([(x, 232), (x-15, 280), (x+15, 280)], fill="#3979CE")
                draw.ellipse((x-15, 265, x+15, 295), fill="#3979CE")
    else:
        draw.arc((153, 210, 359, 365+offset), 12, 168, fill="#252333", width=14)
    return image

catalog = []
for key, title, color, tags, emojis in SAMPLES:
    image = face(key, color)
    image.save(DEST / f"{key}.png", optimize=True)
    image.save(DEST / f"{key}.webp", quality=90)
    catalog.append(dict(id=key, title=title, asset=f"floating/{key}.png", mimeType="image/png", tags=tags, emojis=emojis))
face("happy", "#F9CD63").resize((96, 96), Image.Resampling.LANCZOS).save(DEST / "tray.png")
frames = [face("wink", "#A9D7B4", offset).convert("RGB").convert("P", palette=Image.Palette.ADAPTIVE)
          for offset in (0, 10, 0, -10)]
frames[0].save(DEST / "hello.gif", save_all=True, append_images=frames[1:], duration=180, loop=0, disposal=2)
catalog.append(dict(id="hello", title="Hello", asset="floating/hello.gif", mimeType="image/gif",
                    tags=["hello", "greeting", "animated", "wave"], emojis=["👋"], animated=True))
(DEST / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
print(f"Generated {len(catalog)} sample items in {DEST}")
