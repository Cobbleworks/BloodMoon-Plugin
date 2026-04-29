package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.VampireNPC;
import com.yourname.bloodmoon.traits.VampireTrait;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.TraitFactory;
import net.citizensnpcs.api.trait.TraitInfo;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.mcmonkey.sentinel.SentinelIntegration;
import org.mcmonkey.sentinel.SentinelPlugin;
import org.mcmonkey.sentinel.SentinelTrait;

/**
 * Creates, tracks, and cleans Blood Moon Citizens NPCs and bat entities.
 */
public final class NPCManager {

    private final BloodMoonPlugin plugin;
    private final Set<Integer> activeNpcIds = new HashSet<>();
    private final Set<UUID> activeBatIds = new HashSet<>();
    private final Map<Integer, VampireNPC> vampires = new HashMap<>();
    private final Random random = new Random();
    private boolean citizensInitialized;
    private boolean sentinelIntegrationRegistered;

    public NPCManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Initializes Citizens trait registration and Sentinel integration.
     */
    public void initializeCitizens() {
        if (!isCitizensReady()) {
            return;
        }

        TraitFactory factory = CitizensAPI.getTraitFactory();
        if (factory != null && factory.getTraitClass("bloodmoon_vampire") == null) {
            factory.registerTrait(TraitInfo.create(VampireTrait.class).withName("bloodmoon_vampire"));
        }

        registerSentinelIntegration();
        citizensInitialized = true;
    }

    /**
     * Returns whether Citizens API is available.
     *
     * @return true when safe to call CitizensAPI
     */
    public boolean isCitizensReady() {
        return CitizensAPI.hasImplementation()
            && CitizensAPI.getNPCRegistry() != null
            && CitizensAPI.getTraitFactory() != null;
    }

    /**
     * Spawns a vampire near a specific player.
     *
     * @param player player to spawn near
     * @return created vampire, if any
     */
    public Optional<VampireNPC> spawnVampireNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player);
        if (location == null) {
            location = player.getLocation().clone().add(3.0D, 0.0D, 3.0D);
            location.setY(player.getWorld().getHighestBlockYAt(location) + 1.0D);
        }
        return spawnVampire(location, player);
    }

    /**
     * Spawns a vampire at an exact location.
     *
     * @param location spawn location
     * @param initialTarget first target
     * @return created vampire, if Citizens is ready
     */
    public Optional<VampireNPC> spawnVampire(Location location, Player initialTarget) {
        if (!citizensInitialized) {
            initializeCitizens();
        }
        if (!isCitizensReady() || location == null || location.getWorld() == null) {
            return Optional.empty();
        }

        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            return Optional.empty();
        }

        NPC npc = registry.createNPC(EntityType.PLAYER, "§4Vampire");
        VampireNPC vampire = new VampireNPC(plugin, npc, location, initialTarget);
        activeNpcIds.add(npc.getId());
        vampires.put(npc.getId(), vampire);
        return Optional.of(vampire);
    }

    /**
     * Checks every disguised vampire against a player for transformation.
     *
     * @param player player to test
     */
    public void checkDisguisedProximity(Player player) {
        for (VampireNPC vampire : List.copyOf(vampires.values())) {
            vampire.checkProximity(player);
        }
    }

    /**
     * Gets a vampire controller by NPC id.
     *
     * @param npcId Citizens NPC id
     * @return vampire or null
     */
    public VampireNPC getVampire(int npcId) {
        return vampires.get(npcId);
    }

    /**
     * Gets a vampire controller for a Citizens NPC.
     *
     * @param npc NPC
     * @return vampire or null
     */
    public VampireNPC getVampire(NPC npc) {
        return npc == null ? null : vampires.get(npc.getId());
    }

    /**
     * Gets a vampire controller from an entity, if it is a tracked NPC.
     *
     * @param entity entity to inspect
     * @return vampire or null
     */
    public VampireNPC getVampire(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        NPC npc = registry.getNPC(entity);
        return getVampire(npc);
    }

    /**
     * Returns whether an entity is one of this plugin's vampire NPCs.
     *
     * @param entity entity to inspect
     * @return true if tracked
     */
    public boolean isBloodMoonNpc(Entity entity) {
        return getVampire(entity) != null;
    }

    /**
     * Registers a bat entity for cleanup.
     *
     * @param bat bat entity
     */
    public void registerBat(Bat bat) {
        if (bat != null) {
            activeBatIds.add(bat.getUniqueId());
        }
    }

    /**
     * Unregisters a bat entity.
     *
     * @param bat bat entity
     */
    public void unregisterBat(Bat bat) {
        if (bat != null) {
            activeBatIds.remove(bat.getUniqueId());
        }
    }

    /**
     * Returns immutable active NPC ids.
     *
     * @return active ids
     */
    public Set<Integer> getActiveNpcIds() {
        return Collections.unmodifiableSet(activeNpcIds);
    }

    /**
     * Returns immutable active bat ids.
     *
     * @return active bat ids
     */
    public Set<UUID> getActiveBatIds() {
        return Collections.unmodifiableSet(activeBatIds);
    }

    /**
     * Returns active vampire controllers.
     *
     * @return vampires
     */
    public List<VampireNPC> getActiveVampires() {
        return List.copyOf(vampires.values());
    }

    /**
     * Counts active vampires in a world.
     *
     * @param world world
     * @return count
     */
    public int countVampires(World world) {
        int count = 0;
        for (VampireNPC vampire : vampires.values()) {
            Location location = vampire.getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts vampires near a player.
     *
     * @param player player
     * @param radius radius
     * @return count
     */
    public int countVampiresNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        double radiusSquared = radius * radius;
        for (VampireNPC vampire : vampires.values()) {
            Location location = vampire.getCurrentLocation();
            if (location != null
                && location.getWorld() == player.getWorld()
                && location.distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    /**
     * Unregisters a vampire id without destroying it again.
     *
     * @param npcId NPC id
     */
    public void unregisterVampire(int npcId) {
        activeNpcIds.remove(npcId);
        vampires.remove(npcId);
    }

    /**
     * Cleans every active NPC and bat.
     */
    public void cleanupAll() {
        for (VampireNPC vampire : new ArrayList<>(vampires.values())) {
            vampire.cleanup();
        }
        vampires.clear();
        activeNpcIds.clear();
        cleanupTrackedBats();
    }

    /**
     * Cleans NPCs in a specific world.
     *
     * @param world world to clean
     */
    public void cleanupWorld(World world) {
        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, VampireNPC> entry : new ArrayList<>(vampires.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(id -> {
            vampires.remove(id);
            activeNpcIds.remove(id);
        });
        activeBatIds.removeIf(uid -> {
            for (org.bukkit.entity.Entity e : world.getEntities()) {
                if (e.getUniqueId().equals(uid)) {
                    e.remove();
                    return true;
                }
            }
            return false;
        });
    }

    private void cleanupTrackedBats() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (activeBatIds.contains(entity.getUniqueId())) {
                    entity.remove();
                }
            }
        }
        activeBatIds.clear();
    }

    private Location findSpawnLocationNear(Player player) {
        World world = player.getWorld();
        int radius = plugin.getConfigManager().getVampireSpawnRadius();
        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            if (Math.abs(dx) < 10 && Math.abs(dz) < 10) {
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

    private void registerSentinelIntegration() {
        if (sentinelIntegrationRegistered) {
            return;
        }
        if (SentinelPlugin.instance == null) {
            return;
        }
        SentinelPlugin.instance.registerIntegration(new BloodMoonSentinelIntegration(this));
        sentinelIntegrationRegistered = true;
    }

    private static final class BloodMoonSentinelIntegration extends SentinelIntegration {

        private final NPCManager manager;

        private BloodMoonSentinelIntegration(NPCManager manager) {
            this.manager = manager;
        }

        @Override
        public String getTargetHelp() {
            return "bloodmoonvampire:active";
        }

        @Override
        public String[] getTargetPrefixes() {
            return new String[] {"bloodmoonvampire"};
        }

        @Override
        public boolean isTarget(LivingEntity entity, String prefix, String value) {
            return "bloodmoonvampire".equalsIgnoreCase(prefix)
                && ("active".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value))
                && manager.isBloodMoonNpc(entity);
        }

        @Override
        public boolean tryAttack(SentinelTrait sentinel, LivingEntity entity) {
            NPC npc = sentinel.getNPC();
            VampireNPC vampire = manager.getVampire(npc);
            if (vampire == null || entity == null) {
                return false;
            }
            vampire.nudgeTowardTarget();
            return false;
        }
    }
}
