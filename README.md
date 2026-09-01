# meridian-core

A Layer-1 module for the [Meridian Proxy](../meridian-proxy) — **headless Hytale
game state living inside the proxy**.

It observes server→client traffic to build a model of the game world, and exposes
that model to Layer-2 modules (xray, etc...) through neutral interfaces. Those
modules then speak in terms of "the player placed a block", never raw packets — so
a Hytale protocol update is absorbed here and never reaches them.

## Artifacts

| Module | Role |
|--------|------|
| `meridian-core-api` | Neutral interfaces and value types. Layer-2 modules depend on this (`provided`). Contains no `meridian-protocol` types. |
| `meridian-core-impl` | The loadable `ProxyModule`. Maps raw Hytale packets onto the API and publishes services into the proxy's `ServiceRegistry`. Shades `meridian-core-api` into itself. |

## What it provides

The services, and what each is for:

**The world**

- **`WorldState`** - the block-type catalog. Reads server-truth block types,
  accepts per-type client-view overrides (`overrideBlockType`), and pushes the
  synchronising packet to the client. Backs X-Ray (hide blocks), Night Vision
  (light blocks), jesus-hack (solidify blocks).
- **`WorldChunks`** - the raw chunk feed: sections, fluids, biomes, heightmaps and
  column unloads, exactly as the server sends them, plus the id-to-name tables that
  make sense of them. For anything that wants the world itself rather than an answer
  about it. Backs [meridian-world-downloader](../meridian-world-downloader).
- **`ChunkView`** - what the client is allowed to forget: keeps flown-over chunks on screen
  (by holding back the server's unloads) and forces a view radius so the client actually draws
  them. Core's chunk feed sits ahead of it, so nothing that reads the world loses an event to it.
- **`World`** - the convenient front door: the player, blocks by position, nearby
  entities. Most modules want this rather than the pieces underneath it.
- **`EntityTracker`** - live entity positions and the local player's pose, built
  from observed `EntityUpdates` / `ClientMovement` / `SetClientId`. Resolves the
  nearest entity or the entity under the crosshair.

**The map**

- **`WorldMap`** - the server's own map, collected per world, **for the session only**. Core
  holds a working map, not an archive: it answers "what colour is it there" and lets the view
  redraw ground the client has forgotten, and it writes nothing to disk. A module that wants the
  map to outlive the session keeps its own copy - see
  [meridian-world-map](../meridian-world-map), which subscribes through `onTileChanged` and
  archives what it is given.
- **`WorldMapView`** - what the client is shown of it, including `setTileFilter` / `refreshTile`:
  a module can repaint a tile on its way out (the world downloader reddens ground it has not
  downloaded) and ask for it to be sent again once it looks different, without touching the
  packet itself. A replayed tile keeps the size the server drew it at (this build draws 96 pixels
  a side, not 32 - that number is a tile's span in *blocks*, not pixels); `setTileSize` is only an
  upper bound.
- **`MapMarkers`** - every marker on the map: the server's, and ours. Local markers
  are drawn by rewriting the server's own packet, so they behave like real ones.
- **`MarkerArchive`** - the memory behind that, for whoever keeps the file. Core remembers markers
  while it runs but no longer writes them down: [meridian-markers](../meridian-markers) hands back
  what it kept at startup and writes core's memory out as the session goes. Travels as JSON, which
  is what lets a Layer-2 module keep a format only core knows how to read.
- **`Waypoints`** - saved places, as a plain list, on top of `MapMarkers`.

- **`Containers`** - what the player finds inside a chest, a furnace or a bench. The server tells
  nobody what is in one until it is opened, so this is the only way it can be known; the block it
  belongs to is matched from the interaction that opened it, since the window packet carries the
  contents but not the position. That interaction is the **chain** the client runs
  (`Open_Container`) - the mouse packet before a chest opens carries no block at all.

- **`PlayerState`** - the player as the server keeps them: where they are, which way they face,
  their stats by name (`Health`, `Stamina`, …) and every inventory panel with the stacks in it.
  Read off the traffic that keeps the player's own screen honest, so it is the server's word
  rather than a guess.

**Provided by modules, read by core's neighbours**

- **`Coverage`** - which ground has actually been downloaded, from
  [meridian-world-downloader](../meridian-world-downloader).
- **`MarkerSource`** - markers kept across sessions, and the pictures they are drawn with, from
  [meridian-markers](../meridian-markers).

Both are optional by nature: ask the registry with `get`, not `require`, and work plainly when
nobody is providing them.

**The client**

- **`Hud`** - drawing on the client: the command vocabulary without the packets.
- **`ClientAssets`** - handing the client files (images, fonts), at connect time or
  later. Read [`docs/hytale-client-ui.md`](../docs/hytale-client-ui.md) before building
  an interface: what the client will and will not do is measured there.
- **`Chat`** - saying things to the player.
- **`CameraControl`** - drives the client camera (first/third person, freecam,
  follow-cam, entity POV) via the same packets Hytale's own server commands use.
  Backs [meridian-camera-tweaks](../meridian-camera-tweaks).
- **`DebugRender`** - boxes and lines in the world.

**Acting**

- **`InteractionControl`** - forging the interaction chains the server expects, so a
  module can dig, place or use something the way the client would have.
- **`SelectionBus`** - who is selecting what.

meridian-core is **consumer-driven**: services are added one at a time when a real
Layer-2 consumer needs them. The full target catalog (PlayerState, InventoryState,
ChatService, ...) is described in the proxy's
[architecture doc](../meridian-proxy/docs/architecture.md) — it is *not* built
up-front.

## Build

```sh
mvn clean install
```

Requires `meridian-api` and `meridian-protocol` in the local Maven repo — build the
[`meridian-proxy`](../meridian-proxy) repo first (`mvn install`). Produces the
loadable module:

```
meridian-core-impl/target/meridian-core-impl-<version>.jar
```

## Using it

Drop `meridian-core-impl-*.jar` into the proxy's modules folder. A Layer-2 module
consumes it by:

- depending on `meridian-core-api` (`provided`) at compile time;
- declaring `"dependsOn": { "meridian-core": ">=0.2.0" }` in its `module.json`;
- calling `ctx.services().require(WorldState.class)` (or `EntityTracker` /
  `CameraControl`) in `onEnable`.

See [meridian-xray](../meridian-xray) (`WorldState`) and
[meridian-camera-tweaks](../meridian-camera-tweaks) (`CameraControl` +
`EntityTracker`) for worked examples.

## Versioning

`meridian-core` is versioned independently of the proxy (it changes more often —
the protocol mapper lives here). See the proxy's
[releasing doc](../meridian-proxy/docs/releasing.md).
