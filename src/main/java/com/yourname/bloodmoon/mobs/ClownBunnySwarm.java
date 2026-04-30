package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Manual aggression controller for summoned killer bunny minions.
 * Bunnies chase the nearest player for a set duration, then detonate.
 */
public final class ClownBunnySwarm {

    private static final int BUNNY_LIFETIME_TICKS = 200;
    private static final double BUNNY_SPEED = 0.35D;
    private static final double BUNNY_CHASE_RANGE = 32.0D;
    private static final double BUNNY_DETONATE_RANGE = 1.6D;
    private static final double BUNNY_DETONATE_DAMAGE = 8.0D;
    private static final double BUNNY_DETONATE_RADIUS = 3.5D;

    private final BloodMoonPlugin plugin;
    private final ClownNPC owner;
    private final Random random = new Random();

    private final List<BunnyEntry> bunnies = new ArrayList<>();
    private BukkitRunnable controllerTask;

    public ClownBunnySwarm(BloodMoonPlugin plugin, ClownNPC owner) {
        this.plugin = plugin;
        this.owner = owner;
    }

    public void spawn(Location center, int amount) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        for (int index = 0; index < amount; index++) {
            Location spawnLoc = center.clone().add(
                owner.randomDouble(-2.0D, 2.0D),
                0.1D,
                owner.randomDouble(-2.0D, 2.0D)
            );
            spawnLoc.setY(world.getHighestBlockYAt(spawnLoc) + 0.2D);

            Rabbit rabbit = (Rabbit) world.spawnEntity(spawnLoc, EntityType.RABBIT);
            rabbit.setCustomName("");
            rabbit.setCustomNameVisible(false);
            rabbit.setRabbitType(Rabbit.Type.THE_KILLER_BUNNY);
            rabbit.setRemoveWhenFarAway(false);
            rabbit.setMaximumNoDamageTicks(3);
            rabbit.setInvulnerable(true);
            hideNameTag(rabbit);

            BukkitRunnable arm = new BukkitRunnable() {
                @Override
                public void run() {
                    if (rabbit.isValid()) {
                        rabbit.setInvulnerable(false);
                    }
                }
            };
            arm.runTaskLater(plugin, 40L);

            world.spawnParticle(Particle.HAPPY_VILLAGER, spawnLoc, 12, 0.4D, 0.5D, 0.4D, 0.02D);
            world.playSound(spawnLoc, Sound.ENTITY_RABBIT_JUMP, 0.8F, 0.5F + index * 0.12F);

            bunnies.add(new BunnyEntry(rabbit, 0));
        }

        ensureControllerRunning();
    }

    public int size() {
        cleanupInvalid();
        return bunnies.size();
    }

    public void cleanup() {
        if (controllerTask != null) {
            controllerTask.cancel();
            controllerTask = null;
        }
        for (BunnyEntry entry : bunnies) {
            if (entry.rabbit != null && entry.rabbit.isValid()) {
                entry.rabbit.remove();
            }
        }
        bunnies.clear();
    }

    private void ensureControllerRunning() {
        if (controllerTask != null) {
            return;
        }
        controllerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (owner.isDead()) {
                    cleanup();
                    return;
                }
                tickBunnies();
            }
        };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void tickBunnies() {
        Iterator<BunnyEntry> iterator = bunnies.iterator();
        while (iterator.hasNext()) {
            BunnyEntry entry = iterator.next();
            if (entry.rabbit == null || !entry.rabbit.isValid() || entry.rabbit.isDead()) {
                iterator.remove();
                continue;
            }

            entry.age++;

            // Chase nearest player
            Player target = findNearestPlayer(entry.rabbit.getLocation(), BUNNY_CHASE_RANGE);

            // Detonate early if within detonation range
            if (target != null && target.getLocation().distanceSquared(entry.rabbit.getLocation()) <= BUNNY_DETONATE_RANGE * BUNNY_DETONATE_RANGE) {
                detonateAt(entry.rabbit.getLocation());
                entry.rabbit.remove();
                iterator.remove();
                continue;
            }

            // Detonate after lifetime
            if (entry.age >= BUNNY_LIFETIME_TICKS) {
                detonateAt(entry.rabbit.getLocation());
                entry.rabbit.remove();
                iterator.remove();
                continue;
            }

            // Chase target
            if (target != null) {
                moveTowardTarget(entry.rabbit, target);
            } else {
                // Wander near spawn
                Location ownerLoc = owner.getCurrentLocation();
                Vector wander = ownerLoc.toVector().subtract(entry.rabbit.getLocation().toVector());
                if (wander.lengthSquared() > 0.01D) {
                    wander.normalize().multiply(0.15D);
                    wander.setY(0.05D);
                    entry.rabbit.setVelocity(wander);
                }
            }

            // Ambient particles: white puffs
            if (entry.age % 5 == 0) {
                entry.rabbit.getWorld().spawnParticle(
                    Particle.DUST,
                    entry.rabbit.getLocation().add(0.0D, 0.4D, 0.0D),
                    2, 0.1D, 0.1D, 0.1D, 0.0D,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 80, 120), 0.8F)
                );
            }

            // Countdown ticking sound (last 40 ticks)
            if (entry.age >= BUNNY_LIFETIME_TICKS - 40 && entry.age % 10 == 0) {
                entry.rabbit.getWorld().playSound(entry.rabbit.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6F, 2.0F);
            }
        }

        if (bunnies.isEmpty() && controllerTask != null) {
            controllerTask.cancel();
            controllerTask = null;
        }
    }

    private void moveTowardTarget(Rabbit bunny, Player target) {
        Vector direction = target.getLocation().toVector().subtract(bunny.getLocation().toVector());
        if (direction.lengthSquared() < 0.01D) {
            return;
        }
        direction.normalize().multiply(BUNNY_SPEED);
        direction.setY(Math.max(direction.getY(), 0.05D));
        bunny.setVelocity(direction);
    }

    private void detonateAt(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        // Colorful explosion visual — no block damage
        world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1);
        world.spawnParticle(Particle.FIREWORK, location, 50, 0.8D, 0.8D, 0.8D, 0.12D);
        world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 0.5D, 0.0D), 30, 0.7D, 0.7D, 0.7D, 0.0D,
            new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 80, 120), 1.2F));
        world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 0.5D, 0.0D), 20, 0.5D, 0.5D, 0.5D, 0.0D,
            new Particle.DustOptions(org.bukkit.Color.fromRGB(255, 220, 0), 1.0F));

        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.9F, 1.5F);
        world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8F, 0.7F);

        // Deal damage to players in radius — no entity-driven explosion (zero block damage)
        LivingEntity ownerEntity = owner.getLivingEntityPublic();
        double radiusSq = BUNNY_DETONATE_RADIUS * BUNNY_DETONATE_RADIUS;
        for (Entity nearby : world.getNearbyEntities(location, BUNNY_DETONATE_RADIUS, BUNNY_DETONATE_RADIUS, BUNNY_DETONATE_RADIUS)) {
            if (!(nearby instanceof Player player)) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) > radiusSq) {
                continue;
            }
            player.damage(BUNNY_DETONATE_DAMAGE, ownerEntity);
        }
    }

    private Player findNearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        double radiusSquared = radius * radius;
        return location.getWorld().getPlayers().stream()
            .filter(p -> !p.isDead())
            .filter(p -> p.getLocation().distanceSquared(location) <= radiusSquared)
            .min(java.util.Comparator.comparingDouble(p -> p.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    private void cleanupInvalid() {
        bunnies.removeIf(entry -> entry.rabbit == null || !entry.rabbit.isValid() || entry.rabbit.isDead());
    }

    private void hideNameTag(Rabbit rabbit) {
        try {
            Scoreboard board = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
            if (board == null) {
                return;
            }
            Team team = board.getTeam("bm_hidden_mobs");
            if (team == null) {
                team = board.registerNewTeam("bm_hidden_mobs");
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            team.addEntry(rabbit.getUniqueId().toString());
        } catch (Exception ignored) {
        }
    }

    private static final class BunnyEntry {
        final Rabbit rabbit;
        int age;

        BunnyEntry(Rabbit rabbit, int age) {
            this.rabbit = rabbit;
            this.age = age;
        }
    }
}
