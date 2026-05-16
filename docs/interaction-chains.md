# Hytale Interaction Chains — analysis & forging

Reverse-engineering notes for the interaction system, gathered for `meridian-farmer`.
Everything here is verified against the decompiled server (`Hytale-Server-Unpacked`)
and live packet captures (`meridian-packetdump`).

The goal: let the proxy **forge** block interactions (harvest a crop, water soil,
plant a seed) the player never performed.

---

## 1. The interaction system

Block/item actions in Hytale do **not** travel as one self-contained packet. They
run through a stateful **interaction chain** protocol:

- **`SyncInteractionChains`** (packet id **290**, `C2S` + `S2C`) — carries a batch
  of `SyncInteractionChain` updates. This is the only wire packet involved.
- Client builds a chain locally, simulates it, and streams `SyncInteractionChain`
  updates to the server. The server re-simulates authoritatively and echoes its
  own `SyncInteractionChains` back.
- `MouseInteraction` (id 111) is **not** the trigger — on the server it only fires
  input events and updates the camera. The real work is the chain.

### `SyncInteractionChain` — fields that matter

| Field | Meaning |
|-------|---------|
| `chainId` | Identifies the chain. Client chains are **positive & strictly increasing**; server-initiated chains are **negative**. |
| `initial` | `true` on the first packet of a chain. |
| `interactionType` | `Use`, `Secondary`, `Primary`, `SwapFrom`, … — selects which root interaction the server resolves. |
| `activeHotbarSlot` / `activeUtilitySlot` / `activeToolsSlot` | Must equal server truth or the chain is cancelled. |
| `itemInHandId` / `utilityItemId` / `toolsItemId` | Must equal server truth. |
| `equipSlot` | The acting slot (= `activeHotbarSlot` for hotbar actions). |
| `overrideRootInteraction` | **S2C only.** The server *ignores* it on C2S — it always resolves the root itself. |
| `data` | `InteractionChainData` — `blockPosition` (target block), `entityId`, `proxyId`, `targetSlot`. |
| `state` | `InteractionState` of the whole chain: `NotFinished` / `Finished` / `Failed`. |
| `operationBaseIndex` | Index of `interactionData[0]` in the chain's linear operation log. |
| `interactionData` | `InteractionSyncData[]` — the client's per-operation simulation result. May be `null`. |

### `InteractionSyncData` — per-operation

`state`, `progress`, `operationCounter`, `rootInteraction` (the registry **int** id),
`chargeValue`, `blockPosition`, `blockRotation`, `blockFace`, `placedBlockId`, …

---

## 2. Server acceptance rules (`InteractionManager.syncStart`)

For an `initial = true` chain the server requires:

1. `initial == true`, `forkedId == null`.
2. `chainId > 0` **and** `chainId > lastClientChainId` (monotonic).
3. `data.proxyId` empty → `InteractionContext.forInteraction(type, equipSlot, …)`.
4. `context.getRootInteractionId(type)` resolves non-null. **The server derives the
   root itself** from the held item's / target block-entity's `Interactions` map
   keyed by `interactionType`. The client never picks the root.
5. `activeHotbarSlot` / `activeUtilitySlot` == server truth.
6. `itemInHandId` / `utilityItemId` == server truth.
7. Not on cooldown.

No signature, no nonce, no secret — acceptance is **consistency**, not crypto.

### `sync()` — how `interactionData` is consumed

```java
if (packet.interactionData == null) {
    chainSyncStorage.setClientState(packet.state);   // just take the chain state
} else {
    for each InteractionSyncData:
        index = operationBaseIndex + i
        if server interaction entry exists  -> interaction.setClientState(syncData)
                                               (mismatch -> flagDesync())
        else                                -> buffer it (consumed when the op ticks)
}
```

So `interactionData` **may be `null`** — then only the chain `state` is applied.
A *wrong* non-null entry triggers `flagDesync()`. The `desync` flag is recoverable
— the real client desynced once mid-capture and the chain still completed.

### chainId is a shared, implicit counter

After a chain is accepted, `lastClientChainId = chainId`. The real client keeps its
own positive counter; nothing S2C resets it. If the proxy injects `chainId = N`, the
client's next chain (≤ N) is **rejected**. → the proxy must **NAT chainIds**: own a
single counter, rewrite the client's C2S ids upward, map S2C responses back, swallow
responses for proxy-only chains. Negative (server) ids pass through untouched.

---

## 3. The three farming actions

All evidence below is from live captures.

### 3.1 Harvest — `interactionType = Use`

The crop block-entity provides a `Use` interaction
(`Plant_Crop_<X>_…_StageFinal_Interactions_Use`). The server resolves it from the
**block**, not the held item — any held item works.

- Client sends **one** `SyncInteractionChain`, `initial = true`, `state = Finished`.
- Completes in **one round trip**. No charge, no streaming.
- `interactionData` was present in the capture but, per `sync()`, **`null` is
  accepted** — the server self-runs the chain and `state = Finished` closes it.

**Forge:** one packet, `interactionData = null`, `state = Finished`. ✅ fully synthesised.

### 3.2 Water — `interactionType = Secondary`, **charged**

Held item `*Tool_Watering_Can_State_Filled_Water`. Targets the **soil** block
(`blockFace = Up`). The chain streams ~22 C2S packets over ~0.35 s with rising
`progress`, because it contains a **`ChargingInteraction`** operation.

Server code:

```java
ChargingInteraction.needsRemoteSync()  -> true        // => requiresClient = true
ChargingInteraction.tick0():
    if (clientData.chargeValue == -1.0f) state = NotFinished;   // server waits
    else if (clientData.chargeValue == -2.0f) state = Finished; // finish now
    else { state = Finished; jumpToChargeValue(clientData.chargeValue); }
```

The server **never self-completes a charge** — it reads `clientData.chargeValue`.

**Forge:** one packet, but it **must** carry `interactionData` whose charge
operation has `chargeValue != -1.0` (use `-2.0` = "finish now"), every op
`Finished`, chain `state = Finished`. A bare (`interactionData = null`) packet
would stall the charge op forever. ⚠️ needs a populated `interactionData`.

### 3.3 Plant — `interactionType = Secondary`, **PlaceBlockInteraction**

Held item is the seed (`Plant_Seeds_Sunflower`). Targets the **soil** block
(`y`); the crop block is placed at `y + 1`. Root `2427` chains into `3385`
(the placement). Near-instant (`progress ≈ 0.016`, no charge).

Server code — `PlaceBlockInteraction`:

```java
needsRemoteSync() -> true
tick0() firstRun:
    blockPosition = clientState.blockPosition;     // REQUIRED, non-null
    blockRotation = clientState.blockRotation;     // REQUIRED, non-null
    if (blockPosition != null && blockRotation != null) {
        ... distance / chunk / held-item checks ...
        clientPlacedBlockId = clientState.placedBlockId;   // -1 => use held item's block
        BlockPlaceUtils.placeBlock(..., blockFace = clientState.blockFace, ...);
    }
```

**Forge:** one packet with `interactionData` whose placement op carries
`blockPosition` (= `y+1`), `blockRotation` (non-null, zeroed is fine),
`blockFace = Up`, `placedBlockId = -1` (server falls back to the held seed's
block). ⚠️ needs a populated `interactionData`.

> ⚠️ `placedBlockId` must be `-1` or a **valid** id — the server does
> `getAsset(placedBlockId)` for anything `!= -1`, so a garbage value
> (e.g. `Integer.MIN_VALUE`) risks an NPE. Send `-1`.

### Summary

| Action | `interactionType` | One packet? | `interactionData` | Notes |
|--------|-------------------|-------------|-------------------|-------|
| Harvest | `Use` | ✅ | `null` ok | server resolves root from crop block |
| Water | `Secondary` | ✅ | **required** — charge op `chargeValue = -2.0` | `ChargingInteraction` |
| Plant | `Secondary` | ✅ | **required** — place op `blockPosition`+`blockRotation`+`blockFace` | `PlaceBlockInteraction`, target `y+1` |

No streaming runner is needed for any action.

---

## 4. Forging recipe

A forged `SyncInteractionChain`:

```
activeHotbarSlot / activeUtilitySlot / activeToolsSlot  = current server truth
itemInHandId / utilityItemId / toolsItemId              = current server truth
initial            = true
desync             = false
overrideRootInteraction = Integer.MIN_VALUE
interactionType    = Use | Secondary
equipSlot          = activeHotbarSlot
chainId            = next NAT counter value
forkedId           = null
data.entityId      = -1
data.proxyId       = 0-UUID
data.blockPosition = target block
data.targetSlot    = Integer.MIN_VALUE
state              = Finished
operationBaseIndex = 0
interactionData    = null (Use) | populated (Secondary)
```

Wrapped in `SyncInteractionChains { updates = [chain] }` and sent **to the server**.

**Prerequisites the proxy must track:**

- **Inventory** — `activeHotbarSlot` + `itemInHandId` (and utility/tools) must match
  server truth. Cheap source: every C2S `SyncInteractionChain` the *client* sends
  already carries them; observe and snapshot. `SetActiveSlot` (id 177) gives slot
  changes. A real inventory tracker is the robust source.
- **chainId** — observe the client's C2S chains, track the high-water mark, NAT.
- **Target block** — the client reports the looked-at block in C2S
  `MouseInteraction.worldInteraction.blockPosition`; observe it. (A real raycast
  would need chunk tracking — not required while we can read the client's pick.)

---

## 5. Open questions — require live testing

1. **Water collapse** — does one packet with the charge op pre-`Finished`
   (`chargeValue = -2.0`) actually water, or does the server still expect the
   intermediate progress stream? Code says it should work; only a live server confirms.
2. **Plant `interactionData` shape** — the 3-op structure (`2427`/op0, `2427`/op1,
   `3385`/place) is reconstructable from the capture, but the exact
   `operationCounter` / index mapping for a *collapsed* single packet is unverified.
3. **chainId race** — until the NAT is built, a forged chain bumps `lastClientChainId`
   and can reject the player's next manual interaction. Safe only while the player
   is idle.
4. **Slot-swap chains** — changing slots also emits a `SwapFrom`/`SwapTo` chain
   (item equip animation). `SetActiveSlot` alone changes the server's active slot;
   the swap chain is likely cosmetic. Unverified.

---

## 6. Implementation plan

**meridian-core (Layer 1)** — new `InteractionControl` service:

| Method | Behaviour |
|--------|-----------|
| `targetedBlock()` | last block from observed C2S `MouseInteraction` |
| `useOnBlock(pos)` | forge a `Use` chain — fully synthesised, `interactionData = null` |
| `plantOnBlock(pos)` | forge a `Secondary` chain with the place-op `interactionData` |
| `waterBlock(pos)` | forge a `Secondary` chain with the charge-op `interactionData` |

Backed by an observer that snapshots inventory state + chainId high-water mark from
C2S traffic, and a chainId allocator (NAT comes later).

**Test module (Layer 2)** — buttons "Use / Plant / Water on targeted block",
firing each forge against `InteractionControl.targetedBlock()`. This is how
section 5's open questions get answered.

**meridian-farmer (Layer 2, later)** — scan for ripe crops / soil, switch slot,
call `InteractionControl`. Needs chunk tracking + the chainId NAT.
