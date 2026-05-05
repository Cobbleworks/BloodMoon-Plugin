package com.cobbleworks.bloodmoon.mobs;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Bat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Manual aggression controller for summoned vampire bats.
 */
public final class VampireBatSwarm {

    private final BloodMoonPlugin plugin;
    private final VampireNPC owner;
    private final List<Bat> bats = new ArrayList<>();
    private final java.util.Map<UUID, Integer> contactCooldowns = new java.util.HashMap<>();
    private BukkitRunnable controllerTask;

    public VampireBatSwarm(BloodMoonPlugin plugin, VampireNPC owner) {
        this.plugin = plugin;
        this.owner = owner;
    }

    /**
     * Spawns aggressive bats around the vampire.
     *
     * @param center spawn center
     * @param amount amount of bats to spawn
     */
    public void spawn(Location center, int amount) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        for (int index = 0; index < amount; index++) {
            // Evenly-spaced angles give a clean burst rather than a random clump
            double angle = (Math.PI * 2.0D * index) / amount;
            double radius = 1.2D + owner.randomDouble(0.0D, 0.8D);
            Location spawn = center.clone().add(
                Math.cos(angle) * radius,
                owner.randomDouble(0.4D, 2.2D),
                Math.sin(angle) * radius
            );
            Bat bat = (Bat) world.spawnEntity(spawn, EntityType.BAT);
            bat.setCustomName(null);
            bat.setCustomNameVisible(false);
            bat.setRemoveWhenFarAway(false);
            bat.setAwake(true);
            // AI must stay ENABLED — setAI(false) skips aiStep() which means
            // setVelocity() is never applied to position and bats freeze.
            // Gravity disabled keeps them airborne; our velocity override each
            // tick dominates the bat's own wander goal.
            bat.setGravity(false);
            // Reduce HP so bats die in 1-2 hits
            AttributeInstance maxHp = bat.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHp != null) {
                maxHp.setBaseValue(2.0D);
                bat.setHealth(2.0D);
            }
            // Immediate outward kick so bats visually burst from the caster on spawn
            Vector kick = new Vector(Math.cos(angle), owner.randomDouble(0.15D, 0.45D), Math.sin(angle))
                    .normalize().multiply(0.55D);
            bat.setVelocity(kick);
            bats.add(bat);
            plugin.getNPCManager().registerBat(bat);

            world.spawnParticle(Particle.PORTAL, spawn, 18, 0.3D, 0.4D, 0.3D, 0.12D);
            world.spawnParticle(Particle.SMOKE, spawn, 12, 0.25D, 0.25D, 0.25D, 0.02D);
            world.playSound(spawn, Sound.ENTITY_BAT_LOOP, 0.7F, 0.8F + (index * 0.1F));
        }

        ensureControllerRunning();
    }

    /**
     * Returns active bat count.
     *
     * @return active bat count
     */
    public int size() {
        cleanupInvalidReferences();
        return bats.size();
    }

    /**
     * Removes all swarm bats and cancels their controller.
     */
    public void cleanup() {
        if (controllerTask != null) {
            controllerTask.cancel();
            controllerTask = null;
        }

        for (Bat bat : bats) {
            if (bat != null && bat.isValid()) {
                plugin.getNPCManager().unregisterBat(bat);
                bat.remove();
            }
        }
        bats.clear();
        contactCooldowns.clear();
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
                tickBats();
            }
        };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void tickBats() {
        decrementCooldowns();

        Iterator<Bat> iterator = bats.iterator();
        while (iterator.hasNext()) {
            Bat bat = iterator.next();
            if (bat == null || !bat.isValid() || bat.isDead()) {
                if (bat != null) {
                    plugin.getNPCManager().unregisterBat(bat);
                }
                iterator.remove();
                continue;
            }

            Player target = findNearestTarget(bat.getLocation(), 32.0D);
            if (target == null) {
                swirlNearOwner(bat);
                continue;
            }

            moveTowardTarget(bat, target);
            tryContactDamage(bat, target);
        }

        if (bats.isEmpty() && controllerTask != null) {
            controllerTask.cancel();
            controllerTask = null;
        }
    }

    private void moveTowardTarget(Bat bat, LivingEntity target) {
        Vector direction = target.getEyeLocation().toVector().subtract(bat.getLocation().toVector());
        if (direction.lengthSquared() < 0.01D) {
            return;
        }
        double speed = Math.min(0.38D, direction.length() * 0.5D);
        bat.setVelocity(direction.normalize().multiply(speed));
        bat.getWorld().spawnParticle(Particle.PORTAL, bat.getLocation(), 2, 0.08D, 0.08D, 0.08D, 0.01D);
    }

    private void swirlNearOwner(Bat bat) {
        Location ownerLocation = owner.getCurrentLocation();
        if (ownerLocation == null || ownerLocation.getWorld() != bat.getWorld()) {
            return;
        }
        Vector direction = ownerLocation.clone().add(0.0D, 2.0D, 0.0D).toVector()
                .subtract(bat.getLocation().toVector());
        if (direction.lengthSquared() > 1.0D) {
            bat.setVelocity(direction.normalize().multiply(0.2D));
        }
    }

    private void tryContactDamage(Bat bat, LivingEntity target) {
        if (bat.getLocation().distanceSquared(target.getLocation()) > 1.6D) {
            return;
        }

        UUID uuid = target.getUniqueId();
        int cooldown = contactCooldowns.getOrDefault(uuid, 0);
        if (cooldown > 0) {
            return;
        }

        target.damage(1.0D, bat);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_BAT_HURT, 0.4F, 1.3F);
        contactCooldowns.put(uuid, 20);
    }

    /**
     * Finds the nearest player using a proper sphere check.
     *
     * <p>Previously used {@code getNearbyEntities(x, 4.0, z)} which capped the
     * Y-axis search to only 4 blocks. After the spawn kick sends bats upward,
     * any player more than 4 blocks below them was never found, so
     * {@code findNearestTarget} always returned null and bats fell into
     * {@code swirlNearOwner} until the vampire moved close enough.</p>
     *
     * <p>Using {@code getPlayers()} with a {@code distanceSquared} check is a
     * true sphere — identical to how {@link ClownBunnySwarm} finds targets, and
     * it has no Y-axis limitation.</p>
     */
    private Player findNearestTarget(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        double radiusSq = radius * radius;
        return world.getPlayers().stream()
                .filter(p -> !p.isDead() && p.isValid())
                .filter(p -> !plugin.getNPCManager().isBloodMoonNpc(p))
                .filter(p -> p.getWorld() == world)
                .filter(p -> p.getLocation().distanceSquared(center) <= radiusSq)
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(center)))
                .orElse(null);
    }

    private void decrementCooldowns() {
        Iterator<java.util.Map.Entry<UUID, Integer>> iterator = contactCooldowns.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<UUID, Integer> entry = iterator.next();
            int next = entry.getValue() - 1;
            if (next <= 0) {
                iterator.remove();
            } else {
                entry.setValue(next);
            }
        }
    }

    private void cleanupInvalidReferences() {
        Iterator<Bat> iterator = bats.iterator();
        while (iterator.hasNext()) {
            Bat bat = iterator.next();
            if (bat == null || !bat.isValid() || bat.isDead()) {
                if (bat != null) {
                    plugin.getNPCManager().unregisterBat(bat);
                }
                iterator.remove();
            }
        }
    }
}