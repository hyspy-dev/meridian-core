# meridian-core — roadmap

## The goal

**The goal is not any single module.** The goal is a complete, honest, in-proxy
mirror of Hytale's game state and mechanics — a model faithful enough that
**anyone can build a module against the neutral API without ever touching raw
packets or the protocol.**

Modules — xray, camera-tweaks, farmer, world-downloader, replay, printer, … —
are *demonstrations and consumers* of that model. They prove the model is good
enough; they are not the point. A module is allowed to be thin: all the real
work lives in core.

**Honest implementation** means reproducing the real server/client behaviour,
not shortcuts. The proxy sits on the pipe — it sees exactly what the client
sees — so any state the client can reconstruct, core should reconstruct too, in
full. Where the genuine mechanic is a simulation (interaction chains, chunk
palettes), core reproduces the simulation rather than guessing.

## Layering

- **Layer 1 — meridian-core** (this repo): observes raw traffic, reproduces game
  state and mechanics, publishes neutral services. Absorbs all protocol churn.
- **Layer 2 — modules**: depend only on `meridian-core-api` + `meridian-api`.
  Protocol-neutral; a Hytale update cannot break them.
- **Layer 1 siblings** — orthogonal observers that write to disk and need no
  state model (recorder / world archive). They sit on raw packets directly.

See [`interaction-chains.md`](interaction-chains.md) for the interaction-system
analysis, [`interaction-vm-status.md`](interaction-vm-status.md) for the VM /
forging status, and the proxy's
[`architecture.md`](../../meridian-proxy/docs/architecture.md).

## Status

| Subsystem | State | Service |
|-----------|-------|---------|
| WorldState — block-type catalog | ✅ done | `WorldState` |
| EntityTracker — entity positions + local player pose | ✅ done | `EntityTracker` |
| CameraControl — camera packet forging | ✅ done | `CameraControl` |
| InteractionRegistry — interaction catalog (pkt 66/67) | ✅ done | internal |
| InventoryTracker — hotbar/utility/tools + held items | ✅ done | internal |
| ItemRegistry — item assets: roots + `interactionVars` | ✅ done | internal |
| ChunkTracker — block ids at any position | ✅ done, verified | internal |
| Interaction VM — flatten + simulate + tick-simulate (60 Hz) | ✅ done, validated | internal |
| InteractionControl — interaction forging, chainId NAT, forge queue | ✅ done | `InteractionControl` |
| World / Block / Player — building-block API | ✅ done | `World` |

The interaction track is validated against real captures (`VM check … MATCH`,
`VM-tick check … MATCH`) for harvest / water / plant. Forges are serialized
through a queue and exposed asynchronously (`CompletableFuture<Void>`).
Full detail: [`interaction-vm-status.md`](interaction-vm-status.md).

## What core exposes today

`World` is the position-addressed facade — building blocks, not features:

- `world.blockAt(x,y,z)` → `Block` (`type()`, `isAir()`, `use()` / `plant()` /
  `water()` — each returns `CompletableFuture<Void>`).
- `world.player()` → `Player` (`position()`, `heldItem()`, `hotbarSlotOf()`,
  `selectHotbarSlot()`).

A Layer-2 module writes its own scan / decision logic and acts through these
objects — core only removes the packet plumbing. Encapsulated `*Nearby(radius)`
helpers were deliberately removed: that orchestration belongs in the module.

## Roadmap

### ⬜ meridian-recorder (Layer-1 sibling) — session archive

A raw-packet recorder: tee every framed packet (both directions) with a
monotonic timestamp to a `.mrec` file, plus a header (session info, protocol
version, handshake). An orthogonal observer — needs no state model, sits on raw
packets via `meridian-api` + `meridian-protocol`, like a world archiver.

This is the **shared substrate** for the two flagship modules below — both are
"write the session to disk" of different flavours.

### ⬜ replay-mode — re-watch a recorded session

A proxy **launch mode** (`--replay <file>`): the backend is a recorded stream
instead of a live QUIC server. The proxy re-emits the recorded S→C to a real
Hytale client at the original cadence; a core/module layer suppresses all C→S
and drives a free camera via `CameraControl`. The client renders; the viewer
flies through a frozen, replaying world.

**Why it leads.** This is the feature that makes Meridian spread — content
creators promote replay tooling for free. It is mostly *composition* of pieces
that already exist: the recorder writes the archive, `CameraControl` already
forges the free camera, the proxy already has pluggable launch modes
(launcher / standalone).

**Honest challenges.**
- **Auth on playback** — the client must complete a handshake against the
  proxy-as-fake-server; the recording header carries the recorded handshake, or
  playback forges a minimal valid one.
- **C→S during playback** — fully suppressed; the recorded world does not react.
  The camera is driven independently.
- **Timeline scrubbing** — forward playback is trivial (re-feed). Seeking
  backward needs keyframes or replay-from-start. v1: forward + restart-to-seek;
  keyframes later.

### ⬜ meridian-world-downloader (Layer-1 sibling)

Geometry is doable today: `ChunkTracker` decodes sections, `WorldState` names
the types. Two real prerequisites:

1. **A read API on `ChunkTracker`** — currently only point `blockIdAt`; a dump
   needs enumeration of loaded sections + whole-section reads (a building block,
   core-side).
2. **Persist before eviction** — `ChunkTracker` drops sections on `UnloadChunk`;
   the downloader must write to disk as `SetChunk` arrives, not snapshot at the
   end.

A faithful Hytale save is a separate, larger track: it needs the **disk** format
(≠ the network palette) plus capture of block entities / biomes / metadata.
Until then — dump to an own format + a viewer / re-server.

### ⬜ meridian-farmer (Layer 2)

Autonomous farming: scan crops via `World.blockAt`, forge harvest / plant /
water via `InteractionControl`. All logic in the module; core hands out
`World` / `Block` / `Player`. `meridian-interaction-test` already demonstrates
the pattern (`useNearby` / `waterNearby` / `plantNearby`).

### ⬜ meridian-printer (Layer 2)

Read a structure from the world (`ChunkTracker`) → replay it block by block
(`InteractionControl`). Copy / schematic / build automation.

### ⬜ combat-assist + `DamageEntityInteraction` flattener port

The flattener does not port `DamageEntityInteraction` (directional / targeted
damage branches) — it is currently flattened as a leaf, so any chain containing
it produces a wrong operation sequence. Porting it unlocks combat interactions
→ a combat-assist module (auto-attack, reach, hit prediction) on
`InteractionControl` + `EntityTracker`.

### ⬜ VM honest-port polish

`BlockConditionInteraction` matchers, `SimpleBlockInteraction` air / chunk
checks, `ChargingInteraction` charge branches — currently catch-all `Finished`
(matches every observed path, but not the full node logic).

### ⬜ Future trackers — consumer-driven

Added when a real consumer needs them, never up front: player stats / effects,
cooldowns, full inventory metadata (durability), entity components beyond
transforms, chat, … Each is the same pattern: observe traffic → neutral service.

## Modules — consumers, not the goal

| Module | Layer | State | Consumes |
|--------|-------|-------|----------|
| meridian-xray | 2 | ✅ | WorldState |
| meridian-camera-tweaks | 2 | ✅ | CameraControl, EntityTracker |
| meridian-packetdump | — | ✅ | diagnostic (raw) |
| meridian-interaction-test | 2 | ✅ | InteractionControl / World — demonstrates forging |
| meridian-recorder | 1 sibling | ⬜ | raw packets — session archive |
| replay-mode | launch mode + core | ⬜ | recorder archive, CameraControl |
| meridian-world-downloader | 1 sibling | ⬜ | ChunkTracker read API (+ asset capture) |
| meridian-farmer | 2 | ⬜ | World / Block / Player, InteractionControl |
| meridian-printer | 2 | ⬜ | ChunkTracker, InteractionControl |
| combat-assist | 2 | ⬜ | InteractionControl + `DamageEntityInteraction` port, EntityTracker |

Each module stays thin. If a module needs logic that other modules could reuse,
that logic belongs in core.

## Build order

1. ✅ WorldState · EntityTracker · CameraControl
2. ✅ InteractionRegistry · InventoryTracker · ItemRegistry
3. ✅ ChunkTracker
4. ✅ Interaction VM (flatten + simulate + tick-simulate) → InteractionControl
   → World / Block / Player → interaction-test module
5. ⬜ **meridian-recorder** → **replay-mode** — the session-archive substrate and
   the flagship viewer feature; the priority, it is what makes Meridian spread.
6. ⬜ `ChunkTracker` read API → world-downloader · printer · farmer
7. ⬜ combat-assist (`DamageEntityInteraction` port); remaining VM nodes; future
   trackers as needed
