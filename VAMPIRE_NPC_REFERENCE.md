# Vampire NPC Reference

This document describes the current in-game implementation of the Blood Moon vampire.

## Core setup

- Enemy type: Citizens NPC (PLAYER type), controlled by Sentinel.
- Damageability: Players can damage the vampire (`npc.setProtected(false)` and entity is set non-invulnerable).
- Nametag visibility: Hidden using Citizens metadata and scoreboard nametag suppression.
- Default skin: the real player skin for `aurinkoari`, applied through the documented Citizens `SkinTrait.setSkinName(...)` path before the NPC spawns.

## Default spawn tuning

- Blood Moon trigger chance: `1 in 24` (`bloodmoon.chance: 24`).
- Vampire max per player: `1` (`vampire.max-per-player: 1`).
- Vampire pulse spawn chance while event is active: `0.18` per pulse attempt.

## State machine

The vampire uses these states:

- `DISGUISED_BAT`
- `STALKING`
- `COMBAT`
- `CASTING`
- `BAT_FORM_ESCAPE`
- `DEAD`

## Ability pool

Weighted combat abilities:

- `BLOOD_MAGIC` (weight 30)
- `DRAIN_LIFE` (weight 18)
- `BAT_FORM_ESCAPE` (weight 8)
- `SUMMON_BATS` (weight 14)
- `SHADOW_DASH` (weight 16)
- `FEAR_SHRIEK` (weight 8)
- `BLOOD_SHIELD` (weight 6)

Cooldown constants (ticks):

- Blood Magic: `80`
- Drain Life: `120`
- Summon Bats: `180`
- Shadow Dash: `300`
- Fear Shriek: `500`
- Bat Form Escape: `360`
- Blood Shield: `1200`
- Blood Shield duration: `100`

## Combat behavior notes

- Melee applies bleed chance logic through the plugin bleed system.
- Blood Shield reduces incoming damage while active.
- Summoned bat swarm cap: `6`.

## Audio feedback

- On hurt: vampire plays a dark humanoid hurt cue (`ENTITY_ZOMBIE_VILLAGER_HURT`).
- On death: wither death layer plus evoker death layer.

## Loot table

On death, drops are rolled from this vanilla-only pool:

- Redstone (`2-5`) at 75%
- Rotten Flesh (`1-3`) at 60%
- Spider Eye (`1-2`) at 45%
- Fermented Spider Eye (`1`) at 30%
- Glass Bottle (`1-2`) at 18%
- Phantom Membrane (`1`) at 10%
- Experience orb: `20-35` XP

## Config path summary

- `bloodmoon.chance`
- `vampire.health`
- `vampire.stalk-ticks-min`
- `vampire.stalk-ticks-max`
- `vampire.spawn-radius`
- `vampire.max-per-player`
- `vampire.skin-name`
- `bleed.*`
