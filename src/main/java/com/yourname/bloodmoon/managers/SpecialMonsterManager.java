package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.SpecialMonster;
import com.yourname.bloodmoon.mobs.SpecialMonster.MonsterType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Spawns and tracks Blood Moon special monsters.
 */
public final class SpecialMonsterManager {

    private final BloodMoonPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, SpecialMonster> monsters = new HashMap<>();
    private final Set<UUID> monsterIds = new HashSet<>();
    private final Map<MonsterType, Integer> weights = new EnumMap<>(MonsterType.class);
    private BukkitRunnable spawnTask;

    public SpecialMonsterManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
        for (MonsterType type : MonsterType.values()) {
            weights.put(type, type.getWeight());
        }
    }

    /**
     * Starts special monster spawning.
     */
    public void start() {
        if (!plugin.getConfigManager().areSpecialMonstersEnabled() || spawnTask != null) {
            return;
        }
        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                spawnPulse();
            }
        };
        spawnTask.runTaskTimer(plugin, 100L, plugin.getConfigManager().getSpecialMonsterSpawnPulseTicks());
    }

    /**
     * Stops special monster spawning without removing existing monsters.
     */
    public void stopSpawner() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
    }

    /**
     * Removes all tracked special monsters.
     */
    public void cleanupAll() {
        stopSpawner();
        for (SpecialMonster monster : new ArrayList<>(monsters.values())) {
            monster.cleanup();
        }
        monsters.clear();
        monsterIds.clear();
    }

    /**
     * Removes special monsters in one world.
     *
     * @param world world
     */
    public void cleanupWorld(World world) {
        for (SpecialMonster monster : new ArrayList<>(monsters.values())) {
            if (monster.getEntity().getWorld() == world) {
                monster.cleanup();
                unregisterMonster(monster.getUniqueId());
            }
        }
    }

    /**
     * Spawns one random special monster near a player.
     *
     * @param player player
     * @return spawned monster
     */
    public Optional<SpecialMonster> spawnRandomNear(Player player) {
        MonsterType type = chooseType();
        return spawnNear(player, type);
    }

    /**
     * Spawns a specific special monster near a player.
     *
     * @param player player
     * @param type monster type
     * @return spawned monster
     */
    public Optional<SpecialMonster> spawnNear(Player player, MonsterType type) {
        if (player == null || type == null || !player.isOnline()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player);
        if (location == null) {
            return Optional.empty();
        }
        SpecialMonster monster = new SpecialMonster(plugin, this, type, location);
        monsters.put(monster.getUniqueId(), monster);
        monsterIds.add(monster.getUniqueId());
        return Optional.of(monster);
    }

    /**
     * Handles a tracked monster death.
     *
     * @param event death event
     * @return true if handled
     */
    public boolean handleDeath(EntityDeathEvent event) {
        SpecialMonster monster = monsters.remove(event.getEntity().getUniqueId());
        if (monster == null) {
            return false;
        }
        monsterIds.remove(event.getEntity().getUniqueId());
        monster.handleDeath(event);
        return true;
    }

    /**
     * Gets a special monster controller for an entity.
     *
     * @param entity entity
     * @return monster or null
     */
    public SpecialMonster getMonster(Entity entity) {
        return entity == null ? null : monsters.get(entity.getUniqueId());
    }

    /**
     * Returns whether an entity is a special Blood Moon monster.
     *
     * @param entity entity
     * @return true if tracked
     */
    public boolean isSpecialMonster(Entity entity) {
        return getMonster(entity) != null;
    }

    /**
     * Unregisters a monster without removing the entity.
     *
     * @param uuid entity UUID
     */
    public void unregisterMonster(UUID uuid) {
        monsters.remove(uuid);
        monsterIds.remove(uuid);
    }

    /**
     * Returns immutable tracked monster IDs.
     *
     * @return IDs
     */
    public Set<UUID> getMonsterIds() {
        return Collections.unmodifiableSet(monsterIds);
    }

    /**
     * Returns active monster controllers.
     *
     * @return monsters
     */
    public List<SpecialMonster> getActiveMonsters() {
        return List.copyOf(monsters.values());
    }

    /**
     * Counts active monsters in a world.
     *
     * @param world world
     * @return count
     */
    public int countInWorld(World world) {
        int count = 0;
        for (SpecialMonster monster : monsters.values()) {
            if (monster.getEntity().getWorld() == world) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts special monsters near a player.
     *
     * @param player player
     * @param radius radius
     * @return count
     */
    public int countNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        double radiusSquared = radius * radius;
        int count = 0;
        for (SpecialMonster monster : monsters.values()) {
            if (monster.getEntity().getWorld() == player.getWorld()
                && monster.getEntity().getLocation().distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    private void spawnPulse() {
        for (World world : plugin.getBloodMoonManager().getActiveWorlds()) {
            int maxTotal = world.getPlayers().size() * plugin.getConfigManager().getSpecialMonstersMaxPerPlayer();
            if (maxTotal <= 0 || countInWorld(world) >= maxTotal) {
                continue;
            }
            for (Player player : world.getPlayers()) {
                if (random.nextDouble() > plugin.getConfigManager().getSpecialMonsterSpawnChance()) {
                    continue;
                }
                if (countNear(player, 96.0D) >= plugin.getConfigManager().getSpecialMonstersMaxPerPlayer()) {
                    continue;
                }
                spawnRandomNear(player);
            }
        }
    }

    private MonsterType chooseType() {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = random.nextInt(Math.max(1, total));
        int cursor = 0;
        for (Map.Entry<MonsterType, Integer> entry : weights.entrySet()) {
            cursor += entry.getValue();
            if (roll < cursor) {
                return entry.getKey();
            }
        }
        return MonsterType.BLOOD_WITCH;
    }

    private Location findSpawnLocationNear(Player player) {
        World world = player.getWorld();
        int radius = plugin.getConfigManager().getSpecialMonsterSpawnRadius();
        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            if (Math.abs(dx) < 12 && Math.abs(dz) < 12) {
                continue;
            }

            int x = player.getLocation().getBlockX() + dx;
            int z = player.getLocation().getBlockZ() + dz;
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
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
        if (location.getWorld() == null) {
            return false;
        }
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return ground.getType().isSolid()
            && feet.isPassable()
            && head.isPassable()
            && feet.getLightLevel() < 8
            && location.getY() > location.getWorld().getMinHeight()
            && location.getY() < location.getWorld().getMaxHeight() - 2;
    }
}
