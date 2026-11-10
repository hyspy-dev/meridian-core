# Navigation and work

Two core services take the player somewhere and put them to work there:

- **`Navigator`** finds a way and goes it, move by move, through `Movement`: walking, jumping up,
  pulling up onto ledges, dropping down, leaping gaps, swimming, climbing ladders and vines, flying
  where the player may, and opening doors on the way.
- **`Worker`** breaks a block. It takes the hotbar's best tool, walks within that tool's reach and
  breaks what is in the way first. It also picks up drops.

Both move a headless player, and a game's player, whose body core takes from the game
(`Movement.take`).

A module decides what to go to and what to work on. `meridian-auto-miner` decides what to dig;
`meridian-blueprint` will decide what to build.

```java
Navigator nav = ctx.services().require(Navigator.class);
nav.goTo(Goal.near(new BlockPos(120, 64, -40), 2))
   .thenAccept(r -> { if (r.arrived()) { /* work there */ } });

Worker worker = ctx.services().require(Worker.class);
worker.dig(new BlockPos(121, 63, -40), Worker.Access.ANY)
      .thenAccept(d -> { /* d.outcome(), d.broken() */ });
```

## The body: holds

`Movement.take` gives each taker a hold of its own. The game gets the body back when the last hold
is given back. It gets it back at once when the player presses a key that moves them, the server
teleports them, they die, or they go to another world.

So nobody yanks the body from anyone else:

- A trip holds the body only while it lasts.
- One dig, or one round of picking up, holds it while it lasts.
- A job of many blocks holds it from start to end, so the body stays held between blocks.
- A hold that ends sees why through `Wheel.released()`.

## How a way is found

- **Places.** A* runs over where the body can be:
  - standing on the ground (`Movement.standAt`);
  - swimming in a fluid;
  - holding on to something climbable;
  - in the air, where it may fly (`Movement.cell`).
- **Edges** are the body's moves. Their sizes come from `Movement.capabilities()`, which core works
  out with the body's own physics:
  - a walk, a step up or down included;
  - a jump up, a pull-up onto a ledge;
  - a drop the body survives unhurt, and a drop of any height into deep water;
  - a running or sprinting jump over a gap: level, a block up or a block down;
  - into, through and out of water;
  - onto, along and off a ladder, a vine, a rope;
  - a flight.
- **Doors.** A shut door on the level is a way through. It is opened first.
- **Diagonals** are walks and swims only, and only where both corners leave room.
- **Costs** are in seconds.
  - A jump up and a pull-up are costed by length, not time: a block across plus the rise, at a run.
    So a shorter way over a ledge wins over a longer way round it.
  - The estimate is the straight distance at the body's fastest.
- **Budget.** A search stops after 40,000 places or 1.5 s. When the budget runs out, or the world
  beyond is not sent yet, it gives the way to the nearest place to the goal it saw. The walker goes
  that far and looks again, the way Baritone plans in segments.

## How a way is gone

- **Played on a copy first.** The whole way, up to its first door, is played on a copy of the body
  (`Movement.predict`). If a move would not arrive, or would land hard enough to hurt, it is banned
  and the way is found again.
- **All at once.** The checked way goes to `Movement` as one queue of moves, so the body runs through
  its points without stopping.
- **Doors.** At a door the walker turns to it and uses it (`InteractionControl.useOnBlock`). It waits
  for the server to show the door open, then looks again from there. A door that does not open is
  gone round.
- **Looking again.** A move that goes wrong, or a way that only got nearer, means a new search from
  where the body is. A trip gives up after 12 such searches, or when a partial way gets no nearer
  than the one before.
- **One trip at a time.** A new `goTo` ends the trip under way.

## How a block is broken (`Worker.dig`)

- **The tool.** The hotbar's tool that breaks the block best (`Player.bestToolSlot`). The rule is
  the server's own: a spec of the block's gather type, of at least its quality, the strongest.
- **Where from.** A place within that tool's dig reach (`InteractionControl.digReach`):
  - A pickaxe breaks the block it is aimed at, up to 5 away. 4.5 is used.
  - A shovel's sweep reaches 2.5.

  The body stays where it is when it can, and otherwise walks there. It never stands on the block,
  nor on a block it breaks first. Up to 3 places are tried.
- **In the way.** What `Access` allows is broken first, each block with its own best tool:
  - `Access.inside(box)`: only blocks inside the box. A selection is dug from inside itself.
  - `Access.ANY`: anything on the line from the eyes. For a block in the rock, also a way in the
    body's height, so the body can walk in after the drop.
- **Left.** These outcomes leave the block where it is:
  - `LAVA`: lava is next to the block, or next to the way in.
  - `NO_PLACE`: no place to break it from.
  - `UNREACHABLE`: no way to any such place.
  - `WOULD_NOT_BREAK`: it, or a block in the way, did not break in 30 swings.

## How drops are picked up (`Worker.pickUp`)

The worker walks over the drops lying in the area, nearest first. It goes into each drop's own
cell, or as near as it can stand: the server picks a drop up within 1.75 of the feet. A drop it
cannot get to is left.

## In the proxy's window and on the command channel

Core's panel has **Go to a point**:

- X, Y and Z fields, filled from where the player stands or from the block they last clicked;
- **Go** and **Stop**. Both also end the `Worker`'s work under way, whichever module set it going.

```
nav <conn> goto <x y z> [radius]   -> {status, at, moves, replans}
nav <conn> plan <x y z> [radius]   -> the moves (kind, cell, gait), without moving
nav <conn> cancel                  -> ends the trip and the work under way
core <conn> placeat <x y z> [face] -> the held block into an empty cell (World.placeAt)
```

## Not yet

- **Gliding** and **riding**.
- **Hanging along a ledge** as a way of its own. The body can do it; the search does not plan it.
- **Breaking or building through what is in the way of a trip.** A way is found over the ground as
  it is.
- **Tunnels.** A block deeper in the rock than a tool reaches from open ground is left.
- **Placing with a given rotation**, and getting blocks from the backpack into the hotbar. Both are
  for building a blueprint.
