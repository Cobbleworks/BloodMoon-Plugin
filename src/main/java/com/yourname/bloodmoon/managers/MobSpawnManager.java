package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Spawns and equips extra vanilla hostile mobs during active Blood Moons.
 */
public final class MobSpawnManager {

    private static final List<EntityType> HOSTILE_TYPES = List.of(
        EntityType.ZOMBIE,
        EntityType.SKELETON,
        EntityType.SPIDER,
        EntityType.HUSK,
        EntityType.STRAY
    );

    private static final List<Material> HELMETS = List.of(
        Material.LEATHER_HELMET,
        Material.CHAINMAIL_HELMET,
        Material.IRON_HELMET,
        Material.GOLDEN_HELMET,
        Material.DIAMOND_HELMET
    );

    private static final List<Material> CHESTPLATES = List.of(
        Material.LEATHER_CHESTPLATE,
        Material.CHAINMAIL_CHESTPLATE,
        Material.IRON_CHESTPLATE,
        Material.GOLDEN_CHESTPLATE,
        Material.DIAMOND_CHESTPLATE
    );

    private static final List<Material> LEGGINGS = List.of(
        Material.LEATHER_LEGGINGS,
        Material.CHAINMAIL_LEGGINGS,
        Material.IRON_LEGGINGS,
        Material.GOLDEN_LEGGINGS,
        Material.DIAMOND_LEGGINGS
    );

    private static final List<Material> BOOTS = List.of(
        Material.LEATHER_BOOTS,
        Material.CHAINMAIL_BOOTS,
        Material.IRON_BOOTS,
        Material.GOLDEN_BOOTS,
        Material.DIAMOND_BOOTS
    );

    private static final List<Material> WEAPONS = List.of(
        Material.IRON_SWORD,
        Material.STONE_SWORD,
        Material.GOLDEN_SWORD
    );

    private final BloodMoonPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, BloodMobProfile> activeMobs = new HashMap<>();
    private BukkitRunnable spawnTask;
    private BukkitRunnable behaviorTask;

    private static final class BloodMobProfile {
        private final UUID id;
        private final EntityType type;
        private int abilityCooldown;
        private int ambientCooldown;

        private BloodMobProfile(UUID id, EntityType type, int abilityCooldown, int ambientCooldown) {
            this.id = id;
            this.type = type;
            this.abilityCooldown = abilityCooldown;
            this.ambientCooldown = ambientCooldown;
        }
    }

    public MobSpawnManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the repeating Blood Moon spawn pulse.
     */
    public void start() {
        stop();
        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                spawnPulse();
            }
        };
        spawnTask.runTaskTimer(plugin, 60L, 60L);

        behaviorTask = new BukkitRunnable() {
            @Override
            public void run() {
                behaviorPulse();
            }
        };
        behaviorTask.runTaskTimer(plugin, 20L, 10L);
    }

    /**
     * Stops all extra vanilla mob spawning.
     */
    public void stop() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
        if (behaviorTask != null) {
            behaviorTask.cancel();
            behaviorTask = null;
        }
        activeMobs.clear();
    }

    private void spawnPulse() {
        for (World world : plugin.getBloodMoonManager().getActiveWorlds()) {
            for (Player player : world.getPlayers()) {
                if (!player.isOnline() || player.isDead()) {
                    continue;
                }
                spawnExtraHostileNear(player);
                spawnExtraHostileNear(player);
            }
        }
    }

    private void behaviorPulse() {
        Iterator<Map.Entry<UUID, BloodMobProfile>> iterator = activeMobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BloodMobProfile> entry = iterator.next();
            BloodMobProfile profile = entry.getValue();
            LivingEntity entity = getLivingEntity(profile.id);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                iterator.remove();
                continue;
            }

            Player target = findNearestPlayer(entity, 26.0D);
            profile.abilityCooldown -= 10;
            profile.ambientCooldown -= 10;

            if (profile.ambientCooldown <= 0) {
                playAmbient(entity, profile.type);
                profile.ambientCooldown = 30 + random.nextInt(40);
            }

            if (target != null && profile.abilityCooldown <= 0) {
                useAbility(entity, profile, target);
                profile.abilityCooldown = baseCooldown(profile.type);
            }
        }
    }

    private LivingEntity getLivingEntity(UUID id) {
        org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
        if (entity instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    private void spawnExtraHostileNear(Player player) {
        Location location = findSpawnLocation(player);
        if (location == null) {
            return;
        }

        EntityType type = HOSTILE_TYPES.get(random.nextInt(HOSTILE_TYPES.size()));
        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, type);
        configureBloodMob(entity, type);
        if (random.nextDouble() <= 0.30D) {
            equip(entity);
        }
        activeMobs.put(entity.getUniqueId(), new BloodMobProfile(entity.getUniqueId(), type, baseCooldown(type), 20 + random.nextInt(40)));
    }

    private void configureBloodMob(LivingEntity entity, EntityType type) {
        entity.setRemoveWhenFarAway(true);
        entity.setCanPickupItems(false);

        switch (type) {
            case ZOMBIE -> {
                entity.setCustomName("§cBloodbound Zombie");
                applyAttribute(entity, Attribute.GENERIC_MAX_HEALTH, 30.0D);
                applyAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, 6.0D);
                entity.setHealth(Math.min(entity.getHealth(), 30.0D));
            }
            case HUSK -> {
                entity.setCustomName("§6Sand Reaver");
                applyAttribute(entity, Attribute.GENERIC_MAX_HEALTH, 32.0D);
                applyAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, 6.5D);
                entity.setHealth(Math.min(entity.getHealth(), 32.0D));
            }
            case SKELETON -> {
                entity.setCustomName("§7Bone Marksman");
                applyAttribute(entity, Attribute.GENERIC_MAX_HEALTH, 26.0D);
                entity.setHealth(Math.min(entity.getHealth(), 26.0D));
            }
            case STRAY -> {
                entity.setCustomName("§bFrost Marksman");
                applyAttribute(entity, Attribute.GENERIC_MAX_HEALTH, 28.0D);
                entity.setHealth(Math.min(entity.getHealth(), 28.0D));
            }
            case SPIDER -> {
                entity.setCustomName("§8Night Weaver");
                applyAttribute(entity, Attribute.GENERIC_MAX_HEALTH, 24.0D);
                applyAttribute(entity, Attribute.GENERIC_MOVEMENT_SPEED, 0.36D);
                entity.setHealth(Math.min(entity.getHealth(), 24.0D));
            }
            default -> {
            }
        }
        entity.setCustomNameVisible(true);
    }

    private void applyAttribute(LivingEntity entity, Attribute attribute, double value) {
        if (entity.getAttribute(attribute) != null) {
            entity.getAttribute(attribute).setBaseValue(value);
        }
    }

    private int baseCooldown(EntityType type) {
        return switch (type) {
            case ZOMBIE -> 55 + random.nextInt(30);
            case HUSK -> 60 + random.nextInt(30);
            case SKELETON -> 65 + random.nextInt(30);
            case STRAY -> 70 + random.nextInt(30);
            case SPIDER -> 50 + random.nextInt(25);
            default -> 80;
        };
    }

    private void playAmbient(LivingEntity entity, EntityType type) {
        Location location = entity.getLocation().add(0.0D, 1.0D, 0.0D);
        switch (type) {
            case ZOMBIE, HUSK -> entity.getWorld().spawnParticle(Particle.DUST, location, 5, 0.35D, 0.35D, 0.35D, 0.0D, new Particle.DustOptions(Color.fromRGB(140, 20, 20), 1.0F));
            case SKELETON, STRAY -> entity.getWorld().spawnParticle(Particle.SOUL, location, 4, 0.25D, 0.30D, 0.25D, 0.0D);
            case SPIDER -> entity.getWorld().spawnParticle(Particle.DUST, location, 5, 0.35D, 0.20D, 0.35D, 0.0D, new Particle.DustOptions(Color.fromRGB(55, 0, 75), 1.0F));
            default -> {
            }
        }
    }

    private Player findNearestPlayer(LivingEntity entity, double radius) {
        double radiusSquared = radius * radius;
        Location location = entity.getLocation();
        return entity.getWorld().getPlayers().stream()
            .filter(player -> !player.isDead())
            .filter(player -> player.getLocation().distanceSquared(location) <= radiusSquared)
            .min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    private void useAbility(LivingEntity entity, BloodMobProfile profile, Player target) {
        switch (profile.type) {
            case ZOMBIE -> useBloodCharge(entity, target);
            case HUSK -> {
                useBloodCharge(entity, target);
                useSandBurst(entity);
            }
            case SKELETON -> useArrowVolley(entity, target, false);
            case STRAY -> useArrowVolley(entity, target, true);
            case SPIDER -> useWebSnare(entity, target);
            default -> {
            }
        }
    }

    private void useBloodCharge(LivingEntity entity, Player target) {
        Vector direction = target.getLocation().toVector().subtract(entity.getLocation().toVector());
        if (direction.lengthSquared() < 0.01D) {
            return;
        }

        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.9F, 0.6F);
        entity.setVelocity(direction.normalize().multiply(0.95D).setY(0.28D));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!entity.isValid() || entity.isDead() || !target.isOnline() || target.isDead()) {
                    return;
                }
                if (target.getLocation().distanceSquared(entity.getLocation()) <= 2.6D * 2.6D) {
                    target.damage(4.0D, entity);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 0, true, true, true));
                    plugin.getBleedEffect().applyBleed(target);
                    entity.getWorld().spawnParticle(Particle.DUST, target.getLocation().add(0.0D, 0.5D, 0.0D), 14, 0.25D, 0.25D, 0.25D, 0.0D, new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.15F));
                }
            }
        }.runTaskLater(plugin, 10L);
    }

    private void useSandBurst(LivingEntity entity) {
        Location center = entity.getLocation();
        World world = entity.getWorld();
        world.playSound(center, Sound.BLOCK_SAND_BREAK, 0.8F, 0.8F);
        world.spawnParticle(Particle.CLOUD, center.clone().add(0.0D, 0.7D, 0.0D), 20, 1.1D, 0.45D, 1.1D, 0.06D);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= 5.0D * 5.0D) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, true, true, true));
            }
        }
    }

    private void useArrowVolley(LivingEntity entity, Player target, boolean frostVolley) {
        Location from = entity.getLocation().clone().add(0.0D, 1.5D, 0.0D);
        Vector base = target.getEyeLocation().toVector().subtract(from.toVector());
        if (base.lengthSquared() < 0.01D) {
            return;
        }

        World world = entity.getWorld();
        world.playSound(from, Sound.ENTITY_ARROW_SHOOT, 0.9F, frostVolley ? 0.7F : 1.0F);

        for (int i = -1; i <= 1; i++) {
            Vector velocity = base.clone().normalize().multiply(1.7D).add(new Vector(i * 0.08D, 0.02D, i * 0.08D));
            Arrow arrow = entity.launchProjectile(Arrow.class, velocity);
            arrow.setShooter(entity);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            arrow.setCritical(true);
            if (frostVolley) {
                arrow.setColor(Color.fromRGB(170, 220, 255));
            }
        }

        if (frostVolley) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, true, true));
            world.spawnParticle(Particle.SNOWFLAKE, target.getLocation().add(0.0D, 1.0D, 0.0D), 10, 0.35D, 0.35D, 0.35D, 0.02D);
        }
    }

    private void useWebSnare(LivingEntity entity, Player target) {
        Location feetLocation = target.getLocation().getBlock().getLocation();
        Block feet = feetLocation.getBlock();
        World world = target.getWorld();

        world.playSound(entity.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.85F, 0.7F);
        world.spawnParticle(Particle.ITEM_COBWEB, feetLocation.clone().add(0.5D, 0.2D, 0.5D), 16, 0.35D, 0.10D, 0.35D, 0.01D);
        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, true, true, true));

        if (feet.isPassable() && feet.getType() != Material.WATER && feet.getType() != Material.LAVA) {
            feet.setType(Material.COBWEB, false);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (feet.getType() == Material.COBWEB) {
                        feet.setType(Material.AIR, false);
                    }
                }
            }.runTaskLater(plugin, 50L);
        }
    }

    private Location findSpawnLocation(Player player) {
        World world = player.getWorld();
        int radius = plugin.getConfigManager().getVampireSpawnRadius();

        for (int attempt = 0; attempt < 20; attempt++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            if (Math.abs(dx) < 12 && Math.abs(dz) < 12) {
                continue;
            }

            int x = player.getLocation().getBlockX() + dx;
            int z = player.getLocation().getBlockZ() + dz;
            Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
            if (!chunk.isLoaded()) {
                continue;
            }

            Block ground = world.getHighestBlockAt(x, z);
            Location candidate = ground.getLocation().add(0.5D, 1.0D, 0.5D);
            if (isValidSpawnLocation(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isValidSpawnLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return ground.getType().isSolid()
            && feet.isPassable()
            && head.isPassable()
            && feet.getLightLevel() < 7
            && location.getY() > world.getMinHeight()
            && location.getY() < world.getMaxHeight() - 2;
    }

    private void equip(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }

        equipment.setHelmetDropChance(0.04F);
        equipment.setChestplateDropChance(0.04F);
        equipment.setLeggingsDropChance(0.04F);
        equipment.setBootsDropChance(0.04F);
        equipment.setItemInMainHandDropChance(0.05F);

        if (random.nextBoolean()) {
            equipment.setHelmet(randomArmor(HELMETS));
        }
        if (random.nextBoolean()) {
            equipment.setChestplate(randomArmor(CHESTPLATES));
        }
        if (random.nextBoolean()) {
            equipment.setLeggings(randomArmor(LEGGINGS));
        }
        if (random.nextBoolean()) {
            equipment.setBoots(randomArmor(BOOTS));
        }
        equipment.setItemInMainHand(randomWeapon());
    }

    private ItemStack randomArmor(List<Material> materials) {
        ItemStack item = new ItemStack(materials.get(random.nextInt(materials.size())));
        maybeEnchantArmor(item);
        return item;
    }

    private ItemStack randomWeapon() {
        ItemStack item;
        if (random.nextDouble() < 0.25D) {
            item = createBloodSickle();
        } else {
            item = new ItemStack(WEAPONS.get(random.nextInt(WEAPONS.size())));
        }
        maybeEnchantWeapon(item);
        return item;
    }

    private ItemStack createBloodSickle() {
        ItemStack item = new ItemStack(Material.IRON_HOE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§cBlood Sickle");
            item.setItemMeta(meta);
        }
        item.addUnsafeEnchantment(Enchantment.SHARPNESS, 2);
        item.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
        return item;
    }

    private void maybeEnchantWeapon(ItemStack item) {
        if (random.nextDouble() < 0.12D) {
            item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 1);
        }
        if (random.nextDouble() < 0.10D) {
            item.addUnsafeEnchantment(Enchantment.LOOTING, 1);
        }
        if (random.nextDouble() < 0.08D) {
            item.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);
        }
    }

    private void maybeEnchantArmor(ItemStack item) {
        if (item.getType() == Material.LEATHER_HELMET
            || item.getType() == Material.LEATHER_CHESTPLATE
            || item.getType() == Material.LEATHER_LEGGINGS
            || item.getType() == Material.LEATHER_BOOTS) {
            dyeLeather(item);
        }
        if (random.nextDouble() < 0.10D) {
            item.addUnsafeEnchantment(Enchantment.PROTECTION, 1);
        }
    }

    private void dyeLeather(ItemStack item) {
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta meta) {
            meta.setColor(Color.fromRGB(95, 0, 0));
            item.setItemMeta(meta);
        }
    }
}
