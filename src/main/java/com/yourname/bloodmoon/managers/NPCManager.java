package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.ClownNPC;
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
 * Creates, tracks, and cleans vampire Citizens NPCs and disguise bats.
 */
public final class NPCManager {

    private final BloodMoonPlugin plugin;
    private final Set<Integer> activeNpcIds = new HashSet<>();
    private final Set<UUID> activeBatIds = new HashSet<>();
    private final Map<Integer, VampireNPC> vampires = new HashMap<>();
    private final Map<Integer, ClownNPC> clowns = new HashMap<>();
    private final Random random = new Random();
    private boolean citizensInitialized;
    private boolean sentinelIntegrationRegistered;

    public NPCManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

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

    public boolean isCitizensReady() {
        return CitizensAPI.hasImplementation()
            && CitizensAPI.getNPCRegistry() != null
            && CitizensAPI.getTraitFactory() != null;
    }

    public Optional<VampireNPC> spawnVampireNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        if (countVampiresNear(player, 96.0D) >= plugin.getConfigManager().getMaxVampiresPerPlayer()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player, plugin.getConfigManager().getVampireSpawnRadius());
        if (location == null) {
            return Optional.empty();
        }
        return spawnVampire(location, null);
    }

    public Optional<ClownNPC> spawnClownNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        if (countClownsNear(player, 96.0D) >= plugin.getConfigManager().getClownMaxPerPlayer()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player, plugin.getConfigManager().getClownSpawnRadius());
        if (location == null) {
            return Optional.empty();
        }
        return spawnClown(location);
    }

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

    public Optional<ClownNPC> spawnClown(Location location) {
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

        NPC npc = registry.createNPC(EntityType.PLAYER, "§dClown");
        ClownNPC clown = new ClownNPC(plugin, npc, location);
        activeNpcIds.add(npc.getId());
        clowns.put(npc.getId(), clown);
        return Optional.of(clown);
    }

    public void checkDisguisedProximity(Player player) {
        for (VampireNPC vampire : List.copyOf(vampires.values())) {
            vampire.checkProximity(player);
        }
    }

    public VampireNPC getVampire(int npcId) {
        return vampires.get(npcId);
    }

    public VampireNPC getVampire(NPC npc) {
        return npc == null ? null : vampires.get(npc.getId());
    }

    public VampireNPC getVampire(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        return getVampire(registry.getNPC(entity));
    }

    public ClownNPC getClown(int npcId) {
        return clowns.get(npcId);
    }

    public ClownNPC getClown(NPC npc) {
        return npc == null ? null : clowns.get(npc.getId());
    }

    public ClownNPC getClown(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        return getClown(registry.getNPC(entity));
    }

    public boolean isBloodMoonNpc(Entity entity) {
        return getVampire(entity) != null || getClown(entity) != null;
    }

    public void registerBat(Bat bat) {
        if (bat != null) {
            activeBatIds.add(bat.getUniqueId());
        }
    }

    public void unregisterBat(Bat bat) {
        if (bat != null) {
            activeBatIds.remove(bat.getUniqueId());
        }
    }

    public Set<Integer> getActiveNpcIds() {
        return Collections.unmodifiableSet(activeNpcIds);
    }

    public Set<UUID> getActiveBatIds() {
        return Collections.unmodifiableSet(activeBatIds);
    }

    public List<VampireNPC> getActiveVampires() {
        return List.copyOf(vampires.values());
    }

    public List<ClownNPC> getActiveClowns() {
        return List.copyOf(clowns.values());
    }

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

    public int countClownsNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        double radiusSquared = radius * radius;
        for (ClownNPC clown : clowns.values()) {
            Location location = clown.getCurrentLocation();
            if (location != null
                && location.getWorld() == player.getWorld()
                && location.distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    public void unregisterVampire(int npcId) {
        activeNpcIds.remove(npcId);
        vampires.remove(npcId);
    }

    public void unregisterClown(int npcId) {
        activeNpcIds.remove(npcId);
        clowns.remove(npcId);
    }

    public void cleanupAll() {
        for (VampireNPC vampire : new ArrayList<>(vampires.values())) {
            vampire.cleanup();
        }
        for (ClownNPC clown : new ArrayList<>(clowns.values())) {
            clown.cleanup();
        }
        vampires.clear();
        clowns.clear();
        activeNpcIds.clear();
        cleanupTrackedBats();
    }

    public void cleanupWorld(World world) {
        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, VampireNPC> entry : new ArrayList<>(vampires.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        for (Map.Entry<Integer, ClownNPC> entry : new ArrayList<>(clowns.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(id -> {
            vampires.remove(id);
            clowns.remove(id);
            activeNpcIds.remove(id);
        });
        activeBatIds.removeIf(uid -> {
            for (Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(uid)) {
                    entity.remove();
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

    private Location findSpawnLocationNear(Player player, int radius) {
        World world = player.getWorld();
        double minDistanceSquared = 24.0D * 24.0D;
        Location playerLocation = player.getLocation();
        for (int attempt = 0; attempt < 64; attempt++) {
            int dx = random.nextInt(radius * 2 + 1) - radius;
            int dz = random.nextInt(radius * 2 + 1) - radius;
            int x = playerLocation.getBlockX() + dx;
            int z = playerLocation.getBlockZ() + dz;
            Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
            if (!chunk.isLoaded()) {
                continue;
            }
            Block ground = world.getHighestBlockAt(x, z);
            Location candidate = ground.getLocation().add(0.5D, 1.0D, 0.5D);
            if (candidate.distanceSquared(playerLocation) < minDistanceSquared) {
                continue;
            }
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
