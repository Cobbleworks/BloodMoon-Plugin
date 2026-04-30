package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.VampireNPC;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Tracks per-player nearest vampire boss bars.
 */
public final class VampireHealthBarManager {

    private static final double TRACK_RADIUS = 64.0D;

    private final BloodMoonPlugin plugin;
    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitRunnable updateTask;

    public VampireHealthBarManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        updateTask.runTaskTimer(plugin, 1L, 10L);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        for (UUID playerId : Set.copyOf(bars.keySet())) {
            disable(playerId);
        }
        enabledPlayers.clear();
    }

    public boolean toggle(Player player) {
        if (player == null) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        if (enabledPlayers.contains(playerId)) {
            disable(playerId);
            enabledPlayers.remove(playerId);
            return false;
        }
        enabledPlayers.add(playerId);
        updateFor(player);
        return true;
    }

    public void setEnabled(Player player, boolean enabled) {
        if (player == null) {
            return;
        }
        if (enabled) {
            enabledPlayers.add(player.getUniqueId());
            updateFor(player);
            return;
        }
        enabledPlayers.remove(player.getUniqueId());
        disable(player.getUniqueId());
    }

    public boolean isEnabled(Player player) {
        return player != null && enabledPlayers.contains(player.getUniqueId());
    }

    private void tick() {
        for (UUID playerId : Set.copyOf(enabledPlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                enabledPlayers.remove(playerId);
                disable(playerId);
                continue;
            }
            updateFor(player);
        }
    }

    private void updateFor(Player player) {
        VampireNPC nearest = findNearestVampire(player);
        if (nearest == null) {
            disable(player.getUniqueId());
            return;
        }

        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> {
            BossBar created = Bukkit.createBossBar("§4Nearest Vampire", BarColor.RED, BarStyle.SOLID);
            created.addPlayer(player);
            created.setVisible(true);
            return created;
        });
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        double maxHealth = Math.max(1.0D, nearest.getMaximumHealth());
        double currentHealth = Math.max(0.0D, Math.min(maxHealth, nearest.getCurrentHealth()));
        double progress = Math.max(0.0D, Math.min(1.0D, currentHealth / maxHealth));
        double distance = Math.sqrt(nearest.getCurrentLocation().distanceSquared(player.getLocation()));
        bar.setTitle("§4Nearest Vampire §8- §c" + format(currentHealth) + "§7/§c" + format(maxHealth) + " §8(" + format(distance) + "m)");
        bar.setProgress(progress);
        bar.setColor(progress > 0.66D ? BarColor.RED : progress > 0.33D ? BarColor.YELLOW : BarColor.PURPLE);
        bar.setVisible(true);
    }

    private VampireNPC findNearestVampire(Player player) {
        VampireNPC nearest = null;
        double bestDistanceSquared = TRACK_RADIUS * TRACK_RADIUS;
        for (VampireNPC vampire : plugin.getNPCManager().getActiveVampires()) {
            if (vampire == null || vampire.isDead()) {
                continue;
            }
            if (vampire.getCurrentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distanceSquared = vampire.getCurrentLocation().distanceSquared(player.getLocation());
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            nearest = vampire;
        }
        return nearest;
    }

    private void disable(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    private String format(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }
}
