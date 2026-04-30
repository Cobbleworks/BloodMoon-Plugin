package com.yourname.bloodmoon.effects;

import com.yourname.bloodmoon.BloodMoonPlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Reverses movement input for a short duration.
 */
public final class MindHexEffect {

    private final BloodMoonPlugin plugin;
    private final Map<UUID, Integer> activeTicks = new HashMap<>();

    public MindHexEffect(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    public void apply(Player player, int ticks) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        activeTicks.put(player.getUniqueId(), Math.max(1, ticks));
    }

    public void tick(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) {
            return;
        }
        Integer remaining = activeTicks.get(player.getUniqueId());
        if (remaining == null || remaining <= 0) {
            return;
        }

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (Math.abs(dx) < 0.001D && Math.abs(dz) < 0.001D) {
            return;
        }

        Location corrected = to.clone();
        corrected.setX(from.getX() - dx);
        corrected.setZ(from.getZ() - dz);
        player.teleport(corrected);

        int next = remaining - 1;
        if (next <= 0) {
            activeTicks.remove(player.getUniqueId());
        } else {
            activeTicks.put(player.getUniqueId(), next);
        }
    }

    public void cancel(Player player) {
        if (player != null) {
            activeTicks.remove(player.getUniqueId());
        }
    }

    public void cancelAll() {
        activeTicks.clear();
    }
}
