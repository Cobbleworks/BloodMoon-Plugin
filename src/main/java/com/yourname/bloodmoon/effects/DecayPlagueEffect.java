package com.yourname.bloodmoon.effects;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.utils.MessageUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Unique witch curse with stacking decay and spread at 3 stacks.
 */
public final class DecayPlagueEffect {

    private final BloodMoonPlugin plugin;
    private final Map<UUID, DecayState> states = new HashMap<>();
    private final Map<UUID, BukkitRunnable> tasks = new HashMap<>();

    public DecayPlagueEffect(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    public void applyStack(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        DecayState state = states.get(uuid);
        if (state == null) {
            state = new DecayState();
            states.put(uuid, state);
            startTask(uuid);
        }

        state.stacks = Math.min(5, state.stacks + 1);
        state.remainingTicks = 120;
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_HURT, 0.5F, 1.4F);
        MessageUtils.actionBar(player, "§5☣ Decay x" + state.stacks);

        if (state.stacks >= 3) {
            spreadDecay(player);
        }
    }

    public void cancel(Player player) {
        if (player != null) {
            cancel(player.getUniqueId());
        }
    }

    public void cancelAll() {
        for (BukkitRunnable task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
        states.clear();
    }

    private void startTask(UUID uuid) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                Player player = plugin.getServer().getPlayer(uuid);
                DecayState state = states.get(uuid);
                if (player == null || state == null || !player.isOnline() || player.isDead()) {
                    DecayPlagueEffect.this.cancel(uuid);
                    return;
                }

                state.remainingTicks--;
                int amp = Math.max(0, Math.min(4, state.stacks - 1));
                player.damage(0.0D);
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WITHER, 60, amp, true, true, true));
                player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0.0D, 0.9D, 0.0D), 4, 0.3D, 0.4D, 0.3D, 0.0D,
                    new Particle.DustOptions(Color.fromRGB(60, 10, 70), 0.95F));
                player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0.0D, 0.6D, 0.0D), 3, 0.25D, 0.3D, 0.25D, 0.0D,
                    new Particle.DustOptions(Color.fromRGB(20, 20, 20), 0.8F));

                if (state.remainingTicks <= 0) {
                    DecayPlagueEffect.this.cancel(uuid);
                }
            }
        };
        tasks.put(uuid, task);
        task.runTaskTimer(plugin, 1L, 20L);
    }

    private void spreadDecay(Player source) {
        double radiusSquared = 25.0D;
        for (Player nearby : source.getWorld().getPlayers()) {
            if (nearby.getUniqueId().equals(source.getUniqueId()) || nearby.isDead()) {
                continue;
            }
            if (nearby.getLocation().distanceSquared(source.getLocation()) <= radiusSquared) {
                applyStack(nearby);
            }
        }
    }

    private void cancel(UUID uuid) {
        BukkitRunnable task = tasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        states.remove(uuid);
    }

    private static final class DecayState {
        private int stacks = 0;
        private int remainingTicks = 120;
    }
}
