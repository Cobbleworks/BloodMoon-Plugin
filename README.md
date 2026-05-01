<p align="center">
  <img src="images/plugin-logo.png" alt="BloodMoon Plugin" width="180" />
</p>
<h1 align="center">BloodMoon Event Plugin</h1>
<p align="center">
  <b>A full encounter framework for Blood Moon nights, built around custom NPC bosses.</b><br>
  <b>Seven unique special mobs, configurable event flow, deep admin tooling, and Citizens/Sentinel integration.</b>
</p>
<p align="center">
  <a href="https://github.com/Cobbleworks/BloodMoon-Plugin/releases"><img src="https://img.shields.io/github/v/release/Cobbleworks/BloodMoon-Plugin?include_prereleases&style=flat-square&color=4CAF50" alt="Latest Release"></a>&nbsp;&nbsp;<a href="https://github.com/Cobbleworks/BloodMoon-Plugin/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License"></a>&nbsp;&nbsp;<img src="https://img.shields.io/badge/Java-17+-orange?style=flat-square" alt="Java Version">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Minecraft-1.20+-green?style=flat-square" alt="Minecraft Version">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Platform-Spigot%2FPaper-yellow?style=flat-square" alt="Platform">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Dependencies-Citizens%20%7C%20Sentinel-red?style=flat-square" alt="Dependencies">
</p>

BloodMoon Event is designed as a complete server-side encounter system, not a single scripted mob. It includes event scheduling, world-scoped activation, per-player spawn pressure, configurable balancing knobs, and fully custom NPC combat controllers.

During a Blood Moon, normal vanilla world behavior can continue, while the plugin injects high-pressure custom encounters with seven themed special mobs:

- Vampire
- Clown
- Zombie
- Witch
- Scarecrow
- Ghost
- Werewolf

## **Table of Contents**

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [How The Event Works](#how-the-event-works)
4. [Special NPC Documentation](#special-npc-documentation)
5. [Health Bar System](#health-bar-system)
6. [Configuration Reference](#configuration-reference)
7. [Commands](#commands)
8. [Permissions](#permissions)
9. [Performance Notes](#performance-notes)
10. [Building From Source](#building-from-source)
11. [License](#license)
12. [Screenshots](#screenshots)

## **Requirements**

- Minecraft server: Spigot / Paper / Purpur / CraftBukkit
- Minecraft version: 1.20+
- Java: 17+
- Required dependencies:
  - Citizens
  - Sentinel

## **Installation**

1. Download latest plugin release from the GitHub Releases page.
2. Stop your server.
3. Ensure Citizens and Sentinel are installed in the plugins folder.
4. Place BloodMoon Event jar in plugins.
5. Start server and confirm all dependencies load.
6. Run `/bloodmoon status` to verify command/manager bootstrap.

## **How The Event Works**

### **Lifecycle**

- The manager monitors night transition windows.
- In configured worlds, Blood Moon rolls based on `bloodmoon.chance`.
- On start, Blood Moon event state is activated for that world.
- Initial and pulse-based spawn passes place special NPCs around players.
- Per-player local caps prevent uncontrolled overlap.
- On sunrise or forced stop, active special mobs are cleaned up.

### **Spawn Philosophy**

- Special mobs are plugin-driven encounters.
- Spawning is per-player-pressure based, not one global mob blob.
- Each special type has independent cap/radius/health controls.

## **Special NPC Documentation**

This section documents the current controller-level behavior and ability kits of each BloodMoon special NPC.

### **Vampire**

**Role:** adaptive nocturnal blood mage with sustain and execution pressure.

**State model:**
- DISGUISED_BAT
- STALKING
- COMBAT
- CASTING
- BAT_FORM_ESCAPE
- DEAD

**Ability set:**
- BLOOD_MAGIC
- DRAIN_LIFE
- HEMOPLAGUE
- BAT_FORM_ESCAPE
- SUMMON_BATS
- SHADOW_DASH
- EXECUTION_DASH
- TIDES_OF_BLOOD
- BLOOD_SHIELD

**Behavior highlights:**
- Can approach while disguised and transition into full combat.
- Blood projectile pressure is now slower, delayed, and readable.
- Blood hits can restore vampire health.
- Uses mobility and transform windows to reset engagement distance.

### **Clown**

**Role:** chaotic trickster with burst casts, displacement, and prank windows.

**State model:**
- WANDERING
- COMBAT
- CASTING
- TAUNTING
- MANIC
- DEAD

**Ability set:**
- FIREWORK_VOLLEY
- BUNNY_SWARM
- CONFETTI_CANNON
- WIND_BURST
- CHAOS_DASH
- PARROT_BARRAGE
- DUCK_INFERNO
- JUGGLE
- ANVIL_DROP

**Behavior highlights:**
- Manic phase shifts cadence and aggression.
- Bunny swarm now follows sky-orbit then dive attack sequencing.
- Anvil mechanic includes visible falling anvil and temporary landed block.
- Includes prank subsystem (shuffle/fake death/reveal/freeze/bait trap).

### **Zombie**

**Role:** infection attrition bruiser with zone pressure and corruption effects.

**State model:**
- INFECTED_RAGE
- COMBAT
- CASTING
- DEAD

**Ability set:**
- ACID_SPIT
- ROT_ZONE
- POWER_LEAP
- CHARGE_LEAP

**Behavior highlights:**
- Built around decay pressure and sustained debuffing.
- Infection and acid systems apply ongoing player penalties.
- Includes trail and zone pressure that reward repositioning.

### **Witch**

**Role:** phase-based ritual caster with control, curse, and spell-combo pressure.

**State model:**
- COMBAT
- CASTING
- DEAD

**Phase model:**
- COMPOSED
- WRATH
- UNRAVELING

**Ability set:**
- Signature: SHARED_VESSEL, DEADLY_SPELL, HEX_CIRCLE, MIRROR_IMAGE
- Control: ARMOR_CURSE, FREEZING_SPELL, VOID_CAGE, CURSE_OF_SILENCE, SWITCHING_SPELL, INVENTORY_SPELL
- Damage: LIGHTNING_MARK, FIRE_SPELL, WILL_O_WISP, RAPID_FIRE, LIFE_DRAIN
- Utility/Summon: POTION_VOLLEY, RUNE_TRAPS, ZOMBIFYING

**Behavior highlights:**
- Cast pacing includes telegraph windows and recovery timing.
- Circle and lock mechanics were tuned for improved fairness.
- Cleanup path now force-clears lingering player-lock metadata on death.

### **Scarecrow**

**Role:** harvest-horror controller with fear, drain, swarm, and area pressure.

**State model:**
- COMBAT
- CASTING
- DEAD

**Ability set:**
- FEAR
- DRAIN
- BLOOM
- REAP
- FIREBALLS
- PHANTOM
- CROWSTORM
- DARK_WIND
- HIGH_JUMP

**Behavior highlights:**
- Crowstorm is implemented as scattered bat swarm pressure.
- Drain visuals were redesigned to reduce heavy lag signatures.
- Fear and crowd-control windows were expanded for stronger identity.

### **Ghost**

**Role:** disruption and control encounter focused on movement mindgames and item pressure.

**State model:**
- STALKING
- RUSHING
- DEAD

**Ability set:**
- MIND_CONTROL
- PARANORMAL_ACTIVITY
- ECHO
- POLTERGEIST_THROW

**Behavior highlights:**
- Reworked for stronger counterplay and vulnerability windows.
- Item snatch is interruptible during windup.
- Reduced evade uptime and shortened phase-walk behavior.
- Torch/lantern reveal rule: in torch/soul-torch/lantern/soul-lantern light range, ghost is forced visible until leaving the lit zone.

### **Werewolf**

**Role:** melee predator with bleed/infection pressure, pack mechanics, and surges.

**State model:**
- COMBAT
- CASTING
- DEAD

**Ability set:**
- BITE
- FURIOUS_CLAWS
- FAR_JUMP
- WOLF_PACK
- DEVOUR
- TERRITORIAL_SNARL
- PACK_FRENZY
- BONE_SLAM

**Behavior highlights:**
- Uses mobility + burst windows to collapse distance.
- Applies persistent pressure through bleed/infection systems.
- Territorial behavior affects chase and engagement rhythm.

## **Health Bar System**

BloodMoon special mobs use overhead segmented bars (name tag style) above active entities.

Format example:

`[¦¦¦¦¦¦¦¦??]`

Current behavior:

- No BossBar UI.
- No numeric health values in the overhead text.
- Bars follow active special-mob carriers (including vampire transform forms).

Player command:

- `/bloodmoon healthbar` -> shows status info for overhead health-bar system.

## **Configuration Reference**

Configuration file is generated at:

`plugins/BloodMoon-Event/config.yml`

### **Global Keys**

- `bloodmoon.chance`
- `bloodmoon.worlds`
- `messages.event-start`
- `messages.event-end`

### **Difficulty Profile Runtime Control**

Set in-game via command:

- `/bloodmoon difficulty easy`
- `/bloodmoon difficulty medium`
- `/bloodmoon difficulty hard`
- `/bloodmoon difficulty nightmare`

Profile adjusts:

- Non-vampire health multiplier
- Reward multiplier
- EXP multiplier
- Ability cadence multiplier

### **Shared Effect Keys**

- `bleed.chance`
- `bleed.damage-per-tick`
- `bleed.interval-ticks`
- `bleed.max-stacks`

### **Per-Mob Key Families**

For each of:

- `vampire`
- `clown`
- `zombie`
- `witch`
- `scarecrow`
- `ghost`
- `werewolf`

You can configure:

- `health`
- `spawn-radius`
- `max-per-player`
- `skin-name`
- `skin-texture`
- `skin-signature`

Additional per-mob specialty keys exist for specific systems (examples):

- Vampire stalk timing
- Clown mania and teleport tuning
- Zombie infection/plague tuning

## **Commands**

| Command | Description |
|---------|-------------|
| `/bloodmoon start [world]` | Force-start Blood Moon in target world |
| `/bloodmoon stop [world]` | Stop Blood Moon in target world |
| `/bloodmoon status` | Print manager state, active counts, and configuration context |
| `/bloodmoon spawn <type> <player>` | Spawn one special BloodMoon NPC near player |
| `/bloodmoon clear [world]` | Cleanup active BloodMoon enemies |
| `/bloodmoon reload` | Reload plugin configuration |
| `/bloodmoon chance <1-100>` | Set temporary Blood Moon chance override |
| `/bloodmoon difficulty <easy|medium|hard|nightmare>` | Switch runtime difficulty profile |
| `/bloodmoon healthbar` | Show overhead health-bar status |

Spawn `<type>` values:

- vampire
- clown
- zombie
- witch
- scarecrow
- ghost
- werewolf

## **Permissions**

| Permission | Description | Default |
|------------|-------------|---------|
| `bloodmoon.admin` | Full BloodMoon administration and spawn control | `op` |
| `bloodmoon.healthbar` | Access health-bar status command | `true` |
| `bloodmoon.notify` | Receive BloodMoon notifications | `true` |

## **Performance Notes**

Recent optimization passes include:

- Reduction of high-density particle bursts in heavy spell effects.
- Lower-cost particle profiles for major Clown, Witch, and Zombie spikes.
- Ghost behavior cleanup and reveal rules to reduce prolonged invisible loops.

For higher population servers:

- Keep per-player max caps conservative.
- Avoid over-aggressive chance values.
- Profile TPS during peak event windows after each config change.

## **Building From Source**

Requirements:

- Java 17+
- Maven 3.6+

Build commands:

```bash
git clone https://github.com/Cobbleworks/BloodMoon-Plugin.git
cd BloodMoon-Plugin
mvn clean package
```

Build artifact:

`target/BloodMoon-Event-1.0.0-SNAPSHOT.jar`

## **License**

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

## **Screenshots**

| BloodMoon - Night Start | Vampire - Blood Hunt |
|-------------------------|----------------------|
| [Placeholder] | [Placeholder] |

| Scarecrow - Harvest Drain | Ghost - Possession |
|---------------------------|--------------------|
| [Placeholder] | [Placeholder] |

| Werewolf - Pack Frenzy | Witch - Ritual Casting |
|------------------------|-----------------------|
| [Placeholder] | [Placeholder] |
