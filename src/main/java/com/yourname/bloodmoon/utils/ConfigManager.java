package com.yourname.bloodmoon.utils;

import com.yourname.bloodmoon.BloodMoonPlugin;
import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Owns typed access to BloodMoon configuration values.
 */
public final class ConfigManager {

    private final BloodMoonPlugin plugin;

    public ConfigManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Saves and normalizes the plugin configuration.
     */
    public void load() {
        FileConfiguration config = plugin.getConfig();
        config.options().copyDefaults(true);
        addDefaults(config);
        plugin.saveDefaultConfig();
        plugin.saveConfig();
    }

    /**
     * Reloads the configuration from disk and reapplies missing defaults.
     */
    public void reload() {
        plugin.reloadConfig();
        addDefaults(plugin.getConfig());
        plugin.saveConfig();
    }

    /**
     * Returns the current configuration object.
     *
     * @return Bukkit configuration
     */
    public FileConfiguration getConfig() {
        return plugin.getConfig();
    }

    public int getBloodMoonChance() {
        return Math.max(1, getConfig().getInt("bloodmoon.chance", 12));
    }

    public List<String> getEnabledWorlds() {
        return getConfig().getStringList("bloodmoon.worlds");
    }

    public boolean areSpecialMonstersEnabled() {
        return getConfig().getBoolean("bloodmoon.special-monsters.enabled", true);
    }

    public int getSpecialMonsterSpawnRadius() {
        return Math.max(8, getConfig().getInt("bloodmoon.special-monsters.spawn-radius", 48));
    }

    public int getSpecialMonstersMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("bloodmoon.special-monsters.max-per-player", 2));
    }

    public long getSpecialMonsterSpawnPulseTicks() {
        return Math.max(20L, getConfig().getLong("bloodmoon.special-monsters.spawn-pulse-ticks", 120L));
    }

    public double getSpecialMonsterSpawnChance() {
        return Math.max(0.0D, Math.min(1.0D, getConfig().getDouble("bloodmoon.special-monsters.spawn-chance", 0.55D)));
    }

    public double getVampireHealth() {
        return getConfig().getDouble("vampire.health", 40.0D);
    }

    public int getStalkTicksMin() {
        return Math.max(1, getConfig().getInt("vampire.stalk-ticks-min", 80));
    }

    public int getStalkTicksMax() {
        return Math.max(getStalkTicksMin(), getConfig().getInt("vampire.stalk-ticks-max", 140));
    }

    public int getVampireSpawnRadius() {
        return Math.max(8, getConfig().getInt("vampire.spawn-radius", 48));
    }

    public int getMaxVampiresPerPlayer() {
        return Math.max(0, getConfig().getInt("vampire.max-per-player", 2));
    }

    public String getVampireSkinUrl() {
        return getConfig().getString("vampire.skin-url", "https://s.namemc.com/i/a15d2c945b798f66.png");
    }

    public double getBleedChance() {
        return Math.max(0.0D, Math.min(1.0D, getConfig().getDouble("bleed.chance", 0.4D)));
    }

    public double getBleedDamagePerTick() {
        return Math.max(0.0D, getConfig().getDouble("bleed.damage-per-tick", 1.0D));
    }

    public int getBleedIntervalTicks() {
        return Math.max(1, getConfig().getInt("bleed.interval-ticks", 40));
    }

    public int getBleedMaxStacks() {
        return Math.max(1, getConfig().getInt("bleed.max-stacks", 2));
    }

    public String getEventStartMessage() {
        return getConfig().getString("messages.event-start", "§4☽ §lTHE BLOOD MOON RISES §r§4☾");
    }

    public String getEventEndMessage() {
        return getConfig().getString("messages.event-end", "§6The Blood Moon fades... for now.");
    }

    private void addDefaults(FileConfiguration config) {
        config.addDefault("bloodmoon.chance", 12);
        config.addDefault("bloodmoon.worlds", List.of("world"));
        config.addDefault("bloodmoon.special-monsters.enabled", true);
        config.addDefault("bloodmoon.special-monsters.spawn-radius", 48);
        config.addDefault("bloodmoon.special-monsters.max-per-player", 2);
        config.addDefault("bloodmoon.special-monsters.spawn-pulse-ticks", 120);
        config.addDefault("bloodmoon.special-monsters.spawn-chance", 0.55D);
        config.addDefault("vampire.health", 40.0D);
        config.addDefault("vampire.stalk-ticks-min", 80);
        config.addDefault("vampire.stalk-ticks-max", 140);
        config.addDefault("vampire.spawn-radius", 48);
        config.addDefault("vampire.max-per-player", 2);
        config.addDefault("vampire.skin-url", "https://s.namemc.com/i/a15d2c945b798f66.png");
        config.addDefault("bleed.chance", 0.4D);
        config.addDefault("bleed.damage-per-tick", 1.0D);
        config.addDefault("bleed.interval-ticks", 40);
        config.addDefault("bleed.max-stacks", 2);
        config.addDefault("messages.event-start", "§4☽ §lTHE BLOOD MOON RISES §r§4☾");
        config.addDefault("messages.event-end", "§6The Blood Moon fades... for now.");
        config.options().copyDefaults(true);
    }
}
