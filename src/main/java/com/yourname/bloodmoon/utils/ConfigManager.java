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

    public void load() {
        FileConfiguration config = plugin.getConfig();
        config.options().copyDefaults(true);
        addDefaults(config);
        plugin.saveDefaultConfig();
        plugin.saveConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        addDefaults(plugin.getConfig());
        plugin.saveConfig();
    }

    public FileConfiguration getConfig() {
        return plugin.getConfig();
    }

    public int getBloodMoonChance() {
        return Math.max(1, getConfig().getInt("bloodmoon.chance", 24));
    }

    public List<String> getEnabledWorlds() {
        return getConfig().getStringList("bloodmoon.worlds");
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
        return Math.max(0, getConfig().getInt("vampire.max-per-player", 1));
    }

    public String getVampireSkinName() {
        return getConfig().getString("vampire.skin-name", "bloodmoon_vampire_150ea3e3982a1bd3");
    }

    public String getVampireSkinTexture() {
        return getConfig().getString("vampire.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc2NTIzMzk2OTc2NiwKICAicHJvZmlsZUlkIiA6ICIwNWU0OTczZTk5ZDk0NGY2YjczNjY3ZTEzMzdiY2IzOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lX1NraW4iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjk4YTliNzdlOWNmM2MxYjE1ZmMzNTlhN2FjZmMxZjYyMWYyYTc1OWVmNzZmYmI5N2I0Nzg3NTQ3MWZkMzI1ZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
    }

    public String getVampireSkinSignature() {
        return getConfig().getString("vampire.skin-signature",
            "wYc8PrLfnZyMfsRSHyTBP/CeBQRwzDG66MSQVlsUnghqwxvJdF47pwWlNPcI7yhU1+/Vwi+OYCVF/Ur7Pf+PP/Lx1U9z4x2jm0T50os8MzPh5VbRP54n82Qd727p9iSVJE9YUTrAG+nbqJ5joy0O5JLP9fBjZNA9RLsj6fFwnYccTc4zIV2QVamj0MNuYHihNHYiBh4lWTR8pLi+WH+/qS0Ll5And0QraSLLQdKdpsVl1IPJjghjGmqaRtUlQ+KaOhQBzcYnxxR7Dq4/IEOrkJUyq1/y/BmVuPpQldk9l6iQ8YVBEjJNHd9Ji9vkeplEJw+XxtWkh+yTbDz6XeszGUyIZ5CbY28VIDJYMmRWLMfgg37eK46xTrKzk+N9Yw2/qOrJtGHQ4n2cbbi76OAGv92eGsOWbR7Dw36zVBRHuUdn6+YqKChW3sqL3ES8JqA3KSwgs76GQMkDK/WlxR0LdE/ekaTLRYxKcBxbtverWhqCWt4Mt0zGraPfdNyQs9fNE6dqs2zHh0WCY0bPZ9gdcwBnzEQdgFMkNPGtVdFsCdU64hUzA/BgFGPLL4cWSss5IDNgJ3o+VxXS8u3wANIemvZIhHozEt04uNla7X6/VWhdm6D/k8PC1F8IhAwD8byY1wkYx2Q4ocgdVIGT4pq1gEWWtJahQFPfyzHHvP512H0=");
    }

    public String getVampireSkinUrl() {
        return getConfig().getString("vampire.skin-url", "");
    }

    public double getClownHealth() {
        return Math.max(1.0D, getConfig().getDouble("clown.health", 32.0D));
    }

    public int getClownMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("clown.max-per-player", 1));
    }

    public int getClownSpawnRadius() {
        return Math.max(8, getConfig().getInt("clown.spawn-radius", 48));
    }

    public double getClownSnapRadius() {
        return Math.max(1.0D, getConfig().getDouble("clown.snap-radius", 6.0D));
    }

    public double getClownManicHpThreshold() {
        return Math.max(0.05D, Math.min(0.95D, getConfig().getDouble("clown.manic-hp-threshold", 0.4D)));
    }

    public double getClownManicCooldownMultiplier() {
        return Math.max(0.1D, Math.min(1.0D, getConfig().getDouble("clown.manic-cooldown-multiplier", 0.5D)));
    }

    public int getClownBalloonCap() {
        return Math.max(1, getConfig().getInt("clown.balloon-cap", 6));
    }

    public int getClownTwistedTeleportHops() {
        return Math.max(1, getConfig().getInt("clown.twisted-teleport-hops", 3));
    }

    public int getClownTwistedTeleportIntervalTicks() {
        return Math.max(10, getConfig().getInt("clown.twisted-teleport-interval-ticks", 40));
    }

    public String getClownSkinName() {
        return getConfig().getString("clown.skin-name", "6be48e115844e11c");
    }

    public String getClownSkinTexture() {
        return getConfig().getString("clown.skin-texture", "");
    }

    public String getClownSkinSignature() {
        return getConfig().getString("clown.skin-signature", "");
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
        return getConfig().getString("messages.event-start", "§4- THE BLOOD MOON RISES -");
    }

    public String getEventEndMessage() {
        return getConfig().getString("messages.event-end", "§6The Blood Moon fades... for now.");
    }

    private void addDefaults(FileConfiguration config) {
        config.addDefault("bloodmoon.chance", 24);
        config.addDefault("bloodmoon.worlds", List.of("world"));
        config.addDefault("vampire.health", 40.0D);
        config.addDefault("vampire.stalk-ticks-min", 80);
        config.addDefault("vampire.stalk-ticks-max", 140);
        config.addDefault("vampire.spawn-radius", 48);
        config.addDefault("vampire.max-per-player", 1);
        config.addDefault("vampire.skin-name", "bloodmoon_vampire_150ea3e3982a1bd3");
        config.addDefault("vampire.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc2NTIzMzk2OTc2NiwKICAicHJvZmlsZUlkIiA6ICIwNWU0OTczZTk5ZDk0NGY2YjczNjY3ZTEzMzdiY2IzOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lX1NraW4iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjk4YTliNzdlOWNmM2MxYjE1ZmMzNTlhN2FjZmMxZjYyMWYyYTc1OWVmNzZmYmI5N2I0Nzg3NTQ3MWZkMzI1ZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
        config.addDefault("vampire.skin-signature",
            "wYc8PrLfnZyMfsRSHyTBP/CeBQRwzDG66MSQVlsUnghqwxvJdF47pwWlNPcI7yhU1+/Vwi+OYCVF/Ur7Pf+PP/Lx1U9z4x2jm0T50os8MzPh5VbRP54n82Qd727p9iSVJE9YUTrAG+nbqJ5joy0O5JLP9fBjZNA9RLsj6fFwnYccTc4zIV2QVamj0MNuYHihNHYiBh4lWTR8pLi+WH+/qS0Ll5And0QraSLLQdKdpsVl1IPJjghjGmqaRtUlQ+KaOhQBzcYnxxR7Dq4/IEOrkJUyq1/y/BmVuPpQldk9l6iQ8YVBEjJNHd9Ji9vkeplEJw+XxtWkh+yTbDz6XeszGUyIZ5CbY28VIDJYMmRWLMfgg37eK46xTrKzk+N9Yw2/qOrJtGHQ4n2cbbi76OAGv92eGsOWbR7Dw36zVBRHuUdn6+YqKChW3sqL3ES8JqA3KSwgs76GQMkDK/WlxR0LdE/ekaTLRYxKcBxbtverWhqCWt4Mt0zGraPfdNyQs9fNE6dqs2zHh0WCY0bPZ9gdcwBnzEQdgFMkNPGtVdFsCdU64hUzA/BgFGPLL4cWSss5IDNgJ3o+VxXS8u3wANIemvZIhHozEt04uNla7X6/VWhdm6D/k8PC1F8IhAwD8byY1wkYx2Q4ocgdVIGT4pq1gEWWtJahQFPfyzHHvP512H0=");
        config.addDefault("vampire.skin-url", "");
        config.addDefault("clown.health", 32.0D);
        config.addDefault("clown.max-per-player", 1);
        config.addDefault("clown.spawn-radius", 48);
        config.addDefault("clown.snap-radius", 6.0D);
        config.addDefault("clown.manic-hp-threshold", 0.4D);
        config.addDefault("clown.manic-cooldown-multiplier", 0.5D);
        config.addDefault("clown.balloon-cap", 6);
        config.addDefault("clown.twisted-teleport-hops", 3);
        config.addDefault("clown.twisted-teleport-interval-ticks", 40);
        config.addDefault("clown.skin-name", "6be48e115844e11c");
        config.addDefault("clown.skin-texture", "");
        config.addDefault("clown.skin-signature", "");
        config.addDefault("bleed.chance", 0.4D);
        config.addDefault("bleed.damage-per-tick", 1.0D);
        config.addDefault("bleed.interval-ticks", 40);
        config.addDefault("bleed.max-stacks", 2);
        config.addDefault("messages.event-start", "§4- THE BLOOD MOON RISES -");
        config.addDefault("messages.event-end", "§6The Blood Moon fades... for now.");
        config.options().copyDefaults(true);
    }
}
