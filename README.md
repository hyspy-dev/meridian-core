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

Currently (**v0.2.0**) three services:

- **`WorldState`** — the block-type catalog. Reads server-truth block types,
  accepts per-type client-view overrides (`overrideBlockType`), and pushes the
  synchronising packet to the client. Backs X-Ray (hide blocks), Night Vision
  (light blocks), jesus-hack (solidify blocks).
- **`EntityTracker`** — live entity positions and the local player's pose, built
  from observed `EntityUpdates` / `ClientMovement` / `SetClientId`. Resolves the
  nearest entity or the entity under the crosshair.
- **`CameraControl`** — drives the client camera (first/third person, freecam,
  follow-cam, entity POV) via the same packets Hytale's own server commands use.
  Backs [meridian-camera-tweaks](../meridian-camera-tweaks).

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
