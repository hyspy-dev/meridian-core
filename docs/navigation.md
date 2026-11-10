# Navigation and work

Two core services take the player somewhere and put them to work there:

- **`Navigator`** finds a way and goes it, move by move, through `Movement`: walking, jumping up,
  pulling up onto ledges, dropping down, leaping gaps, swimming, climbing ladders and vines, flying
  where the player may, and opening doors on the way.
- **`Worker`** breaks a block. It takes the hotbar's best tool, walks within that tool's reach and
  breaks what is in the way first. It also picks up drops and puts items into a chest.

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
  - a sprinting jump over a gap after a run-up, see below;
  - into, through and out of water;
  - onto, along and off a ladder, a vine, a rope;
  - a flight.
- **Leaps.** A jump over a gap along an axis, sprinted after a run-up: the level ground before the
  gap, up to 4 blocks of it. The longer the run-up, the faster the take-off. How a leap flies after
  each run-up, from a standstill, core works out with the body's own laws (`RunUps`). Across the
  gap, the ground from 3 up to 2 down is either:
  - come down on, when the feet are over its top as the body's front passes its near side;
  - or taken by the hands: the body meets its wall short of the top, with the head no more than a
    hand under it, and pulls up onto it.

  So a gap of 5 is leapt with a run-up, a gap of 6 with a pull-up at the far side, and a ledge 2 up
  over a gap of 2 with a pull-up.
- **Doors.** A shut door on the level is a way through. It is opened first.
- **Diagonals** are walks and swims only, and only where both corners leave room.
- **Costs** are in seconds.
  - A jump up, a pull-up and a leap are costed by length, not time: the blocks across plus the rise,
    at a run. So a shorter way over a ledge or a gap wins over a longer way round it. A leap that
    ends in a pull-up costs a little more.
  - The estimate is the straight distance at the body's fastest.
- **Budget.** A search stops after 40,000 places or 1.5 s. When the budget runs out, or the world
  beyond is not sent yet, it gives the way to the nearest place to the goal it saw. The walker goes
  that far and looks again, the way Baritone plans in segments.

## How a way is straightened and sprinted

- **Straightened.** A run of walks over level ground becomes one straight line, from where the body
  is or the last point kept, on to the farthest walk a straight line reaches. The whole body must go
  over the same floor all the way, with nothing in the way, nothing that burns and no fluid. A corner
  of the grid becomes a slant, and turns come fewer and gentler. The walk before a move of another
  kind (a jump, a pull-up, a leap, a door) is kept, so that move starts where it was planned from.
- **Sprinted.** A straight walk 6 blocks long or more is sprinted, as long as the stamina pays for
  it. A sprint takes 1 stamina a second, and 4 are kept for the leaps' run-ups. While core does not
  know the stamina, nothing is sprinted.
- **Checked.** The straightened and sprinted way is played on a copy of the body too. If it would
  not do, the way as found is gone instead.

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
- **The head.** It turns the way a player's does with a mouse: fast while it is far from where it
  wants to look, slowing as it gets there, at most 9° a tick. It looks at the way 2 blocks ahead of
  the body, so near a corner it starts turning toward the next move before the corner. The keys go
  with the head: while it is still turning, the body goes where the move sends it, forward taking a
  side key's share, as a player runs one way while the mouse swings the look round.
  - A short step on its own (under 1.2 blocks, no move after it) leaves the head where it is: the
    body steps sideways or back, and the look stays on what it is on.
  - Going somewhere, the head keeps its pitch, within -35° to 15°: it looks neither at the feet nor
    up at the horizon for no reason.
- **Sprint in a leap** is held through the flight, as a player holds it. Let go in the air, the speed
  falls to a run's, and a sprinting leap comes down a block short.

## Where the head looks

`Movement.look` and `Movement.face` turn the head the same way, a tick at a time. A look is done
once the head is near enough (`look(look, within)`). `face(block)` is done once the look is on the
block: within the angle of 0.35 of a block from its middle, so the head stops short of the very
middle, as a player's does.

## How a block is broken (`Worker.dig`)

- **The tool.** The hotbar's tool that breaks the block best (`Player.bestToolSlot`). The rule is
  the server's own: a spec of the block's gather type, of at least its quality, the strongest.
  - `Tools` can prefer a tool for a gather type. For example, `Soils` gets `Shovel`: dirt, gravel
    and sand are dug with a shovel when the hotbar has one. This matters for more than speed: a
    pickaxe on gravel gives a drop list of its own, and an iron pickaxe and an iron shovel hit soil
    equally hard.
  - Near the edge of `Access.within`, a tool whose dig is a sweep (`InteractionControl.digSweeps`)
    is passed over for one that breaks only the block it aims at. A shovel's 2.5-block arc breaks
    every block in it that it breaks, so it would take soft blocks just outside the box with it.
    "Near" means a block it breaks in earnest (power 0.2 or more) is among the 26 around, outside
    the box. With no such other tool, the sweep is taken all the same.
- **Where from.** A place within that tool's dig reach (`InteractionControl.digReach`), as the
  server's config for the item says, kept 0.3 short:
  - A pickaxe breaks the block it is aimed at, within the item's use distance for the game mode
    (`InteractionControl.useReach`): 5 in Adventure unless the item says otherwise. In Creative it
    is at least the client's creative reach, 10 unless its settings say otherwise.
  - A shovel's sweep reaches as far as its far end: 2.5.

  The body stays where it is when it can, and otherwise walks there. A place farther than 3 blocks
  from the block, eyes to its middle, costs more for each block past, so the body steps up to what
  it digs when a nearer place will do. It never stands on the block, nor on a block it breaks first.
  Up to 3 places are tried.
- **The line from the eyes** to the block is 0.12 thick each side: where it passes that near an edge
  of a block, that block is in the way too. A line that only gets through where two blocks meet at an
  edge or a corner does not get through: a player does not hit through a crack.
- **Where on the block.** The line goes to the block's middle, or to the middle of a face turned
  toward the eyes, whichever has the fewest blocks in the way. A block low in a tunnel's wall is hit on
  its face, not through the block over it toward its middle. The head turns to that point.
- **The other side of a wall.** A place costs 3 more for each solid block on the straight line
  between the body and it: it is likely not to be got to.
- **Inside the box** (`Access.inside`), a block in the way costs little to break first (0.5 against
  6), since it is dug anyway: the body stays and digs on rather than walking round to a clear line.
- **In the way.** What `Access` allows is broken first, each block with its own best tool. A place
  costs by all it breaks there: the way in and what is still on the line.
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
cell, or, where the body cannot stand there (on a block under a low ceiling, in a hole), to the
place nearest the body with the feet within 1.5 of the drop: the server picks a drop up within 1.75
of the feet. A drop it cannot get to is left.

`Worker.pickUp(items)` does the same for these items, wherever they lie. `EntityTracker.firstSeenAt`
says where core first saw an entity: for an item just dropped, where it fell from - so a module can
tell its own drops however far they went since.

## How items go into a chest (`Worker.deposit`)

The worker walks within the held item's use reach of the chest (`InteractionControl.useReach`,
kept 0.3 short) and opens it, as a use does. It moves the stacks
of the items asked for in from the hotbar, the storage and the backpack, as a shift-click does:
`SmartMoveItemStack`, `PutInHotbarOrWindow` (from the hotbar or the storage, into the open window
first). Then it closes the window, on the server and on a game's screen.

What went in is counted as what the player holds fewer of afterwards. What did not fit stays with
the player.

`Containers.lastOpened()` names the chest the player opened last, so a module can let the player
choose one by opening it.

## In the proxy's window and on the command channel

Core's panel has **Go to a point**:

- X, Y and Z fields, filled from where the player stands or from the block they last clicked;
- **Go** and **Stop**. Both also end the `Worker`'s work under way, whichever module set it going.

```
nav <conn> goto <x y z> [radius]   -> {status, at, moves, replans}
nav <conn> plan <x y z> [radius]   -> the moves (kind, cell, gait), without moving
nav <conn> trace <x y z> [radius]  -> the way as found, played on a copy of the body tick by tick
nav <conn> cancel                  -> ends the trip and the work under way
core <conn> placeat <x y z> [face] -> the held block into an empty cell (World.placeAt)
core <conn> chest <x y z>          -> what core last saw in that container, and which was opened last
core <conn> reach [item]           -> how far a dig (reach) and a use (use) with the item reach
core <conn> besttool <x y z>       -> the hotbar slot that breaks that block best
```

## Not yet

- **Gliding** and **riding**.
- **Hanging along a ledge** as a way of its own. The body can do it; the search does not plan it.
- **Leaps on a slant.** A leap goes along an axis only.
- **Curves.** A way is straight lines between points; the head's look ahead and the pass through each
  point round the corners off.
- **Breaking or building through what is in the way of a trip.** A way is found over the ground as
  it is.
- **Tunnels.** A block deeper in the rock than a tool reaches from open ground is left.
- **Placing with a given rotation**, and getting blocks from the backpack into the hotbar. Both are
  for building a blueprint.
