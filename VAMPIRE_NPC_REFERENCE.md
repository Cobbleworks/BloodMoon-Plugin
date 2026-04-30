# Vampire NPC Reference

This document describes the current in-game implementation of the Blood Moon vampire.

## Core setup

- Enemy type: Citizens NPC (PLAYER type), controlled by Sentinel.
- Damageability: Players can damage the vampire (`npc.setProtected(false)` and entity is set non-invulnerable).
- Nametag visibility: Hidden using Citizens metadata and scoreboard nametag suppression.
- Bat nametags: Removed entirely from disguise bats, escape bats, and summoned swarm bats.
- Default skin: the fixed historical NameMC skin from `19.12.2025 - 13:35:55`, using NameMC skin id `150ea3e3982a1bd3`.
- Skin source path: `https://s.namemc.com/i/150ea3e3982a1bd3.png`
- Citizens application path: a signed Mineskin-generated `texture` and `signature` are stored in config and applied through the documented `SkinTrait.setSkinPersistent(...)` path before spawn.

## Default spawn tuning

- Blood Moon trigger chance: `1 in 24` (`bloodmoon.chance: 24`).
- Vampire max per player: `1` (`vampire.max-per-player: 1`).
- Vampire pulse spawn chance while event is active: `0.18` per pulse attempt.
- Event spawns now require a farther valid position (`>= 24` blocks from the checked player), and no close fallback spawn is used.
- Event-spawned vampires start without a forced initial target, so they stalk first instead of hard-snapping into immediate combat.

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
- `HEMOPLAGUE` (weight 10)
- `BAT_FORM_ESCAPE` (weight 8)
- `SUMMON_BATS` (weight 14)
- `SHADOW_DASH` (weight 16)
- `TIDES_OF_BLOOD` (weight 12)
- `BLOOD_SHIELD` (weight 6)

Cooldown constants (ticks):

- Blood Magic: `80`
- Drain Life: `180`
- Hemoplague: `260`
- Summon Bats: `180`
- Shadow Dash: `300`
- Tides of Blood: `420`
- Bat Form Escape: `360`
- Blood Shield: `1200`
- Blood Shield duration: `100`

## Combat behavior notes

- Melee applies bleed chance logic through the plugin bleed system.
- Drain Life is empowered below `30%` HP (`x1.55` drain damage).
- Hemoplague now heals for `100%` of explosion damage dealt.
- Tides of Blood replaced the old fear/wither-style spell.
- Tides of Blood behavior: charges a blood orb above the vampire, then fires radial blood bolts in waves up to `7` blocks.
- Blood Shield reduces incoming damage while active.
- Summoned bat swarm cap: `6`.
- Swarm bats now target nearby living entities (players and non-BloodMoon NPCs), not only players.
- Vampire and tracked BloodMoon bats ignore fall damage.
- Casting includes visible hand usage/arm swings so spell prep reads clearly as a mini-boss cast.

## Audio feedback

- On hurt: vampire plays a dark humanoid hurt cue (`ENTITY_ZOMBIE_VILLAGER_HURT`).
- On death: wither death layer plus evoker death layer.

## Loot table

On death, drops are rolled from this vanilla-only pool:

- Redstone (`2-5`) at 75%
- Bone (`1-4`) at 55%
- Rotten Flesh (`1-3`) at 60%
- Spider Eye (`1-2`) at 45%
- Fermented Spider Eye (`1`) at 30%
- Glass Bottle (`1-2`) at 18%
- Phantom Membrane (`1`) at 10%
- Arrow of Healing (`2-4`) at 14%
- Arrow of Harming (`1-2`) at 12%
- Splash Potion of Healing or Regeneration at 10%
- Enchanted Book reward at 8%
- Enchanted Iron or Diamond Sword reward at 5%
- Golden Apple at 6%
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
