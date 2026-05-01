<p align="center">
  <img src="images/plugin-logo.png" alt="BloodMoon Plugin" width="180" />
</p>
<h1 align="center">BloodMoon Event Plugin</h1>
<p align="center">
  <b>Turn normal nights into fully scripted Blood Moon boss encounters.</b><br>
  <b>Seven custom Citizens + Sentinel enemies, cinematic event flow, and deep admin control.</b>
</p>
<p align="center">
  <a href="https://github.com/Cobbleworks/BloodMoon-Plugin/releases"><img src="https://img.shields.io/github/v/release/Cobbleworks/BloodMoon-Plugin?include_prereleases&style=flat-square&color=4CAF50" alt="Latest Release"></a>&nbsp;&nbsp;<a href="https://github.com/Cobbleworks/BloodMoon-Plugin/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License"></a>&nbsp;&nbsp;<img src="https://img.shields.io/badge/Java-17+-orange?style=flat-square" alt="Java Version">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Minecraft-1.20+-green?style=flat-square" alt="Minecraft Version">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Platform-Spigot%2FPaper-yellow?style=flat-square" alt="Platform">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Dependencies-Citizens%20%7C%20Sentinel-red?style=flat-square" alt="Dependencies">&nbsp;&nbsp;<a href="https://github.com/Cobbleworks/BloodMoon-Plugin/issues"><img src="https://img.shields.io/github/issues/Cobbleworks/BloodMoon-Plugin?style=flat-square&color=orange" alt="Open Issues"></a>
</p>

BloodMoon Event is a large-scale encounter framework for Minecraft servers that combines event scheduling, weather control, spawn orchestration, custom NPC AI, and themed loot into one plugin.

When a Blood Moon starts, the plugin activates a stormy event window and applies continuous encounter pressure around active players. Each monster type has its own AI controller, phase logic, ability system, movement identity, and death sequence. The result is not vanilla mob spam, but a curated boss-style night cycle.

### **Core Features**

- **Night-Roll Event System:** Per-night chance-based trigger in configured worlds only
- **Seven Custom Boss Mobs:** Vampire, Clown, Zombie, Witch, Scarecrow, Ghost, and Werewolf
- **Citizens + Sentinel Integration:** Stable NPC ownership, skin control, pathing, and combat targeting
- **Per-Player Spawn Caps:** Spawn pressure scales per player, avoiding uncontrolled global overflow
- **Difficulty Profiles:** `easy`, `medium`, `hard`, and `nightmare` adjust non-vampire HP, reward, EXP, and ability cadence multipliers
- **Admin Control Surface:** Full lifecycle and spawn management through `/bloodmoon` and `/bm`
- **Nearest Special-Mob Health Bar:** Toggleable segmented health bar for nearby active Blood Moon targets
- **Themed Drops + Death Sequences:** Every encounter type has independent loot and death presentation
- **Signed Skin Support:** Stable texture/signature skin assignment for all custom NPC types

### **Supported Platforms**

- **Server Software:** `Spigot`, `Paper`, `Purpur`, `CraftBukkit`
- **Minecraft Versions:** `1.20+`
- **Java Requirements:** `Java 17+`
- **Required Dependencies:** `Citizens2`, `Sentinel`

## **Table of Contents**

1. [Getting Started](#getting-started)
   - [Prerequisites](#prerequisites)
   - [Installation Steps](#installation-steps)
   - [First Launch](#first-launch)
   - [Verification Checklist](#verification-checklist)
2. [Event Architecture](#event-architecture)
3. [Blood Moon Roster](#blood-moon-roster)
4. [Configuration](#configuration)
   - [Global Event Settings](#global-event-settings)
   - [Difficulty Profiles](#difficulty-profiles)
   - [Per-Mob Settings](#per-mob-settings)
   - [Status Effects and Messages](#status-effects-and-messages)
5. [Commands](#commands)
6. [Permissions](#permissions)
7. [Building from Source](#building-from-source)
8. [License](#license)
9. [Screenshots](#screenshots)

## **Getting Started**

### **Prerequisites**

Before installing BloodMoon Event, confirm:

- Your server runs **Spigot/Paper/Purpur/CraftBukkit**
- Minecraft version is **1.20 or newer**
- Java runtime is **17+**
- `Citizens` and `Sentinel` are installed and loading correctly
- You have console or operator access for setup and command testing

### **Installation Steps**

1. Download the latest release JAR from [Releases](https://github.com/Cobbleworks/BloodMoon-Plugin/releases)
2. Stop the server fully
3. Place `Citizens` and `Sentinel` in `plugins/` if not already installed
4. Place BloodMoon JAR in `plugins/`
5. Start server and verify all required plugins are green in `/plugins`

### **First Launch**

On first startup, the plugin creates:

```text
plugins/
+-- BloodMoon-Event/
    +-- config.yml
```

Primary sections in `config.yml`:

- `bloodmoon` -> chance + enabled worlds
- Per-mob blocks -> `vampire`, `clown`, `zombie`, `witch`, `scarecrow`, `ghost`, `werewolf`
- Shared effects -> `bleed`
- Event text -> `messages`

Minimal structure example:

```yaml
bloodmoon:
  chance: 24
  worlds:
    - world

vampire:
  health: 40.0
  spawn-radius: 48
  max-per-player: 1

zombie:
  health: 50.0
  spawn-radius: 48
  max-per-player: 1
```

Use `/bloodmoon reload` after editing configuration.

### **Verification Checklist**

- `/version BloodMoon-Event` returns plugin version
- `/bloodmoon status` returns event and active world details
- `/bloodmoon spawn vampire <player>` works
- `/bloodmoon spawn ghost <player>` works
- `/bloodmoon healthbar` returns overhead health-bar status

## **Event Architecture**

BloodMoon Event is split into coordinated systems:

- **BloodMoonManager:** Event lifecycle, world activation, storm control, spawn pulses, difficulty profile behavior
- **NPCManager:** Citizens NPC creation/destruction, active-controller tracking, per-type lookup/count APIs
- **Mob Controllers (`*NPC.java`):** Ability loops, state machines, targeting logic, phase behavior, death handling
- **Traits + Listeners:** Citizens trait bridges and runtime event hooks for combat, effects, and interaction safety

Spawn flow at runtime:

1. Night transition is detected for configured worlds
2. Chance roll passes -> Blood Moon starts
3. Weather and messaging are applied
4. Initial spawn pass and recurring spawn pulses begin
5. Per-player nearby caps gate each monster type
6. Sunrise or forced stop ends the event and cleans all managed entities

## **Blood Moon Roster**

| NPC | Identity | Highlights |
|-----|----------|------------|
| `Vampire` | Stalking blood mage | Bat disguise openers, blood projectiles, drain windows, execute pressure |
| `Clown` | Chaos trickster | Telegraphed pranks, anvil drops, bunny assaults, movement misdirection |
| `Zombie` | Pest-bringer brute | Infection mechanics, rot zones, hunger pressure, corrosive attacks |
| `Witch` | Ritual phase caster | Signature spell kit, hex control, mirror pressure, escalation phases |
| `Scarecrow` | Harvest nightmare | Fear control, drain channels, dark wind, swarm pressure, crop-based scaling |
| `Ghost` | Haunting control predator | Stalking cadence, item snatch pressure, paranormal control windows |
| `Werewolf` | Territorial frenzy hunter | Pounce mobility, bleed/infection pressure, pack tools, surge windows |

## **Configuration**

### **Global Event Settings**

- `bloodmoon.chance` -> night roll chance denominator (1 in N)
- `bloodmoon.worlds` -> worlds where Blood Moon can naturally trigger

### **Difficulty Profiles**

Set using command: `/bloodmoon difficulty <easy|medium|hard|nightmare>`

Each profile adjusts:

- Non-vampire health multiplier
- Reward multiplier
- EXP multiplier
- Ability cadence multiplier

### **Per-Mob Settings**

Every mob section supports:

- `health`
- `spawn-radius`
- `max-per-player`
- skin keys (`skin-name`, `skin-texture`, `skin-signature`)

Additional examples by type:

- `vampire.stalk-ticks-min`, `vampire.stalk-ticks-max`
- `clown.snap-radius`, `clown.manic-hp-threshold`, `clown.manic-cooldown-multiplier`
- `zombie.infection-*`, `zombie.horde-call-radius`, `zombie.plague-burst-radius`

### **Status Effects and Messages**

- `bleed.chance`
- `bleed.damage-per-tick`
- `bleed.interval-ticks`
- `bleed.max-stacks`
- `messages.event-start`
- `messages.event-end`

## **Commands**

All administration is on `/bloodmoon` (alias: `/bm`).

| Command | Description |
|---------|-------------|
| `/bloodmoon start [world]` | Force-start Blood Moon in a world |
| `/bloodmoon stop [world]` | Force-stop Blood Moon in a world |
| `/bloodmoon status` | Show active state, worlds, counts, and chance info |
| `/bloodmoon spawn <type> <player>` | Spawn one configured Blood Moon mob near player |
| `/bloodmoon clear [world]` | Remove active Blood Moon mobs in one/all worlds |
| `/bloodmoon reload` | Reload config file |
| `/bloodmoon chance <1-100>` | Temporary chance override |
| `/bloodmoon difficulty <easy|medium|hard|nightmare>` | Runtime difficulty profile switch |
| `/bloodmoon healthbar` | Show info about always-on overhead segmented health bars |

Supported `<type>` values:

`vampire`, `clown`, `zombie`, `witch`, `scarecrow`, `ghost`, `werewolf`

## **Permissions**

| Permission | Description | Default |
|------------|-------------|---------|
| `bloodmoon.admin` | Full event administration and spawn control | `op` |
| `bloodmoon.healthbar` | Access overhead special-mob health bar information command | `true` |
| `bloodmoon.notify` | Receive Blood Moon event notifications | `true` |

## **Building from Source**

Requirements:

- Java 17+
- Maven 3.6+

Build:

```bash
git clone https://github.com/Cobbleworks/BloodMoon-Plugin.git
cd BloodMoon-Plugin
mvn clean package
```

Output:

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
