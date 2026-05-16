# meridian-core — roadmap

## The goal

**The goal is not any single module.** The goal is a complete, honest, in-proxy
mirror of Hytale's game state and mechanics — a model faithful enough that
**anyone can build a module against the neutral API without ever touching raw
packets or the protocol.**

Modules — xray, camera-tweaks, farmer, world-downloader, printer, … — are
*demonstrations and consumers* of that model. They prove the model is good
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

See [`interaction-chains.md`](interaction-chains.md) for the interaction-system
analysis, and the proxy's [`architecture.md`](../../meridian-proxy/docs/architecture.md).

## Status

| Subsystem | State | Service |
|-----------|-------|---------|
| WorldState — block-type catalog | ✅ done | `WorldState` |
| EntityTracker — entity positions + local player pose | ✅ done | `EntityTracker` |
| CameraControl — camera packet forging | ✅ done | `CameraControl` |
| InteractionRegistry — interaction catalog (pkt 66/67) | ✅ done | internal |
| InventoryTracker — hotbar/utility/tools + held items | ✅ done | internal |
| ChunkTracker — block ids at any position | ⬜ todo | planned |
| Interaction VM — flatten + simulate interaction chains | ⬜ todo | planned |
| InteractionControl — high-level interaction forging | ⬜ todo | planned |

## Roadmap

### ⬜ ChunkTracker

**What.** A live mirror of loaded chunks — the block id (+ filler + rotation) at
any world position. Built from S2C `SetChunk` (131), `ServerSetBlock` (140),
`ServerSetBlocks` (141), `UnloadChunk` (135).

**The hard part.** `SetChunk.data` is not a raw block array — each 32×32×32
section is stored as **three palettes** (block ids, filler, rotation), each an
`AbstractSectionPalette` with bit-packed indices. Honest decoding means
reproducing the palette format (`PaletteType` / `PaletteTypeEnum`).

**Why it is needed.**
- **Farmer** — scan for ripe crops / plantable soil in a radius.
- **Interaction VM** — `BlockConditionInteraction` and friends read the target
  block; without chunk data the VM must guess.
- It is the missing half of `WorldState` (`blockTypeAt` / `ghostBlock` currently
  throw `UnsupportedOperationException`).

**What it unlocks.**
- **world-downloader module** — export the live world to a local Hytale save.
- **printer module** — read a structure from the world and replay it block by
  block (copy / schematic / build automation).
- **farmer module** — autonomous crop scanning.

A single subsystem, three modules. This is why it is worth doing properly.

### ⬜ Interaction VM

**What.** Reproduce Hytale's interaction interpreter so the proxy can forge a
valid `SyncInteractionChains` for any action.

- **Flattener** — port each `Interaction` node's `compile()`: walk the tree from
  `UpdateRootInteractions`/`UpdateInteractions` into a flat `Operation[]` with
  labels and jumps; the index is the `operationCounter`.
- **Simulator** — port each node's `simulateTick()`: walk the operation list,
  evaluate conditions/charges/chaining, produce the executed operation sequence
  and the `InteractionSyncData` the server expects.
- **`InteractionContext`** — the simulation's inputs: held item (InventoryTracker),
  target block (ChunkTracker / observed `MouseInteraction`), entity state.

**Why it is needed.** Forging harvest / plant / water — and any future
automation — requires `interactionData` whose `operationCounter` + `rootInteraction`
match the server's own run (`InteractionEntry.setClientState` rejects mismatches).
See [`interaction-chains.md`](interaction-chains.md).

**Scope.** ~25 `Interaction` node types. The framework is shared; nodes are
ported one at a time. Farming nodes first (`SimpleBlockInteraction`,
`ChargingInteraction`, `ChainingInteraction`, `Condition*`, `PlaceBlockInteraction`,
`FirstClickInteraction`), the rest as consumers need them.

**Known gaps.**
- **Flattener** — done for every `compile()` override the client receives,
  *except* `DamageEntityInteraction` (combat: directional / targeted damage
  branches). It is currently flattened as a leaf, so any chain containing it
  produces a wrong operation sequence. Porting it unlocks **combat
  interactions** — a future combat-assist / PvP module (auto-attack, reach,
  hit prediction). ⬜ todo.
- **Simulator** — node `simulateTick` ports are incremental; an unported node
  falls back to a default and is logged.

### ⬜ InteractionControl

**What.** A high-level service over the VM: `useOnBlock` / `plantOnBlock` /
`waterBlock` / … plus `targetedBlock()` (the block the player looks at, from
observed `MouseInteraction`). Owns the chainId allocator / NAT.

**Why.** Layer-2 modules drive interactions without touching the VM or the
protocol — they say "harvest this block", core does the rest.

### ⬜ Future trackers (consumer-driven)

Added when a real consumer needs them, never up front: player stats / effects,
cooldowns, full inventory metadata (durability), entity components beyond
transforms, chat, … Each is the same pattern: observe traffic → neutral service.

## Modules — consumers, not the goal

| Module | State | Consumes |
|--------|-------|----------|
| meridian-xray | ✅ | WorldState |
| meridian-camera-tweaks | ✅ | CameraControl, EntityTracker |
| meridian-packetdump | ✅ | diagnostic (raw) |
| interaction test module | ⬜ | InteractionControl — demonstrates forging |
| meridian-farmer | ⬜ | ChunkTracker, InteractionControl, InventoryTracker |
| meridian-world-downloader | ⬜ | ChunkTracker (+ asset capture) |
| meridian-printer | ⬜ | ChunkTracker, InteractionControl |
| combat-assist (auto-attack / reach) | ⬜ | InteractionControl + `DamageEntityInteraction` port, EntityTracker |

Each module stays thin. If a module needs logic that other modules could reuse,
that logic belongs in core.

## Build order

1. ✅ WorldState · EntityTracker · CameraControl
2. ✅ InteractionRegistry · InventoryTracker
3. ⬜ Interaction VM (framework → farming nodes) → InteractionControl → test module
4. ⬜ ChunkTracker → completes WorldState; unlocks farmer · world-downloader · printer
5. ⬜ Autonomous farmer; remaining VM nodes; future trackers as needed

ChunkTracker and the VM are independent — either can come first. The VM is on the
critical path for the interaction test module (which targets the looked-at block
via `MouseInteraction`, no chunk data needed), so it leads; ChunkTracker follows
and unlocks the module cluster above.
