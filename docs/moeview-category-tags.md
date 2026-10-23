# Moeview default category tags

Reviewed on 2026-10-19. Defaults belong to the source APK and vary by board. **All** remains the initial category. Existing SAO custom categories are preserved; **Settings → Home categories → Reset to source defaults** adopts the new presets.

The waifu.pics reaction names are concepts, not portable booru tags. Yande.re retires many expression/action tags: its active-tag summary maps `blush` and `smile` to `deletethistag`. Other familiar names identify unrelated artists, circles or characters: Yande.re's `ponytail` is a circle, `hug` an artist and `happy` a character. These are excluded. Konachan also has many little-used tags with no safe results, and `smile` is not a general tag there.

Only reviewed, useful counterparts become defaults. Shared counterparts appear once: Konachan's **Hug** covers the broader cuddle/glomp request, and **Cat smile** is the narrower available smile/happy expression. Approximate matches use honest labels: **Howling**, **Head bump**, **Death** and **Wince**. Waifu's `1girl` is a broad single-girl category, not a subjective attractiveness filter. Shinobu refers to **Oshino Shinobu**.

| Requested concept | Yande.re | Konachan | Gelbooru | Danbooru* |
| --- | --- | --- | --- | --- |
| Seifuku | `seifuku` | `school_uniform` | `school_uniform` | `school_uniform` |
| Blushing / blush | — | `blush` | `blush` | `blush` |
| Pony tail | — | `ponytail` | `ponytail` | `ponytail` |
| Waifu | — | — | `1girl` | `1girl` |
| Neko | `nekomimi` | `catgirl` | `cat_ears` | `cat_ears` |
| Shinobu | `oshino_shinobu` | `oshino_shinobu` | `oshino_shinobu` | `oshino_shinobu` |
| Megumin | `megumin` | `megumin` | `megumin` | `megumin` |
| Bully | — | — | `bullying` | `bullying` |
| Cuddle | — | `hug` (broader) | `cuddling` | `cuddling` |
| Cry | — | `crying` | `crying` | `crying` |
| Hug | — | `hug` | `hug` | `hug` |
| Awoo | — | — | `howling` (closest) | — |
| Kiss | — | `kiss` | `kiss` | `kiss` |
| Lick | — | — | `licking` | `licking` |
| Pat | — | — | `headpat` | `headpat` |
| Smug | — | — | `smug` | `smug` |
| Bonk | — | — | `bonk` | `head_bump` (closest) |
| Yeet | — | — | `throwing` | `throwing` |
| Smile | — | `cat_smile` (subset) | `smile` | `smile` |
| Wave | — | — | `waving` | `waving` |
| High five | — | — | `high_five` | — |
| Handhold | — | — | `holding_hands` | `holding_hands` |
| Nom | — | — | `eating` | `eating` |
| Bite | — | — | `biting` | `biting` |
| Glomp | — | `hug` (broader) | `glomp` | `glomp` |
| Slap | — | — | `slapping` | `slapping` |
| Kill | — | — | `murder` | `death` (closest) |
| Kick | — | `kick` | `kicking` | `kicking` |
| Happy | — | `cat_smile` (subset) | `happy` | `happy` |
| Wink | — | `wink` | `one_eye_closed` | `one_eye_closed` |
| Poke | — | — | `poking` | `poking` |
| Dance | — | — | `dancing` | `dancing` |
| Cringe | — | — | `wince` (closest) | `wince` (closest) |

`—` means no suitable counterpart was verified for these defaults. It does not prove that the board contains no matching pictures. Duplicate queries become one chip. Counts including All: Yande.re **5**, Konachan **13**, Gelbooru **34**, Danbooru **32**. Rating and profile base-tag restrictions continue to apply, so a constrained profile may still return no results.

## Evidence and limits

- Yande.re: [active tags and aliases](https://yande.re/tag/summary.json), exact `/tag.json` metadata and [the ponytail circle](https://yande.re/wiki/show?title=ponytail). Each chosen tag returned a post with that exact tag under `rating:s`.
- Konachan: [API documentation](https://konachan.com/help/api), active-tag summary and exact tag metadata through the same [Moeview route used by the extension](https://moeview.app/edge/konachan/tag.json?name=blush&limit=5). Each chosen tag returned an exact-tag safe result. Merely having a positive global count was insufficient: e.g. `holding_hands`, `smug`, `happy` and `waving` had no result through the default safe route.
- Gelbooru: [documented batch tag lookup](https://gelbooru.com/index.php?page=wiki&s=view&id=18780), through the existing Moeview route. Every chosen tag had a positive general/character count and returned an exact-tag `rating:general` result.
- **Danbooru*: live verification is blocked by Cloudflare HTTP 403.** Names were cross-checked against Danbooru's [published tag vocabulary](https://github.com/danbooru/autotagger/blob/55d343cd3d79ecb6e1c14b854cb02b6038013bbe/data/tags.json) and the [WD v3 vocabulary](https://huggingface.co/SmilingWolf/wd-swinv2-tagger-v3/blob/main/selected_tags.csv), whose [model card](https://huggingface.co/SmilingWolf/wd-swinv2-tagger-v3) documents Danbooru data through 2024-02-28 and a 600-image minimum. This establishes vocabulary evidence, not current live counts or safe-result availability. Unverified rare counterparts such as Awoo and High five are omitted.

The probes requested public tag metadata and at most one post record per query; they downloaded no images and accessed no personal library. Small reviewed metadata snapshots live in the extension's test assets. Preset discovery itself performs **no network requests**. Users can add other tags through SAO's existing editor and board-specific suggestions.
