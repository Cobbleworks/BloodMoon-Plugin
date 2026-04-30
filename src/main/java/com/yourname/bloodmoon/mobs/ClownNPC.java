package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public final class ClownNPC {

    private enum State {
        JUGGLING,
        SNAPPED,
        MANIC,
        DEAD
    }

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Random random = new Random();
    private final List<BukkitRunnable> ownedTasks = new ArrayList<>();
    private final List<Rabbit> balloons = new ArrayList<>();
    private State state = State.JUGGLING;
    private BukkitRunnable controllerTask;
    private boolean cleanedUp;

    public ClownNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation) {
        this.plugin = plugin;
        this.npc = npc;
        npc.data().set("bloodmoon-clown", true);
        npc.setProtected(false);
        npc.spawn(spawnLocation);
        onNpcSpawn();
        startController();
    }

    public void onNpcSpawn() {
        LivingEntity living = getLivingEntity();
        if (living == null) {
            return;
        }
        double hp = plugin.getConfigManager().getClownHealth();
        if (living.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            living.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(hp);
        }
        living.setHealth(Math.min(hp, living.getHealth()));
    }

    public void triggerSnapFromDamage() {
        if (state == State.JUGGLING) {
            state = State.SNAPPED;
            LivingEntity clown = getLivingEntity();
            if (clown != null) {
                clown.getWorld().playSound(clown.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 3.0F, 0.3F);
            }
        }
    }

    public Location getCurrentLocation() {
        LivingEntity living = getLivingEntity();
        return living == null ? npc.getStoredLocation() : living.getLocation();
    }

    public void startDeathSequence() {
        if (state == State.DEAD) {
            return;
        }
        state = State.DEAD;
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FIREWORK, location, 50, 1.0D, 1.0D, 1.0D, 0.1D);
            world.playSound(location, Sound.ENTITY_WITCH_CELEBRATE, 1.2F, 0.3F);
            dropLoot(world, location);
            for (int i = 0; i < 3; i++) {
                spawnBalloonRabbit(location);
            }
        }
        BukkitRunnable cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanup();
            }
        };
        ownedTasks.add(cleanupTask);
        cleanupTask.runTaskLater(plugin, 20L);
    }

    public void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        if (controllerTask != null) {
            controllerTask.cancel();
        }
        for (BukkitRunnable task : List.copyOf(ownedTasks)) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
            }
        }
        for (Rabbit rabbit : List.copyOf(balloons)) {
            if (rabbit != null && rabbit.isValid()) {
                rabbit.remove();
            }
        }
        balloons.clear();
        if (npc.isSpawned()) {
            npc.despawn();
        }
        try {
            npc.destroy();
        } catch (Exception ignored) {
        }
        plugin.getNPCManager().unregisterClown(npc.getId());
    }

    private void startController() {
        controllerTask = new BukkitRunnable() {
            private long ticks;

            @Override
            public void run() {
                ticks++;
                LivingEntity clown = getLivingEntity();
                if (clown == null || state == State.DEAD) {
                    return;
                }
                if (state == State.JUGGLING && ticks % 80L == 0L) {
                    clown.getWorld().playSound(clown.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.8F, 1.25F);
                    clown.getWorld().spawnParticle(Particle.FIREWORK, clown.getLocation().add(0.0D, 1.0D, 0.0D), 15, 0.7D, 0.6D, 0.7D, 0.04D);
                    if (findNearestPlayer(clown.getLocation(), plugin.getConfigManager().getClownSnapRadius()) != null) {
                        triggerSnapFromDamage();
                    }
                    return;
                }
                if (state == State.SNAPPED || state == State.MANIC) {
                    maybeManic(clown);
                    castChaos(clown, ticks);
                }
            }
        };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void maybeManic(LivingEntity clown) {
        double max = clown.getAttribute(Attribute.GENERIC_MAX_HEALTH) == null ? plugin.getConfigManager().getClownHealth() : clown.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        if (max > 0.0D && clown.getHealth() / max <= plugin.getConfigManager().getClownManicHpThreshold()) {
            state = State.MANIC;
            clown.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 30, 1, true, true, true));
        }
    }

    private void castChaos(LivingEntity clown, long ticks) {
        int interval = state == State.MANIC ? 20 : 40;
        if (ticks % interval != 0L) {
            return;
        }
        Player target = findNearestPlayer(clown.getLocation(), 28.0D);
        if (target == null) {
            return;
        }
        int roll = random.nextInt(100);
        if (roll < 30) {
            target.damage(5.0D, clown);
            target.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 40, 0, true, true, true));
            clown.getWorld().playSound(clown.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 2.0F, 0.3F);
        } else if (roll < 55) {
            for (int i = 0; i < 5; i++) {
                Vector vec = target.getLocation().toVector().subtract(clown.getLocation().toVector()).normalize();
                vec.add(new Vector((i - 2) * 0.12D, 0.05D, (i - 2) * 0.05D));
                clown.getWorld().spawnArrow(clown.getEyeLocation(), vec, 2.0F, 0.0F).setDamage(5.0D);
            }
        } else if (roll < 80) {
            Location to = target.getLocation().clone().add(random.nextDouble() * 2.0D - 1.0D, 0.0D, random.nextDouble() * 2.0D - 1.0D);
            to.setY(target.getWorld().getHighestBlockYAt(to) + 1.0D);
            npc.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
            target.damage(3.0D, clown);
        } else {
            if (balloons.size() < plugin.getConfigManager().getClownBalloonCap()) {
                spawnBalloonRabbit(clown.getLocation());
            }
        }
    }

    private void spawnBalloonRabbit(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Rabbit rabbit = (Rabbit) world.spawnEntity(location.clone().add(random.nextDouble() * 2.0D - 1.0D, 0.3D, random.nextDouble() * 2.0D - 1.0D), EntityType.RABBIT);
        rabbit.setCustomName(null);
        rabbit.setCustomNameVisible(false);
        rabbit.setRabbitType(Rabbit.Type.THE_KILLER_BUNNY);
        balloons.add(rabbit);
    }

    private Player findNearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        double radiusSquared = radius * radius;
        return location.getWorld().getPlayers().stream()
            .filter(player -> !player.isDead())
            .filter(player -> player.getLocation().distanceSquared(location) <= radiusSquared)
            .findFirst().orElse(null);
    }

    private LivingEntity getLivingEntity() {
        return npc.isSpawned() && npc.getEntity() instanceof LivingEntity living ? living : null;
    }

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.70D) {
            world.dropItemNaturally(location, new ItemStack(Material.RED_DYE, random.nextInt(3) + 2));
        }
        if (random.nextDouble() <= 0.55D) {
            world.dropItemNaturally(location, new ItemStack(Material.SLIME_BALL, random.nextInt(3) + 1));
        }
        if (random.nextDouble() <= 0.45D) {
            world.dropItemNaturally(location, new ItemStack(Material.FIREWORK_ROCKET, random.nextInt(3) + 1));
        }
        if (random.nextDouble() <= 0.50D) {
            world.dropItemNaturally(location, new ItemStack(Material.RED_WOOL, random.nextInt(3) + 2));
        }
        if (random.nextDouble() <= 0.30D) {
            world.dropItemNaturally(location, new ItemStack(Material.PHANTOM_MEMBRANE, 1));
        }
        if (random.nextDouble() <= 0.12D) {
            world.dropItemNaturally(location, new ItemStack(Material.ENCHANTED_BOOK, 1));
        }
        if (random.nextDouble() <= 0.04D) {
            world.dropItemNaturally(location, new ItemStack(Material.TRIDENT, 1));
        }
        if (random.nextDouble() <= 0.08D) {
            world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));
        }
        if (random.nextDouble() <= 0.01D) {
            world.dropItemNaturally(location, new ItemStack(Material.NETHER_STAR, 1));
        }
        ExperienceOrb orb = world.spawn(location, ExperienceOrb.class);
        orb.setExperience(random.nextInt(21) + 30);
    }
}


