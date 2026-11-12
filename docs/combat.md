# Combat

What core offers a module that fights: the fight as the client is told of it, what an item does in
a fight, a repair as a player makes it, a chase, and urgent holds on the body. Whom to fight is the
module's call (`meridian-auto-fighter`).

## Other entities' health (`EntityTracker.statOf`, `healthOf`)

The server shares some stats of every entity with whoever sees it: `Health` is one (`Health.json`:
`Shared`), the bar the game draws over a mob. Core keeps every entity's shared stats the way it keeps
the player's own (`StatSheet`: each change applied in turn, the bounds moved by the modifiers - a
mob's `MaxHealth` is a modifier). `statOf(id, "Health")` is the value with its min and max;
`healthOf(id)` the same. A value at its min is an entity dead or dying.

`heldItemOf(id)` is what an entity holds in its main hand (its `Equipment`).

## The fight (`Combat`)

- **A hit on the player.** The server tells a player hurt by an entity how much, of what kind, and
  where the attacker stood (`DamageInfo`, `DamageSystems`). Core names the entity it tracks standing
  within 1.5 of that place. `hits(within)` lists them; `hitBy(id, within)` asks about one.
- **An attack begun.** The server plays every interaction another entity starts to the players who
  see it (`PlayInteractionFor`, `Interaction.sendPlayInteract`), so their games can animate it. The
  first of a `Primary` or `Secondary` chain is an attack begun: `swings(within)`, `swingOf(id,
  within)`. A zombie's attack shows 0.3 s before its blow lands (measured on the stand; its asset
  says 0.2 s of wind-up before its selector).
- There is no attitude on the wire. Whether a mob is hostile is the module's guess.

Listeners (`Combat.Listener.hit`, `swing`) are called on the network threads.

## What an item does in a fight

- `InteractionControl.hitReach(item)`: the far end of the item's `Primary` swing selector - the
  shape the server sweeps for the swing - through the item's own interaction variables (`Replace`),
  the farthest of its swings. A sword's is 2.75. The server takes a hit up to the use distance + 2
  from the eyes (`canPlayerInteractWithEntity`); a hit from beyond the swing is no player's.
- `InteractionControl.guards(item)`: its `Secondary` reaches a `WieldingInteraction` with
  `AngledWielding` or damage modifiers - a sword's guard, a shield's, a spear's block.
  `useItem(hold)` holds it.
- `InteractionControl.heal(item)`: what a use does for the player's health - its `ApplyEffect`s
  whose effect raises `Health` (once, or every `DamageCalculatorCooldown` for the effect's
  duration, `ActiveEntityEffect`) and its `ChangeStat`s on `Health`; a percentage counted by the
  player's own max. A bread: 5 at once; a health potion: 25 at once and 35 over 5 s.
- `Player.bestWeaponSlot()`: of the hotbar's weapons whose swing hits entities and that are not
  broken (a durability with none left), the one with the highest item level.
- The hand a chain runs with is the server's rule (`InteractionContext.forInteraction`): a shield in
  the utility slot takes a sword's `Secondary`. Core forges with that item once it knows which
  utility slot is active (the server's `SetActiveSlot`, or the player's own chains).

## A repair (`InteractionControl.repair`)

The server's repair is the repair kit (item set `Repair_Kit`). Its use runs `OpenCustomUI` with
the page `ItemRepair`: the server sends the client a page listing the worn items of the hotbar and
the utility slot, most worn first, a button for each (`#ElementList[i]`, data `{"Index":"i"}`).
A press runs `RepairItemInteraction`: one kit used up, the item's durability back to a new maximum,
lowered by the kit's penalty (crude 0.2, iron 0.1), the page closed. No bench repairs.

Core does it as a player does: takes a kit from the hotbar, uses it, waits for that page, takes the
element whose icon is the item, waits until the page is acknowledged, and sends the button's event.
The server takes no press of a page it still waits an acknowledgement for (`PageManager`): a game
acknowledges every page packet itself, and core does it for a headless client (every page packet,
and a `SetPage` while a page is up). A game's player sees the list come up and go.

```
core <conn> repair <item>     -> {repaired}
```

## A chase (`Navigator.chase`)

`chase(target, within)` goes after something that moves. The way is found to where the target is;
while it is gone, core looks where the target is every 100 ms. The moves stop and the way is found
again when the target is more than 1 block (or half of `within`) off the place the way was found to;
the trip is done as soon as the body is within `within` across and no more than `within` (at least
1.5) up or down, wherever along the way that is. A target with no place any more (gone, dead) ends
it `INTERRUPTED`. Replans for a moving target do not count against the trip's tries.

Cancelling the future a trip returns ends that trip only (`goTo` too).

```
nav <conn> chase <entity> [within]   -> {status, at, moves, replans}
```

## Urgent holds (`Movement.takeFirst`)

An urgent hold is a hold on the body for what cannot wait - a fight that comes to a player at work.
While one is held, what core was doing for the other holds waits, without ending:

- the move under way stops (`body.stop()` as the urgent hold is taken);
- a trip of the navigator's begun before waits where the body is; a new trip does not end it. Once
  the last urgent hold is given back it goes on, the way found again from where the body is then;
- the worker's job begun before waits before its next trip, turn of the head or swing; a dig the
  urgent hold broke into is begun again from where the body is (up to 4 times). A worker's job begun
  while the hold is held - the next block of a miner's job - waits as well: the worker's work is
  what an urgent hold interrupts;
- a loop of swings at a block (`breakBlock`) begun before stops at its next swing, and is over once
  the hold is given back (the body will be elsewhere).

Work tells its age by a stamp taken when it begins (`Urgency`); work begun while an urgent hold is
held is the urgent holder's own, and runs. The holds of the work that waits are never given back
for it, so the body does not go to the game in between. `Navigator.cancel()` ends the trips that
wait as well.

```
core <conn> urgent <seconds>   -> an urgent hold for that long; answered when given back
core <conn> hits [seconds] | swings [seconds] | fightitem [item]
core <conn> entities ...       -> each with its health and its held item
```

## Who has the body (`Movement.holds`)

`holds()` is how many holds on the body (`take`) are held now: a trip of the navigator's, the
worker's job, a module's own job (the auto-miner's, the blueprint's). A headless body's holds are
counted too, though its body is core's all along. Left out:

- urgent holds (`takeFirst`);
- the holds taken while an urgent hold is held - the urgent taker's own work, a fighter's chase: by
  the rule urgent holds keep (`Urgency`), work begun under one is its taker's.

So a module that holds nothing itself tells whether another's job has the body (`holds() > 0`) or
the player plays by hand (`0`); and under an urgent hold of its own, whether the job it holds back
is still there to give the body back to - a job stopped meanwhile gives its hold back. A hold is
counted from its take until its taker gives it back or the game takes the body (a key, a teleport,
a death, a world). A core too old to count answers `-1`.

```
core <conn> body               -> ... holds
core <conn> wheel <seconds>    -> an ordinary hold for that long, as a job's; answered when given back
```

## A guard that cuts a swing short (`InteractionControl.useItem(hold, interrupt)`)

The game lets a chain started over a running one cut it short, by the running chain's rules: its
root's, or those of the interaction it stands on (`Rules.InterruptedBy`), unless the new chain's
root carries the bypass tag; or the new root's own `Interrupting` lists the running chain's type.
The sword's swing: its `Charging` and its `Simple` wind-up (`Weapon_Sword_Primary_Swing_Left`, 0.111
s) are `InterruptedBy: [Secondary]`; its selector (the blow) and the pad after it are not. So the
rules let a guard end the swing in its first ~0.13 s (of 0.32); later it waits for the swing.

**The server.** The client sends nothing for the chain it cuts short: it stops sending it and sends
the new chain's first packet. The server, starting the new chain (`InteractionManager.syncStart` →
`applyRules`), checks every chain it still runs: one whose rules let the new type interrupt it it
cancels (`cancelChains`: its states `Failed`, a `CancelInteractionChain` to the client, its forks
with it), and removes it a tick later, its interaction under way handled as failed - a selector not
reached hurts no one. Interruptions are checked before blocking; a chain that does not let itself be
interrupted blocks the new one instead (`Primary` and `Secondary` block each other by default,
`InteractionTypeUtils`): the server queues it until the running chain is over.

**Core.** `useItem(hold, true)` is pressed now: when a chain of core's is in flight and nothing else
waits in the queue, core asks the run's rules where its walk stands - the root it is in after any
`Replace`, the interaction it stands on (`ChainRules`, the server's `validateInterrupts`). Where they
let a `Secondary` in, the run is cut short as the client does it: nothing more of it goes out, it is
done locally at once and the queue starts the use at once. The run waits for the server's cancel
and ends `CANCELLED` (or as the server ran it, had it run on; `TIMED_OUT` after 5 s of silence);
server forks of it are not answered. Where the rules do not let it, the use waits its turn.

**The server's lead.** What counts is where the *server* stands when the use comes in, and it can
stand further on than core's walk: it runs the chain by its own clock from its own tick (30 a
second), shifts a new chain's time by what the chain before ran over, and core's scheduler may run
late. A use that reaches the server past the wind-up is blocked there (`Primary` blocks
`Secondary`), while the swing it should have cut waits on its selector for the client's data that
no longer comes: the server warns ("Client failed to send client data") and cancels it after its
timeout, about 3 s - the guard only then. Seen on a loaded stand: cut 76 ms into a crude sword's
swing, the server at its selector already. So core cuts only with time to spare: the interaction's
time left - by the walk's clock less however far the walk is behind the wall clock, and by the
server's own word on it (its progress, and the time since it said so) - must be more than 60 ms
(`SERVER_LEAD_SECONDS`), and the server must not have said it is past it. For a sword that is the
first ~40 ms of its wind-up; a guard asked for later waits for the swing.

`useItem(hold, false)` - and every other forge - waits: the swing runs to its end and lands, then
the guard goes up. More than the game lets a player do; the server takes it (its queue holds the
guard back only while it still runs the swing).

On the stand: a guard 40 ms into an iron sword's swing at a sheep - the swing `CANCELLED` after 4
operations, no hit, the sheep's health as it was; the guard out 18 ms after the cut (a tick, before
it went out at once). Without the interrupt the swing `FINISHED` with its hit (81 → 71), the guard
after it (340 ms later).

```
core <conn> swingguard <entity> [after <ms>] [hold <s>] [overpower]
    -> {interrupt, swing, guard, healthBefore, healthAfter}
```

## Not yet

- **Only a guard cuts a chain short.** The rules are core's for every forge (`ChainRules`), but only
  `useItem(hold, interrupt)` is pressed "now"; every other forge waits its turn. A press waits as well
  when another forge waits ahead of it, and it is not tried again while it waits: a chain whose rules
  let it in only later in its run is not cut then.
- **A shield in the utility slot for a headless player**: the active utility slot is never sent to
  one, so its forges take the hotbar item.
