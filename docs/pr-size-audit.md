# PR #24 size audit

The reported **33,053 additions / 852 deletions** compare master (`e62c7db`) with `5a23040`, before the carousel follow-up. They cover the entire overlay/source branch, rather than one home-screen change. GitHub reports 430 changed files; 373 have textual line counts and 57 are binary assets, including screenshots and test media.

| Area | Added lines | Purpose |
| --- | ---: | --- |
| Host production code | 6,560 | Floating service/window, search and chronology, shared carousel/playback, clipboard/export, source orchestration and settings |
| Host Vector integration | 1,825 | Modern libxposed hooks, per-app adapters, IPC and bridge lifecycle |
| Source SDK and source APK code | 3,714 | Extension contract, Immich client/provider/settings, Local Sticker indexing, OCR/labels and Context search |
| UI resources | 1,832 | Views layouts, strings, icons and theme resources across the host and extensions |
| Tests and debug fixtures | 10,154 | JVM and device checks, synthetic providers/media and cross-process test support; excluded from production APKs |
| Generated emoji keyword data | 1,871 | English shortcode/search index; one generated TSV, rather than handwritten application logic |
| Licenses and attribution | 3,027 | Upstream license/provenance files and notices packaged with the relevant APKs |
| Documentation | 2,780 | Setup, API/delivery research, design reports, behavior and test evidence |
| Development tools | 980 | Disposable chat-contract probes, fixture generators and recovery testing; excluded from production APKs |
| Build configuration and manifests | 310 | Module/dependency setup, shrinking, extension discovery and Android component declarations |
| **Total** | **33,053** | |

Application/extension code plus UI resources account for **13,931 lines (42.1%)**. Tests, tools, research, keyword data and legal text account for most of the remainder. The source-code subtotal is 12,099: host 8,385 and source modules 3,714. Local Sticker contributes 2,165 of those source-module lines; its ML runtime is a dependency and downloaded model weights are not represented by this line count.

The feature scope explains the production-code growth: this branch adds an overlay, media delivery into several unrelated apps, an extension protocol, and two independently installed providers. Playback, exporting, pagination and source switching are shared host implementations. Provider credentials and indexing stay outside the host.

**That does not justify making one PR this broad.** It combines several independently reviewable features. Tests improve confidence but do not make 10,154 test/fixture lines free to maintain. The 2,780 documentation lines include historical plans and overlapping investigations; retaining all of them in the main feature PR is a review cost. Some legal text is tracked both under `third_party` and in APK assets, with Material notices repeated across APK modules. Each distributed APK needs its notices; repeated repository copies could be consolidated through packaging. These are opportunities to reduce duplication, rather than evidence that every added line is unavoidable.

The better delivery would have been a series of PRs for the overlay/media core, chat bridges, source contract/Immich, Local Sticker, and home/aggregate browsing. This follow-up keeps its functional changes small and reuses the existing selector, carousel and folder-import paths.

Counts are reproducible with `git diff --numstat e62c7db185d891a4fdee98045000f342af1427d4 5a23040ca869d1ee31821911e29a9a75743a5bd0`. Each textual path belongs to one row: tests/debug support first, then documentation/legal/data/tools, bridge code, other production code, UI resources and remaining build files. Binary assets add no textual lines.
