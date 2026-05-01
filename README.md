<p align="center">
	<img src="images/plugin-logo.png" alt="BloodMoon Plugin" width="180" />
</p>
<h1 align="center">BloodMoon Event Plugin</h1>
<p align="center">
	<b>Trigger cinematic Blood Moon nights with custom boss-style NPC encounters.</b><br>
	<b>Citizens and Sentinel powered monsters, admin controls, themed loot, and per-player spawn pressure.</b>
</p>
<p align="center">
	<a href="https://github.com/Cobbleworks/BloodMoon-Plugin/releases"><img src="https://img.shields.io/github/v/release/Cobbleworks/BloodMoon-Plugin?include_prereleases&style=flat-square&color=4CAF50" alt="Latest Release"></a>&nbsp;&nbsp;<a href="https://github.com/Cobbleworks/BloodMoon-Plugin/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License"></a>&nbsp;&nbsp;<img src="https://img.shields.io/badge/Java-17+-orange?style=flat-square" alt="Java Version">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Minecraft-1.20+-green?style=flat-square" alt="Minecraft Version">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Platform-Spigot%2FPaper-yellow?style=flat-square" alt="Platform">&nbsp;&nbsp;<img src="https://img.shields.io/badge/Dependencies-Citizens%20%7C%20Sentinel-red?style=flat-square" alt="Dependencies">&nbsp;&nbsp;<a href="https://github.com/Cobbleworks/BloodMoon-Plugin/issues"><img src="https://img.shields.io/github/issues/Cobbleworks/BloodMoon-Plugin?style=flat-square&color=orange" alt="Open Issues"></a>
</p>

BloodMoon Event is an open-source Minecraft plugin that turns ordinary nights into high-pressure Blood Moon events with custom hostile NPCs, stronger atmosphere, and configurable world targeting. When the event triggers, the plugin announces the Blood Moon, drives a thunderstorm-based encounter window, and spawns Citizens-backed monsters around players with per-player caps and themed combat kits.

The current Blood Moon roster includes a stalking vampire, a chaotic carnival clown, a plague zombie, a ritual witch, a harvest scarecrow, a possession-driven ghost, and a blood-frenzied werewolf. Each enemy has its own skin, combat loop, ability set, death presentation, and themed loot table. Administrative controls are exposed through a single `/bloodmoon` command with support for starting, stopping, checking status, spawning individual enemies, clearing active encounters, and temporarily overriding the Blood Moon chance.

### **Core Features**

- **Random Blood Moon Event:** Rolls a configurable chance each night and starts the event only in enabled worlds
- **World-Aware Activation:** Restrict the system to selected worlds through `config.yml`
- **Custom Citizens NPC Enemies:** Uses `Citizens` and `Sentinel` for custom pathing, targeting, skins, and combat integration
- **Seven Named Blood Moon Monsters:** Vampire, Clown, Zombie, Witch, Scarecrow, Ghost, and Werewolf are all supported and spawn through the same event framework
- **Boss-Style Ability Kits:** Every NPC has multiple active abilities, passives, movement effects, and a unique death sequence
- **Signed Skin Support:** All custom NPCs use real configured skin names plus signed texture/signature data for stable appearance
- **Per-Player Spawn Pressure:** Each enemy type respects a per-player nearby cap instead of uncontrolled global spam
- **Admin Spawn and Cleanup Commands:** Spawn any supported Blood Moon monster near a chosen player, clear worlds, or force the event on and off
- **Special-Mob Health Bar Toggle:** Players can enable or disable a nearest special-mob health bar through `/bloodmoon healthbar`
- **Themed Loot Pools:** Every Blood Moon NPC now has expanded basic and rare drop possibilities tailored to its theme

### **Supported Platforms**

- **Server Software:** `Spigot`, `Paper`, `Purpur`, `CraftBukkit`
- **Minecraft Versions:** `1.20` and higher
- **Java Requirements:** `Java 17+`
- **Dependencies:** `Citizens2` and `Sentinel`

## **Table of Contents**

1. [Getting Started](#getting-started)
2. [Blood Moon Roster](#blood-moon-roster)
3. [Configuration](#configuration)
4. [Admin Commands](#admin-commands)
5. [Permissions](#permissions)
6. [Building from Source](#building-from-source)
7. [License](#license)
8. [Screenshots](#screenshots)

## **Getting Started**

### **Prerequisites**

Before installing BloodMoon Event, confirm the following requirements are met:

- A Minecraft server running **Spigot**, **Paper**, **Purpur**, or any compatible fork
- Server version **1.20 or higher**
- **Java 17** or newer installed on the machine running the server
- The **Citizens2** and **Sentinel** plugins already installed and loading correctly
- Operator or console access to install plugin files and run admin commands

### **Installation Steps**

1. Download the latest `BloodMoon-Event-x.x.x.jar` from the [Releases](https://github.com/Cobbleworks/BloodMoon-Plugin/releases) page
2. **Stop your server completely** before placing any plugin files
3. Make sure `Citizens` and `Sentinel` are already present in your server's `plugins/` directory
4. Copy the BloodMoon JAR into the same `plugins/` directory
5. Start the server and confirm all three plugins enable successfully

### **First Launch & Configuration**

On first startup, BloodMoon Event creates `plugins/BloodMoon-Event/config.yml`.

The most important configuration sections are:

- `bloodmoon.chance` - how often the event can trigger
- `bloodmoon.worlds` - which worlds are allowed to roll Blood Moon events
- Per-mob sections such as `vampire`, `clown`, `zombie`, `witch`, `scarecrow`, `ghost`, and `werewolf`
- Shared effect sections such as `bleed` and `messages`

Example high-level configuration structure:

```yaml
bloodmoon:
	chance: 24
	worlds:
		- world

vampire:
	health: 40.0
	spawn-radius: 48
	max-per-player: 1

ghost:
	health: 42.0
	spawn-radius: 52
	max-per-player: 1

werewolf:
	health: 58.0
	spawn-radius: 52
	max-per-player: 1
```

After editing the config, reload or restart the server. `/bloodmoon reload` refreshes configuration values without requiring a full server restart.

### **Verifying Installation**

- Run `/plugins` and confirm `Citizens`, `Sentinel`, and `BloodMoon-Event` appear green
- Run `/version BloodMoon-Event` to verify the installed version
- Run `/bloodmoon status` from console or in-game with admin permission
- Run `/bloodmoon spawn vampire <player>` to confirm Citizens spawning works
- If the plugin fails to load, check the console for dependency, API version, or skin application warnings

## **Blood Moon Roster**

BloodMoon Event currently ships with seven custom encounter types:

| NPC | Theme | Combat Identity |
|-----|-------|-----------------|
| `Vampire` | Nocturnal predator | Stalking pressure, blood magic, life drain, bat synergy |
| `Clown` | Carnival horror | Trick movement, ranged chaos, disruption, spectacle-heavy attacks |
| `Zombie` | Plague brute | Infection spread, horde pressure, lingering damage, undead attrition |
| `Witch` | Ritual caster | Curses, potion pressure, clones, rune-based area denial |
| `Scarecrow` | Harvest horror | Fear, plant corruption, rooted life drain, crow swarms |
| `Ghost` | Possession horror | Phase movement, untargetable windows, control inversion, haunted pressure |
| `Werewolf` | Blood frenzy hunter | Bleed, pounce mobility, pack summons, territory control, lunar rage |

Each NPC uses its own configured health, spawn radius, max-per-player cap, and signed skin data. Death sequences and loot are handled independently per NPC so every encounter feels distinct.

### **Notable Encounter Behaviors**

- **Vampire:** Uses missing-health regeneration scaling, low-health Hemoplague pressure, and an execution dash that can finish low-HP targets
- **Clown:** Theatrical trickster kit with fake-death baiting, confetti and juggle pressure, balloon-glide repositioning, and crowd-control pranks
- **Witch:** Multi-phase ritual fight that escalates from composed control into Wrath and Unraveling, with per-ability casting tells and situational spell choices

## **Configuration**

BloodMoon Event is designed so the core behavior can be tuned from `config.yml` without editing code.

### **What You Can Configure**

- Global Blood Moon trigger chance
- Enabled worlds for event activation
- Per-mob health values
- Per-mob spawn radii
- Per-mob max-per-player caps
- Skin name, texture, and signature for each custom NPC
- Shared bleed timing and damage values
- Start and end broadcast messages

### **Operational Notes**

- The plugin depends on **Citizens** and **Sentinel** being loaded first
- Spawn commands use the target player's surroundings and can fail if there is no safe valid spawn location nearby
- Custom NPC logic is implemented in code, so advanced behavior tuning still requires source edits unless additional config knobs are added later

## **Admin Commands**

All primary administration is handled through `/bloodmoon` with the alias `/bm`.

### **Command Reference**

| Command | Description |
|---------|-------------|
| `/bloodmoon start [world]` | Force-start a Blood Moon in a world |
| `/bloodmoon stop [world]` | Force-stop the Blood Moon in a world |
| `/bloodmoon status` | Show event state, active worlds, chance, and active NPC counts |
| `/bloodmoon spawn <type> <player>` | Spawn a custom Blood Moon NPC near a player |
| `/bloodmoon clear [world]` | Remove active Blood Moon NPCs from one world or all worlds |
| `/bloodmoon reload` | Reload `config.yml` values |
| `/bloodmoon chance <1-100>` | Set a temporary Blood Moon chance override |
| `/bloodmoon healthbar [on|off|toggle]` | Toggle the nearest special-mob health bar for a player |

### **Supported Spawn Types**

`vampire`, `clown`, `zombie`, `witch`, `scarecrow`, `ghost`, `werewolf`

### **Example Commands**

```text
/bloodmoon start world
/bloodmoon status
/bloodmoon spawn scarecrow Bernd
/bloodmoon spawn werewolf Bernd
/bloodmoon clear world_nether
/bloodmoon reload
/bloodmoon chance 12
/bloodmoon healthbar toggle
```

## **Permissions**

| Permission | Description | Default |
|------------|-------------|---------|
| `bloodmoon.admin` | Full Blood Moon administration, including start/stop/spawn/clear/reload/chance | `op` |
| `bloodmoon.healthbar` | Allows players to toggle the nearest special-mob health bar | `true` |
| `bloodmoon.notify` | Receive Blood Moon notifications | `true` |

## **Building from Source**

BloodMoon Event uses **Apache Maven**.

**Requirements:**

- Java 17 or newer
- Apache Maven 3.6 or newer

**Steps:**

```bash
git clone https://github.com/Cobbleworks/BloodMoon-Plugin.git
cd BloodMoon-Plugin
mvn clean package
```

The output JAR is written to `target/BloodMoon-Event-1.0.0-SNAPSHOT.jar`.

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
