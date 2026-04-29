package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.managers.SpecialMonsterManager;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Runtime controller for a named Blood Moon special monster.
 */
public final class SpecialMonster {

    public enum MonsterType {
        BLOOD_WITCH("§5Blood Witch", EntityType.WITCH, 34.0D, 18),
        HARVEST_SCARECROW("§6Harvest Scarecrow", EntityType.SKELETON, 38.0D, 17),
        FRANKENSTEIN_MONSTER("§2Frankenstein's Monster", EntityType.ZOMBIE, 64.0D, 14),
        CARNIVAL_CLOWN("§dCarnival Clown", EntityType.PILLAGER, 42.0D, 16),
        BLOOD_WEREWOLF("§8Blood Werewolf", EntityType.WOLF, 46.0D, 20);

        private final String displayName;
        private final EntityType entityType;
        private final double health;
        private final int weight;

        MonsterType(String displayName, EntityType entityType, double health, int weight) {
            this.displayName = displayName;
            this.entityType = entityType;
            this.health = health;
            this.weight = weight;
        }

        public String getDisplayName() {
            return displayName;
        }

        public EntityType getEntityType() {
            return entityType;
        }

        public double getHealth() {
            return health;
        }

        public int getWeight() {
            return weight;
        }
    }

    private static final Particle.DustOptions BLOOD = new Particle.DustOptions(Color.fromRGB(150, 0, 0), 1.2F);
    private static final Particle.DustOptions GOLD = new Particle.DustOptions(Color.fromRGB(190, 120, 20), 1.1F);
    private static final Particle.DustOptions GREEN = new Particle.DustOptions(Color.fromRGB(60, 160, 45), 1.1F);
    private static final Particle.DustOptions PINK = new Particle.DustOptions(Color.fromRGB(240, 70, 180), 1.1F);

    private final BloodMoonPlugin plugin;
    private final SpecialMonsterManager manager;
    private final MonsterType type;
    private final LivingEntity entity;
    private final Random random = new Random();
    private BukkitRunnable task;
    private int ticks;
    private int abilityCooldown;
    private boolean dead;

    public SpecialMonster(BloodMoonPlugin plugin, SpecialMonsterManager manager, MonsterType type, Location location) {
        this.plugin = plugin;
        this.manager = manager;
        this.type = type;
        this.entity = (LivingEntity) Objects.requireNonNull(location.getWorld()).spawnEntity(location, type.getEntityType());
        configureEntity();
        start();
    }

    /**
     * Gets the Bukkit entity UUID.
     *
     * @return entity UUID
     */
    public UUID getUniqueId() {
        return entity.getUniqueId();
    }

    /**
     * Gets the monster entity.
     *
     * @return monster entity
     */
    public LivingEntity getEntity() {
        return entity;
    }

    /**
     * Gets the special monster type.
     *
     * @return monster type
     */
    public MonsterType getType() {
        return type;
    }

    /**
     * Returns whether this monster is still active.
     *
     * @return true if active
     */
    public boolean isActive() {
        return !dead && entity.isValid() && !entity.isDead();
    }

    /**
     * Cancels AI and removes the entity.
     */
    public void cleanup() {
        dead = true;
        cancelTask();
        if (entity.isValid() && !entity.isDead()) {
            entity.remove();
        }
    }

    /**
     * Handles special death drops and XP.
     *
     * @param event death event
     */
    public void handleDeath(EntityDeathEvent event) {
        dead = true;
        cancelTask();
        event.getDrops().clear();
        event.setDroppedExp(random.nextInt(26) + 20);
        Location location = entity.getLocation();
        World world = entity.getWorld();
        switch (type) {
            case BLOOD_WITCH -> dropBloodWitchLoot(world, location, event);
            case HARVEST_SCARECROW -> dropScarecrowLoot(world, location, event);
            case FRANKENSTEIN_MONSTER -> dropFrankensteinLoot(world, location, event);
            case CARNIVAL_CLOWN -> dropClownLoot(world, location, event);
            case BLOOD_WEREWOLF -> dropWerewolfLoot(world, location, event);
        }
        world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 1.0D, 0.0D), 24, 0.6D, 0.6D, 0.6D, 0.0D, BLOOD);
    }

    private void configureEntity() {
        entity.setCustomName(type.getDisplayName());
        entity.setCustomNameVisible(true);
        entity.setRemoveWhenFarAway(false);
        applyHealth(type.getHealth());
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.setHelmetDropChance(0.0F);
            equipment.setChestplateDropChance(0.0F);
            equipment.setLeggingsDropChance(0.0F);
            equipment.setBootsDropChance(0.0F);
            equipment.setItemInMainHandDropChance(0.03F);
        }
        switch (type) {
            case BLOOD_WITCH -> configureBloodWitch();
            case HARVEST_SCARECROW -> configureScarecrow();
            case FRANKENSTEIN_MONSTER -> configureFrankenstein();
            case CARNIVAL_CLOWN -> configureClown();
            case BLOOD_WEREWOLF -> configureWerewolf();
        }
    }

    private void applyHealth(double health) {
        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            Objects.requireNonNull(entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)).setBaseValue(health);
        }
        entity.setHealth(Math.min(health, getMaxHealth()));
    }

    private double getMaxHealth() {
        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            return Objects.requireNonNull(entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue();
        }
        return type.getHealth();
    }

    private void configureBloodWitch() {
        entity.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false, false));
    }

    private void configureScarecrow() {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setHelmet(new ItemStack(Material.JACK_O_LANTERN));
        equipment.setChestplate(dyedLeather(Material.LEATHER_CHESTPLATE, Color.fromRGB(115, 68, 20)));
        equipment.setLeggings(dyedLeather(Material.LEATHER_LEGGINGS, Color.fromRGB(86, 54, 18)));
        equipment.setBoots(dyedLeather(Material.LEATHER_BOOTS, Color.fromRGB(55, 34, 14)));
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addUnsafeEnchantment(Enchantment.POWER, 1);
        equipment.setItemInMainHand(bow);
    }

    private void configureFrankenstein() {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(new ItemStack(Material.IRON_HELMET));
            equipment.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            ItemStack club = new ItemStack(Material.IRON_AXE);
            club.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1);
            equipment.setItemInMainHand(club);
        }
        entity.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, true, false, false));
    }

    private void configureClown() {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            equipment.setHelmet(dyedLeather(Material.LEATHER_HELMET, Color.fromRGB(220, 40, 40)));
            equipment.setChestplate(dyedLeather(Material.LEATHER_CHESTPLATE, Color.fromRGB(240, 230, 70)));
            equipment.setLeggings(dyedLeather(Material.LEATHER_LEGGINGS, Color.fromRGB(80, 170, 240)));
            equipment.setBoots(dyedLeather(Material.LEATHER_BOOTS, Color.fromRGB(210, 50, 180)));
            ItemStack crossbow = new ItemStack(Material.CROSSBOW);
            crossbow.addUnsafeEnchantment(Enchantment.QUICK_CHARGE, 1);
            equipment.setItemInMainHand(crossbow);
        }
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, true, false, false));
    }

    private void configureWerewolf() {
        if (entity instanceof Wolf wolf) {
            wolf.setAdult();
            wolf.setCollarColor(org.bukkit.DyeColor.RED);
        }
        entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, true, false, false));
    }

    private ItemStack dyedLeather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void start() {
        abilityCooldown = 60 + random.nextInt(80);
        task = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick() {
        if (!isActive()) {
            manager.unregisterMonster(entity.getUniqueId());
            cancelTask();
            return;
        }

        ticks++;
        abilityCooldown--;
        Player target = findNearestPlayer(34.0D);
        if (target != null) {
            steerIfNeeded(target);
        }
        if (ticks % 20 == 0) {
            ambientPulse();
        }
        if (target != null && abilityCooldown <= 0) {
            useAbility(target);
            abilityCooldown = abilityDelay();
        }
    }

    private Player findNearestPlayer(double radius) {
        double radiusSquared = radius * radius;
        Location location = entity.getLocation();
        return entity.getWorld().getPlayers().stream()
            .filter(player -> !player.isDead())
            .filter(player -> player.getLocation().distanceSquared(location) <= radiusSquared)
            .min(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    private void steerIfNeeded(Player target) {
        if (type != MonsterType.BLOOD_WEREWOLF && type != MonsterType.CARNIVAL_CLOWN) {
            return;
        }
        double distanceSquared = entity.getLocation().distanceSquared(target.getLocation());
        if (distanceSquared < 3.2D || distanceSquared > 30.0D * 30.0D) {
            return;
        }
        Vector direction = target.getLocation().toVector().subtract(entity.getLocation().toVector());
        if (direction.lengthSquared() < 0.01D) {
            return;
        }
        double speed = type == MonsterType.BLOOD_WEREWOLF ? 0.32D : 0.20D;
        entity.setVelocity(direction.normalize().multiply(speed).setY(entity.getVelocity().getY()));
    }

    private int abilityDelay() {
        return switch (type) {
            case BLOOD_WITCH -> 95 + random.nextInt(35);
            case HARVEST_SCARECROW -> 120 + random.nextInt(40);
            case FRANKENSTEIN_MONSTER -> 130 + random.nextInt(50);
            case CARNIVAL_CLOWN -> 90 + random.nextInt(45);
            case BLOOD_WEREWOLF -> 75 + random.nextInt(35);
        };
    }

    private void useAbility(Player target) {
        switch (type) {
            case BLOOD_WITCH -> castBloodHex(target);
            case HARVEST_SCARECROW -> castHarvestTerror(target);
            case FRANKENSTEIN_MONSTER -> castThunderStomp();
            case CARNIVAL_CLOWN -> castPrankBomb(target);
            case BLOOD_WEREWOLF -> castPounce(target);
        }
    }

    private void ambientPulse() {
        Location location = entity.getLocation().add(0.0D, 1.0D, 0.0D);
        switch (type) {
            case BLOOD_WITCH -> entity.getWorld().spawnParticle(Particle.WITCH, location, 4, 0.35D, 0.35D, 0.35D, 0.0D);
            case HARVEST_SCARECROW -> entity.getWorld().spawnParticle(Particle.DUST, location, 4, 0.35D, 0.35D, 0.35D, 0.0D, GOLD);
            case FRANKENSTEIN_MONSTER -> entity.getWorld().spawnParticle(Particle.CRIT, location, 4, 0.35D, 0.35D, 0.35D, 0.05D);
            case CARNIVAL_CLOWN -> entity.getWorld().spawnParticle(Particle.DUST, location, 5, 0.45D, 0.45D, 0.45D, 0.0D, PINK);
            case BLOOD_WEREWOLF -> entity.getWorld().spawnParticle(Particle.DUST, location, 4, 0.35D, 0.35D, 0.35D, 0.0D, BLOOD);
        }
    }

    private void castBloodHex(Player target) {
        Location location = entity.getLocation();
        World world = entity.getWorld();
        world.playSound(location, Sound.ENTITY_WITCH_CELEBRATE, 0.9F, 0.55F);
        world.spawnParticle(Particle.WITCH, location.clone().add(0.0D, 1.0D, 0.0D), 36, 0.9D, 0.7D, 0.9D, 0.08D);
        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 0, true, true, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 120, 0, true, true, true));
        target.damage(2.0D, entity);
        drawParticleLine(location.clone().add(0.0D, 1.2D, 0.0D), target.getEyeLocation(), Particle.WITCH, null);
    }

    private void castHarvestTerror(Player target) {
        Location location = entity.getLocation();
        World world = entity.getWorld();
        world.playSound(location, Sound.ENTITY_ENDERMAN_STARE, 0.8F, 0.45F);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= 9.0D * 9.0D) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 90, 2, true, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 45, 0, true, true, true));
                player.setVelocity(player.getVelocity().multiply(0.2D));
            }
        }
        world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 1.0D, 0.0D), 28, 0.9D, 0.6D, 0.9D, 0.0D, GOLD);
        drawParticleLine(location.clone().add(0.0D, 1.4D, 0.0D), target.getEyeLocation(), Particle.DUST, GOLD);
    }

    private void castThunderStomp() {
        Location location = entity.getLocation();
        World world = entity.getWorld();
        world.playSound(location, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0F, 0.5F);
        world.strikeLightningEffect(location);
        world.spawnParticle(Particle.FLASH, location.clone().add(0.0D, 1.0D, 0.0D), 2, 0.2D, 0.2D, 0.2D, 0.0D);
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) > 6.0D * 6.0D) {
                continue;
            }
            Vector knockback = player.getLocation().toVector().subtract(location.toVector());
            if (knockback.lengthSquared() < 0.01D) {
                knockback = new Vector(random.nextDouble() - 0.5D, 0.0D, random.nextDouble() - 0.5D);
            }
            player.damage(5.0D, entity);
            player.setVelocity(knockback.normalize().multiply(0.9D).setY(0.45D));
        }
    }

    private void castPrankBomb(Player target) {
        Location center = target.getLocation().clone();
        World world = target.getWorld();
        world.playSound(center, Sound.ENTITY_CREEPER_PRIMED, 0.8F, 1.5F);
        world.spawnParticle(Particle.DUST, center.clone().add(0.0D, 0.7D, 0.0D), 24, 0.8D, 0.4D, 0.8D, 0.0D, PINK);

        BukkitRunnable bomb = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isActive()) {
                    return;
                }
                world.spawnParticle(Particle.EXPLOSION, center, 4, 0.5D, 0.3D, 0.5D, 0.0D);
                world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9F, 0.8F);
                for (Player player : world.getPlayers()) {
                    if (player.getLocation().distanceSquared(center) <= 4.5D * 4.5D) {
                        player.damage(4.0D, entity);
                        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, true, true));
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 1, true, true, true));
                    }
                }
            }
        };
        bomb.runTaskLater(plugin, 28L);
    }

    private void castPounce(Player target) {
        Location location = entity.getLocation();
        World world = entity.getWorld();
        world.playSound(location, Sound.ENTITY_WOLF_HOWL, 1.0F, 0.7F);
        Vector direction = target.getLocation().toVector().subtract(location.toVector());
        if (direction.lengthSquared() > 0.01D) {
            entity.setVelocity(direction.normalize().multiply(1.2D).setY(0.35D));
        }
        BukkitRunnable impact = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isActive() || !target.isOnline() || target.isDead()) {
                    return;
                }
                if (target.getLocation().distanceSquared(entity.getLocation()) <= 3.4D * 3.4D) {
                    target.damage(6.0D, entity);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0, true, true, true));
                    plugin.getBleedEffect().applyBleed(target);
                    world.spawnParticle(Particle.DUST, target.getLocation().add(0.0D, 0.4D, 0.0D), 18, 0.4D, 0.25D, 0.4D, 0.0D, BLOOD);
                }
            }
        };
        impact.runTaskLater(plugin, 12L);
    }

    private void drawParticleLine(Location start, Location end, Particle particle, Object data) {
        World world = start.getWorld();
        if (world == null) {
            return;
        }
        Vector vector = end.toVector().subtract(start.toVector());
        double length = vector.length();
        if (length <= 0.1D) {
            return;
        }
        Vector step = vector.normalize().multiply(0.35D);
        Location cursor = start.clone();
        for (double traveled = 0.0D; traveled < length; traveled += 0.35D) {
            if (data instanceof Particle.DustOptions dust) {
                world.spawnParticle(particle, cursor, 1, 0.02D, 0.02D, 0.02D, 0.0D, dust);
            } else {
                world.spawnParticle(particle, cursor, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
            cursor.add(step);
        }
    }

    private void dropBloodWitchLoot(World world, Location location, EntityDeathEvent event) {
        event.getDrops().add(new ItemStack(Material.REDSTONE, random.nextInt(4) + 2));
        event.getDrops().add(new ItemStack(Material.GLOWSTONE_DUST, random.nextInt(3) + 1));
        if (random.nextDouble() < 0.35D) {
            event.getDrops().add(namedGlow(Material.FERMENTED_SPIDER_EYE, "§5Hexed Eye", List.of("§7Still warm with blood magic")));
        }
        world.playSound(location, Sound.ENTITY_WITCH_DEATH, 0.8F, 0.7F);
    }

    private void dropScarecrowLoot(World world, Location location, EntityDeathEvent event) {
        event.getDrops().add(new ItemStack(Material.WHEAT, random.nextInt(5) + 3));
        event.getDrops().add(new ItemStack(Material.PUMPKIN_SEEDS, random.nextInt(4) + 2));
        if (random.nextDouble() < 0.30D) {
            event.getDrops().add(namedGlow(Material.WHEAT, "§6Cursed Straw", List.of("§7It twitches when the moon is red")));
        }
        world.playSound(location, Sound.BLOCK_GRASS_BREAK, 0.9F, 0.6F);
    }

    private void dropFrankensteinLoot(World world, Location location, EntityDeathEvent event) {
        event.getDrops().add(new ItemStack(Material.IRON_INGOT, random.nextInt(3) + 1));
        event.getDrops().add(new ItemStack(Material.COPPER_INGOT, random.nextInt(4) + 2));
        event.getDrops().add(new ItemStack(Material.REDSTONE, random.nextInt(3) + 1));
        if (random.nextDouble() < 0.25D) {
            event.getDrops().add(namedGlow(Material.IRON_NUGGET, "§bCharged Bolt", List.of("§7A surgical bolt humming with stormlight")));
        }
        world.playSound(location, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8F, 0.5F);
    }

    private void dropClownLoot(World world, Location location, EntityDeathEvent event) {
        event.getDrops().add(new ItemStack(Material.SLIME_BALL, random.nextInt(3) + 1));
        event.getDrops().add(new ItemStack(Material.FIREWORK_STAR, 1));
        if (random.nextDouble() < 0.30D) {
            event.getDrops().add(namedGlow(Material.GOLD_NUGGET, "§dJester Token", List.of("§7Stamped with a smiling crescent")));
        }
        world.playSound(location, Sound.ENTITY_PILLAGER_DEATH, 0.8F, 1.2F);
    }

    private void dropWerewolfLoot(World world, Location location, EntityDeathEvent event) {
        event.getDrops().add(new ItemStack(Material.BONE, random.nextInt(4) + 2));
        event.getDrops().add(new ItemStack(Material.LEATHER, random.nextInt(3) + 1));
        if (random.nextDouble() < 0.35D) {
            event.getDrops().add(namedGlow(Material.LEATHER, "§8Werewolf Pelt", List.of("§7Thick fur marked by crimson moonlight")));
        }
        if (random.nextDouble() < 0.15D) {
            event.getDrops().add(new ItemStack(Material.RABBIT_FOOT, 1));
        }
        world.playSound(location, Sound.ENTITY_WOLF_DEATH, 0.8F, 0.7F);
    }

    private ItemStack namedGlow(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        item.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 1);
        return item;
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
