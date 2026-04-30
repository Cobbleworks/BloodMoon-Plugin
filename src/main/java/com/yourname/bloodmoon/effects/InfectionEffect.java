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
 * Tracks and applies the zombie infection damage-over-time effect.
 */
public final class InfectionEffect {

    private static final int BASE_JUMP_INTERVAL_TICKS = 100;

    private final BloodMoonPlugin plugin;
    private final Map<UUID, BukkitRunnable> activeTasks = new HashMap<>();
    private final Map<UUID, InfectionState> states = new HashMap<>();

    public InfectionEffect(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    public void applyInfection(Player player) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        InfectionState existing = states.get(uuid);
        if (existing != null) {
            existing.remainingTicks = plugin.getConfigManager().getZombieInfectionDurationTicks();
            MessageUtils.actionBar(player, "§2☣ Infected");
            return;
        }

        InfectionState state = new InfectionState(
            plugin.getConfigManager().getZombieInfectionDurationTicks(),
            0,
            0
        );
        states.put(uuid, state);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                Player current = plugin.getServer().getPlayer(uuid);
                InfectionState infection = states.get(uuid);
                if (current == null || infection == null || !current.isOnline() || current.isDead()) {
                    cancelInfection(uuid);
                    return;
                }

                spawnInfectionParticles(current);
                MessageUtils.actionBar(current, "§2☣ Infected");

                infection.tickCounter++;
                infection.jumpCounter++;
                infection.remainingTicks--;

                if (infection.tickCounter >= plugin.getConfigManager().getZombieInfectionTickInterval()) {
                    infection.tickCounter = 0;
                    current.damage(plugin.getConfigManager().getZombieInfectionDamage());
                    current.getWorld().playSound(current.getLocation(), Sound.ENTITY_ZOMBIE_INFECT, 0.45F, 0.8F);
                }

                if (infection.jumpCounter >= BASE_JUMP_INTERVAL_TICKS) {
                    infection.jumpCounter = 0;
                    spreadInfection(current);
                }

                if (infection.remainingTicks <= 0) {
                    cancelInfection(uuid);
                }
            }
        };

        activeTasks.put(uuid, task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    public void cancelInfection(Player player) {
        if (player != null) {
            cancelInfection(player.getUniqueId());
        }
    }

    public void cancelAll() {
        Iterator<Map.Entry<UUID, BukkitRunnable>> iterator = activeTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BukkitRunnable> entry = iterator.next();
            entry.getValue().cancel();
            iterator.remove();
        }
        states.clear();
    }

    public boolean isInfected(Player player) {
        return player != null && activeTasks.containsKey(player.getUniqueId());
    }

    private void spreadInfection(Player source) {
        double radius = plugin.getConfigManager().getZombieInfectionJumpRadius();
        double radiusSquared = radius * radius;
        for (Player nearby : source.getWorld().getPlayers()) {
            if (nearby.getUniqueId().equals(source.getUniqueId()) || nearby.isDead()) {
                continue;
            }
            if (nearby.getLocation().distanceSquared(source.getLocation()) <= radiusSquared) {
                applyInfection(nearby);
            }
        }
    }

    private void cancelInfection(UUID uuid) {
        BukkitRunnable task = activeTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        states.remove(uuid);
    }

    private void spawnInfectionParticles(Player player) {
        World world = player.getWorld();
        Particle.DustOptions green = new Particle.DustOptions(Color.fromRGB(50, 190, 50), 0.9F);
        Particle.DustOptions black = new Particle.DustOptions(Color.fromRGB(25, 25, 25), 0.8F);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0.0D, 1.0D, 0.0D), 4, 0.35D, 0.5D, 0.35D, 0.0D, green);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0.0D, 0.5D, 0.0D), 3, 0.25D, 0.35D, 0.25D, 0.0D, black);
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, player.getLocation().add(0.0D, 0.8D, 0.0D), 2, 0.2D, 0.25D, 0.2D, 0.01D);
    }

    private static final class InfectionState {
        private int remainingTicks;
        private int tickCounter;
        private int jumpCounter;

        private InfectionState(int remainingTicks, int tickCounter, int jumpCounter) {
            this.remainingTicks = remainingTicks;
            this.tickCounter = tickCounter;
            this.jumpCounter = jumpCounter;
        }
    }
}
