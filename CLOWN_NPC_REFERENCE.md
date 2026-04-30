# Clown NPC Reference

This document describes the current clown implementation added to Blood Moon.

## States

- JUGGLING: Wanders, laughs periodically, emits confetti-like fireworks.
- SNAPPED: Triggered when hit or when players enter snap radius.
- MANIC: Triggered below configured HP threshold; cast interval is faster.
- DEAD: Runs final laugh burst, drops loot, and spawns last balloon bunnies.

## Core behavior

- Snap trigger radius is configurable via `clown.snap-radius`.
- Manic threshold is configurable via `clown.manic-hp-threshold`.
- Manic cooldown pacing is configurable via `clown.manic-cooldown-multiplier`.

## Ability set (chaotic cycle)

- Honk fear pulse (bass horn + nausea pressure).
- Knife fan (multi-arrow burst).
- Twist teleport (blink near target and strike).
- Balloon bunny summon (killer bunnies without visible tags).

## Event integration

- Clowns now spawn during Blood Moon initial wave and pulse wave.
- Spawn radius and cap are independent from vampires.

## Loot

- Dye, slimeballs, rockets, wool, phantom membrane, enchanted book, trident, golden apple, and rare nether star.
- XP: 30-50.

## Config keys

- `clown.health`
- `clown.max-per-player`
- `clown.spawn-radius`
- `clown.snap-radius`
- `clown.manic-hp-threshold`
- `clown.manic-cooldown-multiplier`
- `clown.balloon-cap`
- `clown.twisted-teleport-hops`
- `clown.twisted-teleport-interval-ticks`
- `clown.skin-name`
- `clown.skin-texture`
- `clown.skin-signature`
