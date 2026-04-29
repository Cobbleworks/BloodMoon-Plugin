package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import java.util.List;
import java.util.Random;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

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
    private BukkitRunnable task;

    public MobSpawnManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the repeating Blood Moon spawn pulse.
     */
    public void start() {
        stop();
        task = new BukkitRunnable() {
            @Override
            public void run() {
                spawnPulse();
            }
        };
        task.runTaskTimer(plugin, 60L, 60L);
    }

    /**
     * Stops all extra vanilla mob spawning.
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
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

    private void spawnExtraHostileNear(Player player) {
        Location location = findSpawnLocation(player);
        if (location == null) {
            return;
        }

        EntityType type = HOSTILE_TYPES.get(random.nextInt(HOSTILE_TYPES.size()));
        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, type);
        if (random.nextDouble() <= 0.30D) {
            equip(entity);
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
