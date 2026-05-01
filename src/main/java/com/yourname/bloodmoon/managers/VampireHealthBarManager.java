package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.ClownNPC;
import com.yourname.bloodmoon.mobs.GhostNPC;
import com.yourname.bloodmoon.mobs.ScarecrowNPC;
import com.yourname.bloodmoon.mobs.VampireNPC;
import com.yourname.bloodmoon.mobs.WerewolfNPC;
import com.yourname.bloodmoon.mobs.WitchNPC;
import com.yourname.bloodmoon.mobs.ZombieNPC;
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
 * Tracks per-player nearest Blood Moon special-mob health bars.
 */
public final class VampireHealthBarManager {

    private static final double TRACK_RADIUS = 64.0D;

    private final BloodMoonPlugin plugin;
    private final Set<UUID> enabledPlayers = new HashSet<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitRunnable updateTask;

    private record HealthBarTarget(String label, double currentHealth, double maximumHealth, double distanceMeters) {}

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
        HealthBarTarget nearest = findNearestTarget(player);
        if (nearest == null) {
            disable(player.getUniqueId());
            return;
        }

        BossBar bar = bars.computeIfAbsent(player.getUniqueId(), id -> {
            BossBar created = Bukkit.createBossBar("§4Nearest Special Mob", BarColor.RED, BarStyle.SOLID);
            created.addPlayer(player);
            created.setVisible(true);
            return created;
        });
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        double maxHealth = Math.max(1.0D, nearest.maximumHealth());
        double currentHealth = Math.max(0.0D, Math.min(maxHealth, nearest.currentHealth()));
        double progress = Math.max(0.0D, Math.min(1.0D, currentHealth / maxHealth));
        bar.setTitle("§4" + nearest.label() + " §8" + buildSegmentBar(progress) + " §7" + format(currentHealth) + "§8/§7" + format(maxHealth) + " §8(" + format(nearest.distanceMeters()) + "m)");
        bar.setProgress(progress);
        bar.setColor(BarColor.RED);
        bar.setVisible(true);
    }

    private HealthBarTarget findNearestTarget(Player player) {
        HealthBarTarget nearest = null;
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
            nearest = new HealthBarTarget("Vampire", vampire.getCurrentHealth(), vampire.getMaximumHealth(), Math.sqrt(distanceSquared));
        }

        for (ClownNPC clown : plugin.getNPCManager().getActiveClowns()) {
            if (clown == null || clown.isDead()) {
                continue;
            }
            if (clown.getCurrentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distanceSquared = clown.getCurrentLocation().distanceSquared(player.getLocation());
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            nearest = new HealthBarTarget("Clown", clown.getCurrentHealth(), clown.getMaximumHealth(), Math.sqrt(distanceSquared));
        }

        for (ZombieNPC zombie : plugin.getNPCManager().getActiveZombies()) {
            if (zombie == null || zombie.isDead()) {
                continue;
            }
            if (zombie.getCurrentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distanceSquared = zombie.getCurrentLocation().distanceSquared(player.getLocation());
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            nearest = new HealthBarTarget("Zombie", zombie.getCurrentHealth(), zombie.getMaximumHealth(), Math.sqrt(distanceSquared));
        }

        for (WitchNPC witch : plugin.getNPCManager().getActiveWitches()) {
            if (witch == null || witch.isDead()) {
                continue;
            }
            if (witch.getCurrentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distanceSquared = witch.getCurrentLocation().distanceSquared(player.getLocation());
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            nearest = new HealthBarTarget("Witch", witch.getCurrentHealth(), witch.getMaximumHealth(), Math.sqrt(distanceSquared));
        }

        for (ScarecrowNPC scarecrow : plugin.getNPCManager().getActiveScarecrows()) {
            if (scarecrow == null || scarecrow.isDead()) {
                continue;
            }
            if (scarecrow.getCurrentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distanceSquared = scarecrow.getCurrentLocation().distanceSquared(player.getLocation());
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            nearest = new HealthBarTarget("Scarecrow", scarecrow.getCurrentHealth(), scarecrow.getMaximumHealth(), Math.sqrt(distanceSquared));
        }

        for (GhostNPC ghost : plugin.getNPCManager().getActiveGhosts()) {
            if (ghost == null || ghost.isDead()) {
                continue;
            }
            if (ghost.getCurrentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distanceSquared = ghost.getCurrentLocation().distanceSquared(player.getLocation());
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            nearest = new HealthBarTarget("Ghost", ghost.getCurrentHealth(), ghost.getMaximumHealth(), Math.sqrt(distanceSquared));
        }

        for (WerewolfNPC werewolf : plugin.getNPCManager().getActiveWerewolves()) {
            if (werewolf == null || werewolf.isDead()) {
                continue;
            }
            if (werewolf.getCurrentLocation().getWorld() != player.getWorld()) {
                continue;
            }
            double distanceSquared = werewolf.getCurrentLocation().distanceSquared(player.getLocation());
            if (distanceSquared > bestDistanceSquared) {
                continue;
            }
            bestDistanceSquared = distanceSquared;
            nearest = new HealthBarTarget("Werewolf", werewolf.getCurrentHealth(), werewolf.getMaximumHealth(), Math.sqrt(distanceSquared));
        }
        return nearest;
    }

    private String buildSegmentBar(double progress) {
        int segments = 10;
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * segments);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < segments; i++) {
            builder.append(i < filled ? "§c■" : "§8□");
        }
        builder.append("§7]");
        return builder.toString();
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
