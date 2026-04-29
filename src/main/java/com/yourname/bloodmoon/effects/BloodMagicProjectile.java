package com.yourname.bloodmoon.effects;

import com.yourname.bloodmoon.BloodMoonPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
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

    public BloodMagicProjectile(BloodMoonPlugin plugin, LivingEntity caster, Player target, Location start, int volleyIndex) {
        this.plugin = plugin;
        this.caster = caster;
        this.target = target;
        this.start = start.clone();
        this.volleyIndex = volleyIndex;
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

        Vector initialDirection = target.getEyeLocation().toVector().subtract(start.toVector()).normalize();
        Vector velocity = initialDirection.multiply(0.58D + (volleyIndex * 0.03D));
        velocity.setY(velocity.getY() + 0.14D + (volleyIndex * 0.03D));

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

                Vector homing = target.getEyeLocation().toVector().subtract(current.toVector()).normalize().multiply(0.045D);
                currentVelocity.add(homing);
                currentVelocity.setY(currentVelocity.getY() - 0.018D);
                if (currentVelocity.lengthSquared() > 0.64D) {
                    currentVelocity.normalize().multiply(0.8D);
                }

                orb.teleport(current.add(currentVelocity));
            }

            private void removeOrb() {
                orb.remove();
                cancel();
            }
        }.runTaskTimer(plugin, volleyIndex * 10L, 1L);
    }

    private void hitTarget(World world, Location location) {
        target.damage(3.0D, caster);
        target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0, true, true, true));
        world.playSound(location, Sound.ENTITY_GENERIC_HURT, 0.9F, 0.8F);
        world.playSound(location, Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.8F, 0.7F);
        burst(world, location);
    }

    private void spawnTrail(World world, Location location) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.2F);
        world.spawnParticle(Particle.DUST, location, 4, 0.08D, 0.08D, 0.08D, 0.0D, dust);
    }

    private void burst(World world, Location location) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(190, 0, 0), 1.4F);
        world.spawnParticle(Particle.DUST, location, 20, 0.45D, 0.45D, 0.45D, 0.0D, dust);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, location, 8, 0.35D, 0.25D, 0.35D, 0.02D);
    }
}
