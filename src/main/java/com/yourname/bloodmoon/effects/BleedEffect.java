package com.yourname.bloodmoon.effects;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.utils.MessageUtils;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Tracks and applies the vampire bleed damage-over-time effect.
 */
public final class BleedEffect {

    private static final int BASE_APPLICATIONS = 5;

    private final BloodMoonPlugin plugin;
    private final Map<UUID, BukkitRunnable> activeTasks = new HashMap<>();
    private final Map<UUID, BleedState> states = new HashMap<>();

    public BleedEffect(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Applies or stacks bleeding to a player.
     *
     * @param player affected player
     */
    public void applyBleed(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        BleedState existing = states.get(uuid);
        if (existing != null) {
            existing.remainingApplications += 1;
            existing.stacks = Math.min(plugin.getConfigManager().getBleedMaxStacks(), existing.stacks + 1);
            MessageUtils.actionBar(player, "§4⚡ You are bleeding! §c(" + existing.stacks + "x)");
            return;
        }

        BleedState state = new BleedState(BASE_APPLICATIONS, 1, 0);
        states.put(uuid, state);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                Player current = plugin.getServer().getPlayer(uuid);
                BleedState bleedState = states.get(uuid);
                if (current == null || bleedState == null || !current.isOnline() || current.isDead()) {
                    cancelBleed(uuid);
                    return;
                }

                spawnBleedParticles(current);
                MessageUtils.actionBar(current, "§4⚡ You are bleeding! §c(" + bleedState.stacks + "x)");

                bleedState.tickCounter++;
                if (bleedState.tickCounter < plugin.getConfigManager().getBleedIntervalTicks()) {
                    return;
                }

                bleedState.tickCounter = 0;
                current.damage(plugin.getConfigManager().getBleedDamagePerTick());
                current.getWorld().playSound(current.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.4F, 0.9F);
                bleedState.remainingApplications--;

                if (bleedState.remainingApplications <= 0) {
                    cancelBleed(uuid);
                }
            }
        };

        activeTasks.put(uuid, task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Cancels bleeding for a single player.
     *
     * @param player player to cleanse
     */
    public void cancelBleed(Player player) {
        if (player != null) {
            cancelBleed(player.getUniqueId());
        }
    }

    /**
     * Cancels all active bleed effects.
     */
    public void cancelAll() {
        Iterator<Map.Entry<UUID, BukkitRunnable>> iterator = activeTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BukkitRunnable> entry = iterator.next();
            entry.getValue().cancel();
            iterator.remove();
        }
        states.clear();
    }

    /**
     * Returns whether a player is currently bleeding.
     *
     * @param player player to inspect
     * @return true if the player has an active bleed task
     */
    public boolean isBleeding(Player player) {
        return player != null && activeTasks.containsKey(player.getUniqueId());
    }

    private void cancelBleed(UUID uuid) {
        BukkitRunnable task = activeTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        states.remove(uuid);
    }

    private void spawnBleedParticles(Player player) {
        World world = player.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(120, 0, 0), 0.8F);
        world.spawnParticle(
            Particle.DUST,
            player.getLocation().add(0.0D, 0.08D, 0.0D),
            2,
            0.25D,
            0.02D,
            0.25D,
            0.0D,
            dust
        );
    }

    private static final class BleedState {
        private int remainingApplications;
        private int stacks;
        private int tickCounter;

        private BleedState(int remainingApplications, int stacks, int tickCounter) {
            this.remainingApplications = remainingApplications;
            this.stacks = stacks;
            this.tickCounter = tickCounter;
        }
    }
}
