# Architecture review — October 2026

Two sequential Sol 6.1 xhigh reviews covered the PR’s production code (excluding tests), then all first-party production code in SAO-extensions. This follow-up fixes concrete ownership, lifecycle and failure-handling problems; tests were added afterward to reproduce those failures.

| Priority | Finding | Resolution |
|---|---|---|
| P1 | Source-scoped asset IDs collided in UI state and prepared export caches | Typed owner/asset identity; export keys also include content revision and complete conversion policy |
| P1 | Native clients were polled every 400 ms while idle | Picker-scoped pushed state, explicit one-shot selection reads, no polling job |
| P2 | A timed-out native dispatch could still send after SAO treated it as rejected | Queued work cancels atomically; running timeouts return UNKNOWN, retain grants/input and never auto-retry |
| P2 | Eviction or source removal could delete a preview during asynchronous decoding | Callback-scoped cache leases held through decode/share; deletion waits for the last reader |
| P2 | Extension descriptor IPC could block before request timeouts applied | Protocol compatibility checked from service manifest metadata before binding |
| P2 | Interrupted share preparation could discard the rest of a batch | Checkpoint only committed items; resume pending URIs against the validated destination |
| P2 | Coroutine dispatcher limits did not bound suspended destination RPCs | A semaphore covers each complete RPC lifetime |
| P2 | Setup recreation could stall during discovery/reconciliation | Explicit saved phases and restartable work |
| P2 | Connected-folder tasks assumed every provider required a network | Persist route network policy; schedule separate offline/online queues; migrate existing routes as online |
| P2 | Aggregation flattened capability loading/failures into empty declarations | Preserve typed capability states and healthy aggregate features |

SAO still owns media caching, browsing/session lifetimes, upload scheduling and delivery. Extensions own credentials, backend protocols and local indexing. The extension Binder wire protocol stays v1; the in-process Kotlin preview API and native bridge revision change together. Install updated builds before testing native injection.

## Validation

Host production and instrumentation APKs build; 51 JVM checks pass. Sixty distinct host device checks pass on a disposable Android 15 emulator using synthetic media, localhost servers and code-less target UID fixtures. One accessibility setup timeout passed on its isolated rerun; one optional external-probe check is skipped because the isolated probe is absent. Added checks cover cross-profile byte isolation, preview removal during a lease, typed focus restoration, partial aggregate capability failure, cancelled/uncertain dispatch and grant lifetime, import restoration and route migration. Host lint reports zero errors (existing warnings remain).

No real chat sends, Vector injection into installed chat clients, personal accounts or private servers were exercised. Extension-specific fixes and their validation are recorded in SAO-extensions/docs/architecture-review.md. No APKs or catalog updates are published by this review.
