# Interaction VM — session status

Handoff snapshot for the interaction-forging / farming work. Read alongside
[`interaction-chains.md`](interaction-chains.md) (protocol analysis) and
[`roadmap.md`](roadmap.md) (overall plan).

## Where it stands

| Piece | State |
|-------|-------|
| Proxy stream-direction bug | ✅ fixed (see below) |
| `InteractionRegistry` — `UpdateRootInteractions/Interactions` catalog | ✅ done |
| `InventoryTracker` — slots + held items | ✅ done |
| `ChunkTracker` — `SetChunk` palette decode + block edits | ✅ done, verified |
| VM flattener — all `compile()` node ports | ✅ done |
| VM simulator — harvest / water / plant | ✅ **validated** (all 3 `VM check ... MATCH`) |
| VM simulator — `ConditionInteraction` (water op 0) | ✅ ported (movement-state branch) |
| VM simulator — `ReplaceInteraction` root switch (plant) | ✅ ported (`context.execute(nextRoot)`) |
| VM simulator — `BlockConditionInteraction` matchers | ⬜ catch-all `Finished` (matches observed path) |
| `InteractionControl` — `useOnBlock` / `plantOnBlock` / `waterBlock` | ✅ wired |
| Capture-replay path | ✅ works (harvest confirmed in-game) |
| `meridian-interaction-test` module | ✅ works (X/Y/Z fields + buttons) |

## The proxy bug (meridian-proxy — not core)

`ProxyFrontendHandler.linkStreams(a, b)` always gives `a` the C2S pipeline and
`b` the S2C pipeline. For **server-initiated** streams (chunk stream 3, world-map
stream 7) `bridgeStreamNow` passed the *server* stream as `a` → its S→C data ran
through a **C2S** router → `getToServerPacketById` → `null` → silently raw-forwarded.
That is why no chunk packet ever reached a module handler, with no error.

**Fix:** `bridgeStreamNow` now takes `isServerInitiated` and swaps the
`linkStreams` arguments for server-initiated streams. This also unblocks any
future world-map / world-downloader work. **The proxy jar must be rebuilt** for
the fix to take effect.

## VM — how it works

`InteractionRegistry` (pkt 66/67) → `InteractionFlattener` (ports every
`compile()` → flat `Operation[]`, index = `operationCounter`) →
`InteractionSimulator` (walks the flat list, per-node `simulateTick0` decides
Finished/Failed → fall through or jump to the failed label) → `InteractionSyncData[]`.

`InteractionContext` feeds the simulator: chain `interactionType`, target block,
the target's `BlockType` (resolved `ChunkTracker.blockIdAt` → `WorldStateImpl.blockTypeById`).

**Ported node logic** (`InteractionSimulator.evaluateState`):
- `UseEntityInteraction` → `Failed` (forged chains have no entity target).
- `UseBlockInteraction` → `Failed` unless the target `BlockType.interactions`
  has an entry for the chain's type.
- `ConditionInteraction` → `Failed` if any set flag (`jumping` / `swimming` /
  `crouching` / `running` / `flying`) mismatches the player's last observed
  `MovementStates`; else `Finished`. `requiredGameMode` is not tracked
  proxy-side and is treated as satisfied. No movement snapshot → `Finished`.
- `ReplaceInteraction` → `Finished`, then the walk switches to operation 0 of
  the replacement root (server's `context.execute(nextRoot)`). The replacement
  root is learned from observed chains (`observedReplacement`, keyed by type);
  the VM self-check derives it from the captured chain's own op roots.
- `ChargingInteraction` → `Finished`, carries `chargeValue = -2.0`.
- `PlaceBlockInteraction` → `Finished`, carries the placed block.
- everything else → `Finished`.

The VM dump (`CompiledInteraction.describe`) now prints each node's
decision-relevant fields — `ConditionInteraction` flags,
`ReplaceInteraction` `var`/`default`, `BlockConditionInteraction` matcher count.

**VM self-check:** every observed real chain is run through the VM and
`meridian-core: VM check <type> root <id> — real=[…] vm=[…] MATCH/MISMATCH` is
logged. The captured chain is the simulator's test oracle.

## Farming root trees (this server — ids are server-specific)

```
Harvest — root 2829  *Empty_Interactions_Use   (6 ops)   ✅ VM validated
  0 SimpleInteraction      labels=[6]
  1 UseBlockInteraction    labels=[3]   -> Failed (crop has no Use) -> jump 3
  2 jump -> 6
  3 UseEntityInteraction   labels=[5]   -> Failed (no entity)       -> jump 5
  4 jump -> 6
  5 BreakBlockInteraction               -> Finished (harvest = break)
  executed: 0,1,3,5

Water — root 2373  Watering_Can_Filled_Use   (20 ops)   ✅ VM validated
  0 ConditionInteraction  labels=[5]  { crouching=true }
                                      -> Failed (player not crouching) -> jump 5
  5 SimpleInteraction      labels=[8]  -> Finished -> 6
  6 SimpleInteraction                  -> Finished -> 7 jump 20 (end)
  executed: 0,5,6
  (op 1 fill / op 8+ charge paths exist; not on the observed branch)

Plant — root 2427  Seed_Condition   (2 ops)   ✅ VM validated
  0 BlockConditionInteraction labels=[2]  -> Finished -> 1
  1 ReplaceInteraction  { var='SeedId' default=#935 }
                                      -> Finished, context.execute(root 3385)
Plant place — root 3385  *Plant_Seeds_Sunflower_…   (1 op)
  0 PlaceBlockInteraction               -> Finished
  executed: 0(2427), 1(2427), 0(3385)
```

Root ids are **not** hard-coded — `InteractionControl` learns them from observed
C2S chains (`observedRoot` map, keyed by `InteractionType`).

## Capture-replay vs VM

Two paths in `InteractionControlImpl.forge`:
- **Capture-replay** — re-sends a real `initial=true` chain the player performed,
  with a fresh `chainId` and retargeted block. Proven in-game for harvest.
  Per-action-kind; multi-packet chains (water) are not yet captured whole.
- **VM** (`buildFromVm`) — flatten + simulate. Validated for harvest. Used as the
  fallback when no capture exists. Needs `observedRoot` populated (one observed
  interaction of that type).

`forge` currently prefers the captured template, VM is the fallback.

## Next steps

1. ~~Port water + plant node `simulateTick0`.~~ ✅ done — harvest / water / plant
   all `VM check ... MATCH`.
2. chainId NAT — forged chains bump the shared counter; currently safe only while
   the player is idle. Needed before an autonomous farmer.
3. Then `meridian-farmer` (Layer-2): scan crops via `ChunkTracker`, drive
   `InteractionControl`.
4. Optional honest-port polish: `BlockConditionInteraction` matchers,
   `SimpleBlockInteraction` air/chunk checks, `ChargingInteraction` charge
   branches — currently catch-all `Finished`, which matched every observed
   path but is not the full node logic.

## Open risks / gotchas

- **chainId race** — `InteractionControl` increments one counter; the real client
  has its own. Forging while the player interacts can desync. NAT not built yet.
- **Bare `interactionData = null` does not work** — harvest is `requiresClient`;
  the server waits for per-op client data. Every forge needs a populated
  `interactionData` (VM or replay).
- **VM root** — the VM needs `observedRoot` learned from one real interaction of
  that type. Resolving roots by name from the registry would remove that.
- **Memory** — `ChunkTracker` keeps `int[32768]` per section (~128 KB each); fine
  for now, may need the compact palette form later.

## Build / test

```sh
.\build-releases.ps1            # rebuilds proxy + all modules into _releases\
```

Deploy `meridian-proxy` (the fixed jar!) + `meridian-core-impl` +
`meridian-interaction-test`. In-game: the test module's panel has X/Y/Z fields
and Use / Plant / Water buttons; "Fill from last observed block" copies the last
interacted block. Watch the proxy log for `VM check …` and `forged … chain`.
