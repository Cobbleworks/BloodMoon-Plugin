package com.cobbleworks.bloodmoon.managers;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ClownNPC;
import com.cobbleworks.bloodmoon.mobs.GhostNPC;
import com.cobbleworks.bloodmoon.mobs.ScarecrowNPC;
import com.cobbleworks.bloodmoon.mobs.VampireNPC;
import com.cobbleworks.bloodmoon.mobs.WerewolfNPC;
import com.cobbleworks.bloodmoon.mobs.WitchNPC;
import com.cobbleworks.bloodmoon.mobs.ZombieNPC;
import com.cobbleworks.bloodmoon.traits.ClownTrait;
import com.cobbleworks.bloodmoon.traits.GhostTrait;
import com.cobbleworks.bloodmoon.traits.ScarecrowTrait;
import com.cobbleworks.bloodmoon.traits.VampireTrait;
import com.cobbleworks.bloodmoon.traits.WerewolfTrait;
import com.cobbleworks.bloodmoon.traits.WitchTrait;
import com.cobbleworks.bloodmoon.traits.ZombieTrait;
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
import org.bukkit.entity.Zombie;
import org.bukkit.util.RayTraceResult;
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
    private final Map<Integer, WitchNPC> witches = new HashMap<>();
    private final Map<Integer, ZombieNPC> zombies = new HashMap<>();
    private final Map<Integer, ScarecrowNPC> scarecrows = new HashMap<>();
    private final Map<Integer, GhostNPC> ghosts = new HashMap<>();
    private final Map<Integer, WerewolfNPC> werewolves = new HashMap<>();
    private final Map<UUID, Location> shamblingZombies = new HashMap<>();
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
        if (factory != null && factory.getTraitClass("bloodmoon_clown") == null) {
            factory.registerTrait(TraitInfo.create(ClownTrait.class).withName("bloodmoon_clown"));
        }
        if (factory != null && factory.getTraitClass("bloodmoon_zombie") == null) {
            factory.registerTrait(TraitInfo.create(ZombieTrait.class).withName("bloodmoon_zombie"));
        }
        if (factory != null && factory.getTraitClass("bloodmoon_witch") == null) {
            factory.registerTrait(TraitInfo.create(WitchTrait.class).withName("bloodmoon_witch"));
        }
        if (factory != null && factory.getTraitClass("bloodmoon_scarecrow") == null) {
            factory.registerTrait(TraitInfo.create(ScarecrowTrait.class).withName("bloodmoon_scarecrow"));
        }
        if (factory != null && factory.getTraitClass("bloodmoon_ghost") == null) {
            factory.registerTrait(TraitInfo.create(GhostTrait.class).withName("bloodmoon_ghost"));
        }
        if (factory != null && factory.getTraitClass("bloodmoon_werewolf") == null) {
            factory.registerTrait(TraitInfo.create(WerewolfTrait.class).withName("bloodmoon_werewolf"));
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

    public Optional<ZombieNPC> spawnZombieNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        if (countZombiesNear(player, 96.0D) >= plugin.getConfigManager().getZombieMaxPerPlayer()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player, plugin.getConfigManager().getZombieSpawnRadius());
        if (location == null) {
            return Optional.empty();
        }
        return spawnZombie(location, player);
    }

    public Optional<WitchNPC> spawnWitchNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        if (countWitchesNear(player, 96.0D) >= plugin.getConfigManager().getWitchMaxPerPlayer()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player, plugin.getConfigManager().getWitchSpawnRadius());
        if (location == null) {
            return Optional.empty();
        }
        return spawnWitch(location, player);
    }

    public Optional<ScarecrowNPC> spawnScarecrowNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        if (countScarecrowsNear(player, 96.0D) >= plugin.getConfigManager().getScarecrowMaxPerPlayer()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player, plugin.getConfigManager().getScarecrowSpawnRadius());
        if (location == null) {
            return Optional.empty();
        }
        return spawnScarecrow(location, player);
    }

    public Optional<GhostNPC> spawnGhostNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        if (countGhostsNear(player, 96.0D) >= plugin.getConfigManager().getGhostMaxPerPlayer()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player, plugin.getConfigManager().getGhostSpawnRadius());
        if (location == null) {
            return Optional.empty();
        }
        return spawnGhost(location, player);
    }

    public Optional<WerewolfNPC> spawnWerewolfNear(Player player) {
        if (player == null || !player.isOnline()) {
            return Optional.empty();
        }
        if (countWerewolvesNear(player, 96.0D) >= plugin.getConfigManager().getWerewolfMaxPerPlayer()) {
            return Optional.empty();
        }
        Location location = findSpawnLocationNear(player, plugin.getConfigManager().getWerewolfSpawnRadius());
        if (location == null) {
            return Optional.empty();
        }
        return spawnWerewolf(location, player);
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

    public boolean spawnShamblingZombie(Location location, Player initialTarget) {
        return spawnZombie(location, initialTarget).isPresent();
    }

    public Optional<WitchNPC> spawnWitch(Location location, Player initialTarget) {
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

        NPC npc = registry.createNPC(EntityType.PLAYER, "§5Witch");
        WitchNPC controller = new WitchNPC(plugin, npc, location, initialTarget);
        activeNpcIds.add(npc.getId());
        witches.put(npc.getId(), controller);
        return Optional.of(controller);
    }

    public Optional<ScarecrowNPC> spawnScarecrow(Location location, Player initialTarget) {
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

        NPC npc = registry.createNPC(EntityType.PLAYER, "§2Scarecrow");
        ScarecrowNPC controller = new ScarecrowNPC(plugin, npc, location, initialTarget);
        activeNpcIds.add(npc.getId());
        scarecrows.put(npc.getId(), controller);
        return Optional.of(controller);
    }

    public Optional<GhostNPC> spawnGhost(Location location, Player initialTarget) {
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

        NPC npc = registry.createNPC(EntityType.PLAYER, "§fGhost");
        GhostNPC controller = new GhostNPC(plugin, npc, location, initialTarget);
        activeNpcIds.add(npc.getId());
        ghosts.put(npc.getId(), controller);
        return Optional.of(controller);
    }

    public Optional<WerewolfNPC> spawnWerewolf(Location location, Player initialTarget) {
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

        NPC npc = registry.createNPC(EntityType.PLAYER, "§8Werewolf");
        WerewolfNPC controller = new WerewolfNPC(plugin, npc, location, initialTarget);
        activeNpcIds.add(npc.getId());
        werewolves.put(npc.getId(), controller);
        return Optional.of(controller);
    }

    public Optional<ZombieNPC> spawnZombie(Location location, Player initialTarget) {
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

        NPC npc = registry.createNPC(EntityType.PLAYER, "§2Infected");
        ZombieNPC controller = new ZombieNPC(plugin, npc, location, initialTarget);
        activeNpcIds.add(npc.getId());
        zombies.put(npc.getId(), controller);
        return Optional.of(controller);
    }

    public Optional<ZombieNPC> transformShamblingZombie(Zombie shambling, Player aggressor) {
        if (shambling == null || !shambling.isValid()) {
            return Optional.empty();
        }
        Location spawn = shambling.getLocation().clone();
        shamblingZombies.remove(shambling.getUniqueId());
        shambling.remove();

        if (!citizensInitialized) {
            initializeCitizens();
        }
        if (!isCitizensReady()) {
            return Optional.empty();
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            return Optional.empty();
        }

        NPC npc = registry.createNPC(EntityType.PLAYER, "§2Infected");
        ZombieNPC controller = new ZombieNPC(plugin, npc, spawn, aggressor);
        activeNpcIds.add(npc.getId());
        zombies.put(npc.getId(), controller);
        return Optional.of(controller);
    }

    public boolean isShamblingZombie(Entity entity) {
        return entity instanceof Zombie && shamblingZombies.containsKey(entity.getUniqueId());
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

    public ZombieNPC getZombie(int npcId) {
        return zombies.get(npcId);
    }

    public ZombieNPC getZombie(NPC npc) {
        return npc == null ? null : zombies.get(npc.getId());
    }

    public ZombieNPC getZombie(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        return getZombie(registry.getNPC(entity));
    }

    public WitchNPC getWitch(int npcId) {
        return witches.get(npcId);
    }

    public WitchNPC getWitch(NPC npc) {
        return npc == null ? null : witches.get(npc.getId());
    }

    public WitchNPC getWitch(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        return getWitch(registry.getNPC(entity));
    }

    public ScarecrowNPC getScarecrow(int npcId) {
        return scarecrows.get(npcId);
    }

    public ScarecrowNPC getScarecrow(NPC npc) {
        return npc == null ? null : scarecrows.get(npc.getId());
    }

    public ScarecrowNPC getScarecrow(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        return getScarecrow(registry.getNPC(entity));
    }

    public GhostNPC getGhost(int npcId) {
        return ghosts.get(npcId);
    }

    public GhostNPC getGhost(NPC npc) {
        return npc == null ? null : ghosts.get(npc.getId());
    }

    public GhostNPC getGhost(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        return getGhost(registry.getNPC(entity));
    }

    public WerewolfNPC getWerewolf(int npcId) {
        return werewolves.get(npcId);
    }

    public WerewolfNPC getWerewolf(NPC npc) {
        return npc == null ? null : werewolves.get(npc.getId());
    }

    public WerewolfNPC getWerewolf(Entity entity) {
        if (entity == null || !entity.hasMetadata("NPC") || !isCitizensReady()) {
            return null;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null || !registry.isNPC(entity)) {
            return null;
        }
        return getWerewolf(registry.getNPC(entity));
    }

    public boolean isBloodMoonNpc(Entity entity) {
        return getVampire(entity) != null || getClown(entity) != null || getZombie(entity) != null || getWitch(entity) != null || getScarecrow(entity) != null || getGhost(entity) != null || getWerewolf(entity) != null;
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

    public List<ZombieNPC> getActiveZombies() {
        return List.copyOf(zombies.values());
    }

    public List<WitchNPC> getActiveWitches() {
        return List.copyOf(witches.values());
    }

    public List<ScarecrowNPC> getActiveScarecrows() {
        return List.copyOf(scarecrows.values());
    }

    public List<GhostNPC> getActiveGhosts() {
        return List.copyOf(ghosts.values());
    }

    public List<WerewolfNPC> getActiveWerewolves() {
        return List.copyOf(werewolves.values());
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

    public int countZombies(World world) {
        int count = 0;
        for (ZombieNPC zombie : zombies.values()) {
            Location location = zombie.getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                count++;
            }
        }
        for (UUID id : shamblingZombies.keySet()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getUniqueId().equals(id)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    public int countWitches(World world) {
        int count = 0;
        for (WitchNPC witch : witches.values()) {
            Location location = witch.getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                count++;
            }
        }
        return count;
    }

    public int countScarecrows(World world) {
        int count = 0;
        for (ScarecrowNPC scarecrow : scarecrows.values()) {
            Location location = scarecrow.getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                count++;
            }
        }
        return count;
    }

    public int countGhosts(World world) {
        int count = 0;
        for (GhostNPC ghost : ghosts.values()) {
            Location location = ghost.getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                count++;
            }
        }
        return count;
    }

    public int countWerewolves(World world) {
        int count = 0;
        for (WerewolfNPC werewolf : werewolves.values()) {
            Location location = werewolf.getCurrentLocation();
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

    public int countZombiesNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        double radiusSquared = radius * radius;
        for (ZombieNPC zombie : zombies.values()) {
            Location location = zombie.getCurrentLocation();
            if (location != null
                && location.getWorld() == player.getWorld()
                && location.distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        for (Entity entity : player.getWorld().getEntities()) {
            if (!shamblingZombies.containsKey(entity.getUniqueId())) {
                continue;
            }
            if (entity.getLocation().distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    public int countWitchesNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        double radiusSquared = radius * radius;
        for (WitchNPC witch : witches.values()) {
            Location location = witch.getCurrentLocation();
            if (location != null
                && location.getWorld() == player.getWorld()
                && location.distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    public int countScarecrowsNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        double radiusSquared = radius * radius;
        for (ScarecrowNPC scarecrow : scarecrows.values()) {
            Location location = scarecrow.getCurrentLocation();
            if (location != null
                && location.getWorld() == player.getWorld()
                && location.distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    public int countGhostsNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        double radiusSquared = radius * radius;
        for (GhostNPC ghost : ghosts.values()) {
            Location location = ghost.getCurrentLocation();
            if (location != null
                && location.getWorld() == player.getWorld()
                && location.distanceSquared(player.getLocation()) <= radiusSquared) {
                count++;
            }
        }
        return count;
    }

    public int countWerewolvesNear(Player player, double radius) {
        if (player == null) {
            return 0;
        }
        int count = 0;
        double radiusSquared = radius * radius;
        for (WerewolfNPC werewolf : werewolves.values()) {
            Location location = werewolf.getCurrentLocation();
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

    public void unregisterZombie(int npcId) {
        activeNpcIds.remove(npcId);
        zombies.remove(npcId);
    }

    public void unregisterWitch(int npcId) {
        activeNpcIds.remove(npcId);
        witches.remove(npcId);
    }

    public void unregisterScarecrow(int npcId) {
        activeNpcIds.remove(npcId);
        scarecrows.remove(npcId);
    }

    public void unregisterGhost(int npcId) {
        activeNpcIds.remove(npcId);
        ghosts.remove(npcId);
    }

    public void unregisterWerewolf(int npcId) {
        activeNpcIds.remove(npcId);
        werewolves.remove(npcId);
    }

    public void cleanupAll() {
        for (VampireNPC vampire : new ArrayList<>(vampires.values())) {
            vampire.cleanup();
        }
        for (ClownNPC clown : new ArrayList<>(clowns.values())) {
            clown.cleanup();
        }
        for (ZombieNPC zombie : new ArrayList<>(zombies.values())) {
            zombie.cleanup();
        }
        for (WitchNPC witch : new ArrayList<>(witches.values())) {
            witch.cleanup();
        }
        for (ScarecrowNPC scarecrow : new ArrayList<>(scarecrows.values())) {
            scarecrow.cleanup();
        }
        for (GhostNPC ghost : new ArrayList<>(ghosts.values())) {
            ghost.cleanup();
        }
        for (WerewolfNPC werewolf : new ArrayList<>(werewolves.values())) {
            werewolf.cleanup();
        }
        vampires.clear();
        clowns.clear();
        zombies.clear();
        witches.clear();
        scarecrows.clear();
        ghosts.clear();
        werewolves.clear();
        activeNpcIds.clear();
        cleanupTrackedBats();
        cleanupShamblingZombies();
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
        for (Map.Entry<Integer, ZombieNPC> entry : new ArrayList<>(zombies.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        for (Map.Entry<Integer, WitchNPC> entry : new ArrayList<>(witches.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        for (Map.Entry<Integer, ScarecrowNPC> entry : new ArrayList<>(scarecrows.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        for (Map.Entry<Integer, GhostNPC> entry : new ArrayList<>(ghosts.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        for (Map.Entry<Integer, WerewolfNPC> entry : new ArrayList<>(werewolves.entrySet())) {
            Location location = entry.getValue().getCurrentLocation();
            if (location != null && location.getWorld() == world) {
                entry.getValue().cleanup();
                toRemove.add(entry.getKey());
            }
        }
        toRemove.forEach(id -> {
            vampires.remove(id);
            clowns.remove(id);
            zombies.remove(id);
            witches.remove(id);
            scarecrows.remove(id);
            ghosts.remove(id);
            werewolves.remove(id);
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
        Set<UUID> removedShambling = new HashSet<>();
        world.getEntities().stream()
            .filter(e -> shamblingZombies.containsKey(e.getUniqueId()))
            .forEach(e -> {
                removedShambling.add(e.getUniqueId());
                e.remove();
            });
        removedShambling.forEach(shamblingZombies::remove);
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

    private void cleanupShamblingZombies() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (shamblingZombies.containsKey(entity.getUniqueId())) {
                    entity.remove();
                }
            }
        }
        shamblingZombies.clear();
    }

    private Location findSpawnLocationNear(Player player, int radius) {
        World world = player.getWorld();
        Location playerLocation = player.getLocation();

        // 1. Prefer the surface at the player's crosshair
        RayTraceResult ray = player.rayTraceBlocks(Math.max(radius, 48.0D));
        if (ray != null && ray.getHitBlock() != null) {
            Block hit = ray.getHitBlock();
            int x = hit.getX();
            int z = hit.getZ();
            Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
            if (chunk.isLoaded()) {
                Block ground = world.getHighestBlockAt(x, z);
                Location candidate = ground.getLocation().add(0.5D, 1.0D, 0.5D);
                // Ensure the candidate is at least 5 blocks away to avoid spawning on the player
                if (candidate.distanceSquared(playerLocation) >= 25.0D && isValidSpawnLocation(candidate)) {
                    return candidate;
                }
            }
        }

        // 2. Fallback: random position within radius, at least 24 blocks away
        double minDistanceSquared = 24.0D * 24.0D;
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


