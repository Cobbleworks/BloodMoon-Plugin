package com.yourname.bloodmoon.effects;

import com.yourname.bloodmoon.BloodMoonPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * A red blood orb projectile fired by a vampire.
 */
public final class BloodMagicProjectile {

    private static final int MAX_FLIGHT_TICKS = 80;

    private final BloodMoonPlugin plugin;
    private final LivingEntity caster;
    private final Player target;
    private final Location start;
    private final int volleyIndex;
    private final int launchDelayTicks;
    private final double initialSpeed;
    private final double homingStrength;
    private final double healPercentOnHit;

    public BloodMagicProjectile(
        BloodMoonPlugin plugin,
        LivingEntity caster,
        Player target,
        Location start,
        int volleyIndex,
        int launchDelayTicks,
        double initialSpeed,
        double homingStrength,
        double healPercentOnHit
    ) {
        this.plugin = plugin;
        this.caster = caster;
        this.target = target;
        this.start = start.clone();
        this.volleyIndex = volleyIndex;
        this.launchDelayTicks = Math.max(0, launchDelayTicks);
        this.initialSpeed = Math.max(0.2D, initialSpeed);
        this.homingStrength = Math.max(0.0D, homingStrength);
        this.healPercentOnHit = Math.max(0.0D, healPercentOnHit);
    }

    /**
     * Launches the projectile on the main server thread.
     */
    public void launch() {
        if (start.getWorld() == null || target == null || !target.isOnline()) {
            return;
        }

        World world = start.getWorld();
        ArmorStand orb = (ArmorStand) world.spawnEntity(start, EntityType.ARMOR_STAND);
        orb.setVisible(false);
        orb.setSmall(true);
        orb.setGravity(false);
        orb.setSilent(true);
        orb.setInvulnerable(true);
        orb.setCustomName("§4Blood Orb");
        orb.setCustomNameVisible(false);

        EntityEquipment equipment = orb.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(new ItemStack(Material.BARRIER));
        }

        Vector initialDirection = target.getEyeLocation().toVector().subtract(start.toVector());
        if (initialDirection.lengthSquared() < 0.0001D) {
            initialDirection = new Vector(0.0D, 0.2D, 1.0D);
        }
        initialDirection.normalize();
        Vector velocity = initialDirection.multiply(initialSpeed + (volleyIndex * 0.02D));
        velocity.setY(velocity.getY() + 0.06D + (volleyIndex * 0.015D));

        new BukkitRunnable() {
            private int ticks;
            private Vector currentVelocity = velocity.clone();

            @Override
            public void run() {
                if (!orb.isValid() || target == null || !target.isOnline() || target.isDead()) {
                    removeOrb();
                    return;
                }

                ticks++;
                Location current = orb.getLocation();
                spawnTrail(world, current);

                if (current.distanceSquared(target.getEyeLocation()) <= 2.25D || current.distanceSquared(target.getLocation()) <= 1.8D) {
                    hitTarget(world, current);
                    removeOrb();
                    return;
                }

                if (ticks > MAX_FLIGHT_TICKS || current.getBlock().getType().isSolid()) {
                    burst(world, current);
                    removeOrb();
                    return;
                }

                Vector toTarget = target.getEyeLocation().toVector().subtract(current.toVector());
                if (toTarget.lengthSquared() < 0.0001D) {
                    toTarget = target.getLocation().toVector().subtract(current.toVector());
                }
                if (toTarget.lengthSquared() > 0.0001D) {
                    Vector homing = toTarget.normalize().multiply(homingStrength);
                    currentVelocity.add(homing);
                }
                currentVelocity.setY(currentVelocity.getY() - 0.018D);
                if (currentVelocity.lengthSquared() > 0.3721D) {
                    currentVelocity.normalize().multiply(0.61D);
                }

                orb.teleport(current.add(currentVelocity));
            }

            private void removeOrb() {
                orb.remove();
                cancel();
            }
        }.runTaskTimer(plugin, (long) volleyIndex * launchDelayTicks, 1L);
    }

    private void hitTarget(World world, Location location) {
        target.damage(3.0D, caster);
        healCasterOnHit();
        target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0, true, true, true));
        world.playSound(location, Sound.ENTITY_GENERIC_HURT, 0.9F, 0.8F);
        world.playSound(location, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.8F, 0.7F);
        burst(world, location);
    }

    private void healCasterOnHit() {
        if (caster == null || caster.isDead() || healPercentOnHit <= 0.0D) {
            return;
        }
        var attr = caster.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) {
            return;
        }
        double maxHealth = attr.getValue();
        if (maxHealth <= 0.0D) {
            return;
        }
        double heal = maxHealth * healPercentOnHit;
        caster.setHealth(Math.min(maxHealth, caster.getHealth() + heal));
    }

    private void spawnTrail(World world, Location location) {
        Particle.DustOptions blood = new Particle.DustOptions(Color.fromRGB(170, 0, 0), 1.2F);
        Particle.DustOptions dark = new Particle.DustOptions(Color.fromRGB(22, 22, 26), 1.0F);
        world.spawnParticle(Particle.DUST, location, 3, 0.08D, 0.08D, 0.08D, 0.0D, blood);
        world.spawnParticle(Particle.DUST, location, 2, 0.07D, 0.07D, 0.07D, 0.0D, dark);
        world.spawnParticle(Particle.SMOKE, location, 1, 0.06D, 0.06D, 0.06D, 0.002D);
    }

    private void burst(World world, Location location) {
        Particle.DustOptions blood = new Particle.DustOptions(Color.fromRGB(190, 0, 0), 1.35F);
        Particle.DustOptions dark = new Particle.DustOptions(Color.fromRGB(26, 26, 30), 1.15F);
        world.spawnParticle(Particle.DUST, location, 16, 0.45D, 0.45D, 0.45D, 0.0D, blood);
        world.spawnParticle(Particle.DUST, location, 10, 0.38D, 0.38D, 0.38D, 0.0D, dark);
        world.spawnParticle(Particle.SMOKE, location, 8, 0.32D, 0.32D, 0.32D, 0.01D);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, location, 8, 0.35D, 0.25D, 0.35D, 0.02D);
    }
}
