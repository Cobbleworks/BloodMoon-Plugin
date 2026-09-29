# Changelog

Notable changes to Blood Moon are documented in GitHub release notes.

## [Unreleased]

## [2.0.0] - 2026-09-30

### Changed

- Replace Citizens and Sentinel with Blockfolk's plugin-owned temporary NPC API for all seven bosses, ghost echoes, and witch mirror clones.
- Preserve encounter controllers, phases, abilities, health, armor reductions, effects, rewards, event scheduling, and admin commands.
- Use Blockfolk native navigation and skin resolution; animate mannequin hands and held items without Citizens reflection.
- Route boss damage and death through Bukkit events, including player projectile damage and existing custom death sequences.
- Require Paper 26.2, Java 25, and Blockfolk 1.3.0 or newer. Upgrade Blockfolk before installing BloodMoon 2.0.

### Removed

- Remove Citizens/Sentinel dependencies, traits, and integration hooks. No saved Blockfolk administrator NPC presets are created by encounters.

## [1.0.0]

- Added the initial Blood Moon event with seven custom NPC encounters.

