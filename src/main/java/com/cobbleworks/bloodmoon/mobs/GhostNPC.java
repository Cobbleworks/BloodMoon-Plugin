package com.cobbleworks.bloodmoon.mobs;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.traits.GhostTrait;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class GhostNPC {

    private static final Particle.DustOptions DUST_WHITE   = new Particle.DustOptions(Color.fromRGB(230, 230, 255), 1.2F);
    private static final Particle.DustOptions DUST_ICE     = new Particle.DustOptions(Color.fromRGB(100, 180, 255), 1.0F);
    private static final Particle.DustOptions DUST_CYAN    = new Particle.DustOptions(Color.fromRGB(  0, 200, 200), 1.1F);
    private static final Particle.DustOptions DUST_SOUL    = new Particle.DustOptions(Color.fromRGB( 90, 215, 195), 1.05F);
    private static final Particle.DustOptions DUST_PALE    = new Particle.DustOptions(Color.fromRGB(210, 220, 255), 0.85F);
    private static final Particle.DustOptions DUST_REDSTONE = new Particle.DustOptions(Color.fromRGB(220,  30,  30), 0.9F);

    private enum GhostState { HAUNTING, STALKING, RUSHING, CASTING, DEAD }

    private enum GhostAbility {
        SPECTRAL_SURGE(22),
        WAILING_SHRIEK(18),
        POLTERGEIST_THROW(20),
        ECHO(14),
        PARANORMAL_ACTIVITY(12);

        private final int weight;

        GhostAbility(int weight) {
            this.weight = weight;
        }

        public int getWeight() {
            return weight;
        }
    }

    private record AbilityWeight(GhostAbility ability, int weight) {}

    private static final int ITEM_SNATCH_COOLDOWN_TICKS = 180;
    private static final int COUNTERPLAY_WINDOW_TICKS   = 80;
    private static final int HAUNTING_TICKS             = 25;
    private static final int PERIODIC_REVEAL_INTERVAL   = 220;

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<GhostAbility, Integer> cooldowns = new EnumMap<>(GhostAbility.class);
    private final Map<GhostAbility, Integer> abilityUseCounts = new EnumMap<>(GhostAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private final List<ItemStack> stolenItems = new ArrayList<>();
    private final List<NPC> echoIllusions = new ArrayList<>();
    private final Map<String, BlockRevert> paranormalReverts = new HashMap<>();
    private final Deque<Location> trackedPath = new ArrayDeque<>();

    private GhostState state = GhostState.HAUNTING;
    private BukkitRunnable controllerTask;
    private Player target;
    private GhostAbility pendingAbility;
    private GhostState  stateBeforeCasting;
    private int castingTicks;
    private NPC controlledHost;
    private Location lastKnownLocation;
    private double lastPlayerDistSquared = Double.MAX_VALUE;
    private int stateTicks;
    private int vanishingTicks;
    private int phaseWalkTicks;
    private int itemSnatchCooldown;
    private int recentDamageTicks;
    private int stalkPauseTicks;
    private int stalkingStage;
    private int vulnerableTicks;
    private int periodicRevealCounter;
    private boolean cleaned;
    private boolean deathStarted;
    private boolean untargetable;
    private boolean forcedVisibleByLight;

    private record BlockRevert(Location loc, Material original) {}

    public GhostNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

    public NPC getNpc() {
        return npc;
    }

    public boolean isDead() {
        return state == GhostState.DEAD || cleaned || deathStarted;
    }

    public Location getCurrentLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : (lastKnownLocation != null ? lastKnownLocation.clone() : spawnLocation.clone());
    }

    public double getCurrentHealth() {
        LivingEntity entity = getLivingEntity();
        return entity == null ? plugin.getConfigManager().getGhostHealth() : Math.max(0.0D, entity.getHealth());
    }

    public double getMaximumHealth() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return plugin.getConfigManager().getGhostHealth();
        }
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? plugin.getConfigManager().getGhostHealth() : Math.max(1.0D, attr.getValue());
    }

    public void onTraitTick() {
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            lastKnownLocation = entity.getLocation();
        }
    }

    public void onNpcSpawn() {
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
        }
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        event.setCancelled(true);
        if (!(event.getTarget() instanceof Player player) || state == GhostState.DEAD) {
            return;
        }
        target = player;
    }

    public boolean isUntargetable() {
        return untargetable;
    }

    public double reduceIncomingDamage(double damage) {
        return phaseWalkTicks > 0 ? damage * 0.7D : damage;
    }

    public void onTakeDamage(double damage) {
        breakVanishing();
        recentDamageTicks = 60;
        vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS);
        if (damage >= 1.0D) {
            phaseWalkTicks = 0;
        }
    }

    public void startDeathSequence() {
        if (deathStarted) {
            return;
        }
        deathStarted = true;
        state = GhostState.DEAD;
        cancelControllerOnly();
        cancelTasks();
        revertParanormalBlocks();
        Location death = getCurrentLocation();
        World world = death.getWorld();
        if (world != null) {
            world.playSound(death, Sound.ENTITY_WITHER_DEATH, 0.7F, 1.8F);
            world.spawnParticle(Particle.DUST, death.clone().add(0, 1, 0), 40, 0.6, 0.7, 0.6, 0, DUST_WHITE);
            world.spawnParticle(Particle.SNOWFLAKE, death.clone().add(0, 1, 0), 25, 0.4, 0.5, 0.4, 0.05);
            for (ItemStack item : stolenItems) {
                if (item != null && item.getType() != Material.AIR) {
                    world.dropItemNaturally(death, item);
                }
            }
            stolenItems.clear();
            dropLoot(world, death);
            ExperienceOrb orb = world.spawn(death.clone().add(0, 0.25, 0), ExperienceOrb.class);
            orb.setExperience(25 + random.nextInt(15));
        }
        BukkitRunnable cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanup();
            }
        };
        tasks.add(cleanupTask);
        cleanupTask.runTaskLater(plugin, 60L);
    }

    public void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        cancelControllerOnly();
        cancelTasks();
        revertParanormalBlocks();
        endHostControl();
        destroyEchoes();
        LivingEntity ghostEntity = getLivingEntity();
        if (ghostEntity != null) {
            plugin.getOverheadHealthBarManager().removeBar(ghostEntity.getUniqueId());
        }
        if (npc.isSpawned()) {
            npc.despawn();
        }
        npc.destroy();
        plugin.getNPCManager().unregisterGhost(npc.getId());
    }

    private void configureNpc() {
        npc.data().set("bloodmoon-ghost", true);
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        GhostTrait trait = npc.getOrAddTrait(GhostTrait.class);
        trait.bind(this);
        configureSkin();
        configureSentinel();
        if (!npc.isSpawned()) {
            npc.spawn(spawnLocation.clone());
        }
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
            hideNameplate(entity);
        }
    }

    private void configureSkin() {
        String skinName = plugin.getConfigManager().getGhostSkinName();
        String texture = plugin.getConfigManager().getGhostSkinTexture();
        String signature = plugin.getConfigManager().getGhostSkinSignature();
        if ((skinName == null || skinName.isBlank()) && (texture == null || texture.isBlank())) {
            return;
        }
        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = npc.getOrAddTrait(skinTraitClass);
            skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setFetchDefaultSkin", boolean.class).invoke(skinTrait, false);
            if (texture != null && !texture.isBlank() && signature != null && !signature.isBlank()) {
                skinTraitClass.getMethod("setSkinPersistent", String.class, String.class, String.class)
                    .invoke(skinTrait, skinName, signature, texture);
                return;
            }
            if (skinName != null && !skinName.isBlank()) {
                skinTraitClass.getMethod("setSkinName", String.class, boolean.class).invoke(skinTrait, skinName, true);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply ghost skin: " + ex.getMessage());
        }
    }

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.65D) world.dropItemNaturally(location, new ItemStack(Material.PAPER,    1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.60D) world.dropItemNaturally(location, new ItemStack(Material.STRING,   1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.55D) world.dropItemNaturally(location, new ItemStack(Material.WHITE_WOOL, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.50D) world.dropItemNaturally(location, new ItemStack(Material.GLASS_BOTTLE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.45D) world.dropItemNaturally(location, new ItemStack(Material.BONE,     1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.40D) world.dropItemNaturally(location, new ItemStack(Material.SNOWBALL, 2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.35D) world.dropItemNaturally(location, new ItemStack(Material.SOUL_SAND, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.25D) world.dropItemNaturally(location, new ItemStack(Material.PHANTOM_MEMBRANE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.20D) world.dropItemNaturally(location, new ItemStack(Material.BLUE_ICE, 1));
        if (random.nextDouble() <= 0.15D) world.dropItemNaturally(location, new ItemStack(Material.ENDER_PEARL, 1));

        // Haunted Clock — custom name
        if (random.nextDouble() <= 0.08D) {
            ItemStack clock = new ItemStack(Material.CLOCK);
            org.bukkit.inventory.meta.ItemMeta m = clock.getItemMeta();
            if (m != null) {
                m.setDisplayName("§7Haunted Clock");
                m.setLore(java.util.List.of("§8Still ticking... backward."));
                clock.setItemMeta(m);
            }
            world.dropItemNaturally(location, clock);
        }

        // Haunted Compass — custom name
        if (random.nextDouble() <= 0.08D) {
            ItemStack compass = new ItemStack(Material.COMPASS);
            org.bukkit.inventory.meta.ItemMeta m = compass.getItemMeta();
            if (m != null) {
                m.setDisplayName("§7Haunted Compass");
                m.setLore(java.util.List.of("§8Points somewhere it shouldn't."));
                compass.setItemMeta(m);
            }
            world.dropItemNaturally(location, compass);
        }

        // Spirit Lamp — soul lantern
        if (random.nextDouble() <= 0.10D) {
            ItemStack lamp = new ItemStack(Material.SOUL_LANTERN);
            org.bukkit.inventory.meta.ItemMeta m = lamp.getItemMeta();
            if (m != null) {
                m.setDisplayName("§bSpirit Lamp");
                m.setLore(java.util.List.of("§7The flame within never truly dies."));
                lamp.setItemMeta(m);
            }
            world.dropItemNaturally(location, lamp);
        }

        if (random.nextDouble() <= 0.10D) world.dropItemNaturally(location, new ItemStack(Material.SPECTRAL_ARROW,   2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.GHAST_TEAR,       1));
        if (random.nextDouble() <= 0.10D) world.dropItemNaturally(location, new ItemStack(Material.EXPERIENCE_BOTTLE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.NAUTILUS_SHELL,   1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.CRYING_OBSIDIAN,  1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.ENCHANTED_BOOK,   1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.MUSIC_DISC_13,    1));

        // Experience
        ExperienceOrb orb = world.spawn(location.clone().add(0D, 0.25D, 0D), ExperienceOrb.class);
        orb.setExperience(25 + random.nextInt(21));
    }

    private void configureSentinel() {
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.setInvincible(false);
        sentinel.setHealth(plugin.getConfigManager().getGhostHealth());
        sentinel.health = plugin.getConfigManager().getGhostHealth();
        sentinel.damage = 0.0D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange = 0.0D;
        sentinel.armor = 0.0D;
        sentinel.protectFromIgnores = false;
        sentinel.allTargets = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
        sentinel.addIgnore("npcs");
    }

    private void hideNameplate(LivingEntity entity) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            if (board == null) {
                return;
            }
            Team team = board.getTeam("bm_hidden_npc");
            if (team == null) {
                team = board.registerNewTeam("bm_hidden_npc");
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            if (entity instanceof Player player) {
                team.addEntry(player.getName());
            }
        } catch (Exception ignored) {
        }
    }

    private void applyConfiguredHealth(LivingEntity entity) {
        double health = plugin.getConfigManager().getGhostHealth();
        var attribute = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attribute != null) {
            attribute.setBaseValue(health);
            entity.setHealth(Math.min(health, entity.getHealth()));
        }
    }

    private void startController() {
        controllerTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick() {
        if (cleaned || deathStarted) {
            return;
        }
        stateTicks++;
        cooldowns.replaceAll((key, value) -> Math.max(0, value - 1));
        if (itemSnatchCooldown > 0) {
            itemSnatchCooldown--;
        }
        if (recentDamageTicks > 0) {
            recentDamageTicks--;
        }
        if (stalkPauseTicks > 0) {
            stalkPauseTicks--;
        }
        if (vulnerableTicks > 0) {
            vulnerableTicks--;
            breakVanishing();
            setUntargetable(false);
        }

        // Periodic forced reveal — creates clear damage windows every ~11 seconds
        periodicRevealCounter++;
        if (periodicRevealCounter >= PERIODIC_REVEAL_INTERVAL && vulnerableTicks <= 0 && !forcedVisibleByLight
                && state != GhostState.RUSHING && state != GhostState.HAUNTING) {
            periodicRevealCounter = 0;
            vulnerableTicks = COUNTERPLAY_WINDOW_TICKS;
            breakVanishing();
            setUntargetable(false);
            announceVulnerabilityWindow();
        } else if (vulnerableTicks <= 0) {
            periodicRevealCounter = Math.min(periodicRevealCounter, PERIODIC_REVEAL_INTERVAL);
        }
        onTraitTick();
        updateLightRevealState();
        emitGhostTellParticles();

        tickVanishing();

        if (state == GhostState.DEAD || state == GhostState.RUSHING) {
            return;
        }

        if (state == GhostState.HAUNTING) {
            tickHaunting();
            return;
        }

        if (state == GhostState.CASTING) {
            tickCasting();
            return;
        }

        Player player = ensureTarget(64.0D);
        if (player == null) {
            player = findNearestPlayer(getCurrentLocation(), 64.0D);
            target = player;
        }
        if (player == null) {
            return;
        }

        trackPlayerPath(player);
        tickColdPresence();

        if (phaseWalkTicks > 0) {
            tickPhaseWalk(player);
            lastPlayerDistSquared = getCurrentLocation().distanceSquared(player.getLocation());
            return;
        }

        npc.getNavigator().cancelNavigation();
        npc.faceLocation(player.getEyeLocation());

        if (stateTicks % 100 == 0) {
            Location loc = getCurrentLocation();
            if (loc.getWorld() != null) {
                loc.getWorld().playSound(loc, Sound.AMBIENT_CAVE, 0.3F, 1.6F + random.nextFloat() * 0.2F);
            }
        }

        double distanceSquared = getCurrentLocation().distanceSquared(player.getLocation());
        tickStalking(player, distanceSquared);

        int abilityInterval = Math.max(16, (int) Math.round(34.0D * plugin.getBloodMoonManager().getAbilityCadenceMultiplier()));
        if (state == GhostState.STALKING && vulnerableTicks <= 0 && vanishingTicks <= 0 && stateTicks % abilityInterval == 0 && distanceSquared < 2000.0D) {
            GhostAbility ability = chooseAbility();
            if (ability != null) {
                startCasting(ability);
            }
        }

        lastPlayerDistSquared = distanceSquared;
    }

    private void tickStalking(Player player, double distanceSquared) {
        double currentDistance = Math.sqrt(distanceSquared);

        if (stalkPauseTicks > 0) {
            npc.getNavigator().cancelNavigation();
            npc.faceLocation(player.getEyeLocation());
            if (stateTicks % 8 == 0) {
                Location stare = getCurrentLocation().clone().add(0, 1, 0);
                World w = stare.getWorld();
                if (w != null) {
                    w.spawnParticle(Particle.DUST, stare, 1, 0.12, 0.18, 0.12, 0.0, DUST_WHITE);
                }
            }
            return;
        }

        LivingEntity entity = getLivingEntity();
        if (entity != null && currentDistance <= 18.0D && !entity.hasLineOfSight(player) && phaseWalkTicks <= 0 && random.nextDouble() <= 0.18D) {
            startPhaseWalk();
            return;
        }

        if (currentDistance <= 8.0D && itemSnatchCooldown <= 0 && recentDamageTicks <= 0) {
            startRush(player);
            return;
        }

        if (stateTicks % 28 == 0 && currentDistance > 8.0D) {
            teleportCloser(player, currentDistance);
            stalkingStage = Math.min(2, stalkingStage + 1);
            stalkPauseTicks = 16 + random.nextInt(8);
            if (stalkingStage >= 2 && itemSnatchCooldown <= 0 && recentDamageTicks <= 0) {
                startRush(player);
                stalkingStage = 0;
                return;
            }
            if (vulnerableTicks <= 0 && random.nextDouble() <= 0.15D) {
                startVanishing(10 + random.nextInt(8));
            }
            return;
        }

        if (currentDistance <= 6.0D) {
            if (random.nextDouble() <= 0.32D) {
                startRush(player);
            } else {
                teleportAway(player);
                vulnerableTicks = Math.max(vulnerableTicks, 40);
            }
            return;
        }
        if (stateTicks % 25 == 0
            && lastPlayerDistSquared < Double.MAX_VALUE
            && distanceSquared > lastPlayerDistSquared + 9.0D
            && currentDistance > 9.0D) {
            teleportCloser(player, currentDistance);
            return;
        }
        if (stateTicks % 100 == 0 && currentDistance > 14.0D) {
            teleportCloser(player, currentDistance);
        }

        if (currentDistance > 14.0D && stateTicks % 60 == 0) {
            stalkingStage = 0;
        }
    }

    private void teleportCloser(Player player, double currentDistance) {
        World world = player.getWorld();
        double newDistance = Math.max(8.0D, currentDistance - (4.0D + random.nextDouble() * 4.0D));
        Vector toGhost = getCurrentLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        if (toGhost.lengthSquared() < 0.001D) {
            toGhost = new Vector(1, 0, 0);
        }
        toGhost.normalize();

        double angleOffset = (random.nextDouble() - 0.5D) * Math.PI * 0.85D;
        double cos = Math.cos(angleOffset);
        double sin = Math.sin(angleOffset);
        Vector rotated = new Vector(
            toGhost.getX() * cos - toGhost.getZ() * sin,
            0,
            toGhost.getX() * sin + toGhost.getZ() * cos).normalize().multiply(newDistance);

        Location newLoc = player.getLocation().clone().add(rotated);
        int surfaceY = world.getHighestBlockYAt(newLoc.getBlockX(), newLoc.getBlockZ());
        newLoc.setY(surfaceY + 1.0D);
        if (Math.abs(newLoc.getY() - player.getLocation().getY()) > 12.0D) {
            return;
        }

        Location before = getCurrentLocation().clone();
        npc.teleport(newLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        world.spawnParticle(Particle.DUST, before.clone().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0, DUST_WHITE);
        world.spawnParticle(Particle.DUST, newLoc.clone().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0, DUST_ICE);
        world.playSound(newLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.25F, 1.8F);
        if (vulnerableTicks <= 0 && random.nextDouble() <= 0.18D) {
            startVanishing(18 + random.nextInt(10));
        }
    }

    private void teleportAway(Player player) {
        World world = player.getWorld();
        double distance = 15.0D + random.nextDouble() * 6.0D;
        double angle = random.nextDouble() * Math.PI * 2.0D;
        Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle)).multiply(distance);
        Location newLoc = player.getLocation().clone().add(dir);
        int surfaceY = world.getHighestBlockYAt(newLoc.getBlockX(), newLoc.getBlockZ());
        newLoc.setY(surfaceY + 1.0D);
        if (Math.abs(newLoc.getY() - player.getLocation().getY()) > 14.0D) {
            return;
        }

        npc.teleport(newLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        world.spawnParticle(Particle.DUST, newLoc.clone().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0, DUST_ICE);
        world.playSound(newLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.2F, 1.9F);
    }

    private void startRush(Player player) {
        if (state == GhostState.RUSHING) {
            return;
        }
        startVanishing(42);
        state = GhostState.RUSHING;

        LivingEntity caster = getLivingEntity();
        if (caster == null) {
            state = GhostState.STALKING;
            return;
        }

        World world = caster.getWorld();
        if (player.getWorld() != world) {
            state = GhostState.STALKING;
            return;
        }
        setUntargetable(true);
        world.playSound(caster.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.85F, 1.75F);
        world.spawnParticle(Particle.DUST, caster.getLocation().clone().add(0, 1, 0), 16, 0.2, 0.3, 0.2, 0, DUST_WHITE);

        BukkitRunnable rush = new BukkitRunnable() {
            private boolean stolen;
            private int ticks;

            @Override
            public void run() {
                ticks++;
                if (ticks > 58 || isDead()) {
                    if (player.isOnline() && !isDead()) {
                        teleportAway(player);
                    }
                    setUntargetable(false);
                    vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS);
                    state = GhostState.STALKING;
                    lastPlayerDistSquared = Double.MAX_VALUE;
                    cancel();
                    return;
                }

                Location current = getCurrentLocation();
                World currentWorld = current.getWorld();
                if (currentWorld != null) {
                    currentWorld.spawnParticle(Particle.DUST, current.clone().add(0, 1, 0), 5, 0.26, 0.34, 0.26, 0, DUST_ICE);
                    currentWorld.spawnParticle(Particle.SNOWFLAKE, current.clone().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0.05);
                }

                if (ticks == 10 && player.isOnline() && !player.isDead()) {
                    teleportBehindPlayer(player);
                    startVanishing(32);
                    if (currentWorld != null) {
                        currentWorld.playSound(getCurrentLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.9F, 0.45F);
                    }
                }

                if (ticks >= 24 && ticks % 3 == 0 && currentWorld != null) {
                    Location back = getCurrentLocation().clone().add(0, 1.1, 0);
                    currentWorld.spawnParticle(Particle.DUST, back, 4, 0.2, 0.25, 0.2, 0.0, DUST_CYAN);
                    currentWorld.playSound(back, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.15F, 0.6F + random.nextFloat() * 0.25F);
                }

                if (!stolen && ticks >= 38 && player.isOnline() && !player.isDead() && player.getWorld() == current.getWorld()) {
                    stolen = trySnatchAndThrow(player, caster);
                    setUntargetable(false);
                }
            }
        };
        tasks.add(rush);
        rush.runTaskTimer(plugin, 0L, 1L);
    }

    private GhostAbility chooseAbility() {
        if (target == null || !target.isOnline() || target.isDead()) {
            return null;
        }
        List<AbilityWeight> available = new ArrayList<>();
        for (GhostAbility ability : GhostAbility.values()) {
            if (cooldowns.getOrDefault(ability, 0) <= 0) {
                available.add(new AbilityWeight(ability, ability.getWeight()));
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        // Prefer underused abilities to keep the fight varied
        int minUses = available.stream()
            .mapToInt(aw -> abilityUseCounts.getOrDefault(aw.ability(), 0))
            .min().orElse(0);
        List<AbilityWeight> underused = available.stream()
            .filter(aw -> abilityUseCounts.getOrDefault(aw.ability(), 0) == minUses)
            .toList();
        if (!underused.isEmpty() && random.nextDouble() <= 0.55D) {
            return underused.get(random.nextInt(underused.size())).ability();
        }
        // Weighted random selection
        int totalWeight = available.stream().mapToInt(AbilityWeight::weight).sum();
        int roll = random.nextInt(totalWeight);
        int cursor = 0;
        for (AbilityWeight aw : available) {
            cursor += aw.weight();
            if (roll < cursor) {
                return aw.ability();
            }
        }
        return available.get(available.size() - 1).ability();
    }

    private void executeAbility(GhostAbility ability) {
        if (target == null || !target.isOnline() || target.isDead()) {
            return;
        }
        Player player = target;
        abilityUseCounts.merge(ability, 1, Integer::sum);
        switch (ability) {
            case SPECTRAL_SURGE      -> castSpectralSurge(player);
            case WAILING_SHRIEK      -> castWailingScream(player);
            case PARANORMAL_ACTIVITY -> castParanormalActivity(player);
            case ECHO                -> castEcho(player);
            case POLTERGEIST_THROW   -> castPoltergeistThrow(player);
        }
        cooldowns.put(ability, switch (ability) {
            case SPECTRAL_SURGE      -> 200;
            case WAILING_SHRIEK      -> 300;
            case PARANORMAL_ACTIVITY -> 320;
            case ECHO                -> 220;
            case POLTERGEIST_THROW   -> 180;
        });
    }

    private void castEcho(Player player) {
        if (trackedPath.size() < 12 || !CitizensAPI.hasImplementation()) {
            return;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            return;
        }

        List<Location> path = new ArrayList<>(trackedPath);
        NPC echo = registry.createNPC(org.bukkit.entity.EntityType.PLAYER, "");
        echo.data().set("nameplate-visible", false);
        echo.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        echo.setProtected(false);
        echo.spawn(path.get(0).clone());
        echoIllusions.add(echo);

        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = echo.getOrAddTrait(skinTraitClass);
            skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setFetchDefaultSkin", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setSkinName", String.class, boolean.class).invoke(skinTrait, player.getName(), true);
        } catch (ReflectiveOperationException ignored) {
        }

        if (echo.getEntity() instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setSilent(true);
            living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 4, false, false, false));
        }

        BukkitRunnable echoTask = new BukkitRunnable() {
            int index = 0;
            @Override
            public void run() {
                if (index >= path.size() || !echo.isSpawned() || echo.getEntity() == null || isDead()) {
                    destroyEcho(echo);
                    cancel();
                    return;
                }
                Location step = path.get(index++).clone();
                echo.teleport(step, PlayerTeleportEvent.TeleportCause.PLUGIN);
                World world = step.getWorld();
                if (world != null) {
                    world.spawnParticle(Particle.DUST, step.clone().add(0, 1, 0), 8, 0.2, 0.4, 0.2, 0, DUST_WHITE);
                    for (Monster monster : world.getNearbyEntities(step, 10.0D, 4.0D, 10.0D).stream()
                            .filter(Monster.class::isInstance).map(Monster.class::cast).toList()) {
                        monster.setTarget((LivingEntity) echo.getEntity());
                    }
                }
            }
        };
        tasks.add(echoTask);
        echoTask.runTaskTimer(plugin, 0L, 2L);
    }

    private void castPoltergeistThrow(Player player) {
        World world = player.getWorld();
        int count = 2 + random.nextInt(3);
        List<org.bukkit.entity.Item> projectiles = new ArrayList<>();
        for (org.bukkit.entity.Item nearby : world.getNearbyEntities(getCurrentLocation(), 8.0D, 4.0D, 8.0D).stream()
                .filter(org.bukkit.entity.Item.class::isInstance).map(org.bukkit.entity.Item.class::cast).limit(count).toList()) {
            projectiles.add(nearby);
        }
        while (projectiles.size() < count) {
            Material material = switch (random.nextInt(4)) {
                case 0 -> Material.COBBLESTONE;
                case 1 -> Material.BONE;
                case 2 -> Material.ROTTEN_FLESH;
                default -> Material.GRAY_WOOL;
            };
            org.bukkit.entity.Item item = world.dropItem(getCurrentLocation().add(0, 1.2, 0), new ItemStack(material));
            item.setPickupDelay(Integer.MAX_VALUE);
            projectiles.add(item);
        }

        world.playSound(getCurrentLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.8F, 0.8F);
        for (org.bukkit.entity.Item projectile : projectiles) {
            Vector velocity = player.getEyeLocation().toVector().subtract(projectile.getLocation().toVector()).normalize()
                .multiply(0.7D + random.nextDouble() * 0.18D);
            projectile.setVelocity(velocity);
            projectile.setMetadata("bloodmoon-ghost-throw", new FixedMetadataValue(plugin, true));
            BukkitRunnable throwTask = new BukkitRunnable() {
                int ticks = 0;
                @Override
                public void run() {
                    ticks++;
                    if (!projectile.isValid() || ticks > 40) {
                        projectile.remove();
                        cancel();
                        return;
                    }
                    if (player.isOnline() && !player.isDead() && projectile.getLocation().distanceSquared(player.getLocation()) <= 2.25D) {
                        player.damage(4.0D, getLivingEntity());
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true, true));
                        world.spawnParticle(Particle.ITEM, player.getLocation().add(0, 1, 0), 8, 0.3, 0.3, 0.3, new ItemStack(projectile.getItemStack().getType()));
                        projectile.remove();
                        cancel();
                    }
                }
            };
            tasks.add(throwTask);
            throwTask.runTaskTimer(plugin, 0L, 1L);
        }
    }

    private void castParanormalActivity(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        world.playSound(center, Sound.AMBIENT_CAVE, 0.9F, 0.6F + random.nextFloat() * 0.3F);
        world.playSound(center, Sound.BLOCK_LEVER_CLICK, 0.6F, 0.8F);
        world.spawnParticle(Particle.DUST, center.clone().add(0, 1.5, 0), 22, 1.5, 0.3, 1.5, 0, DUST_WHITE);

        int radius = 12;
        List<Block> levers = new ArrayList<>();
        List<Block> lamps = new ArrayList<>();
        List<Block> swapCandidates = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    Block block = world.getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    switch (block.getType()) {
                        case LEVER -> levers.add(block);
                        case REDSTONE_LAMP -> lamps.add(block);
                        case STONE, COBBLESTONE, GRAVEL, DIRT -> swapCandidates.add(block);
                        default -> {
                        }
                    }
                }
            }
        }

        for (Block lever : levers) {
            if (lever.getBlockData() instanceof Powerable powerable) {
                powerable.setPowered(!powerable.isPowered());
                lever.setBlockData(powerable, true);
                world.playSound(lever.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.7F, 1.0F);
            }
        }
        if (!levers.isEmpty()) {
            BukkitRunnable revertLevers = new BukkitRunnable() {
                @Override
                public void run() {
                    for (Block lever : levers) {
                        if (lever.getType() == Material.LEVER && lever.getBlockData() instanceof Powerable powerable) {
                            powerable.setPowered(!powerable.isPowered());
                            lever.setBlockData(powerable, true);
                        }
                    }
                }
            };
            tasks.add(revertLevers);
            revertLevers.runTaskLater(plugin, 60L + random.nextInt(40));
        }

        for (Block lamp : lamps) {
            if (lamp.getBlockData() instanceof Lightable lightable) {
                boolean originalLit = lightable.isLit();
                lightable.setLit(!originalLit);
                lamp.setBlockData(lightable, true);
                world.spawnParticle(Particle.END_ROD, lamp.getLocation().clone().add(0.5, 0.5, 0.5), 3, 0.2, 0.2, 0.2, 0.01);
                BukkitRunnable revertLamp = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (lamp.getType() == Material.REDSTONE_LAMP && lamp.getBlockData() instanceof Lightable revertLightable) {
                            revertLightable.setLit(originalLit);
                            lamp.setBlockData(revertLightable, true);
                        }
                    }
                };
                tasks.add(revertLamp);
                revertLamp.runTaskLater(plugin, 30L + random.nextInt(50));
            }
        }

        Collections.shuffle(swapCandidates, random);
        Material[] swapTargets = { Material.SOUL_SAND, Material.CRYING_OBSIDIAN, Material.TUFF, Material.MOSS_BLOCK };
        int swapCount = Math.min(6, swapCandidates.size());
        for (int index = 0; index < swapCount; index++) {
            Block block = swapCandidates.get(index);
            Material original = block.getType();
            Material replacement = swapTargets[random.nextInt(swapTargets.length)];
            String key = block.getX() + "," + block.getY() + "," + block.getZ();
            block.setType(replacement, false);
            paranormalReverts.put(key, new BlockRevert(block.getLocation().clone(), original));
            world.spawnParticle(Particle.DUST, block.getLocation().clone().add(0.5, 0.5, 0.5), 4, 0.3, 0.3, 0.3, 0, DUST_WHITE);
            BukkitRunnable revert = new BukkitRunnable() {
                @Override
                public void run() {
                    Block revertBlock = block.getLocation().getBlock();
                    if (revertBlock.getType() == replacement) {
                        revertBlock.setType(original, false);
                    }
                    paranormalReverts.remove(key);
                }
            };
            tasks.add(revert);
            revert.runTaskLater(plugin, 60L + random.nextInt(60));
        }
    }

    private void revertParanormalBlocks() {
        for (BlockRevert revert : paranormalReverts.values()) {
            revert.loc().getBlock().setType(revert.original(), false);
        }
        paranormalReverts.clear();
    }

    private void tickColdPresence() {
        Location current = getCurrentLocation();
        World world = current.getWorld();
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            double distanceSquared = player.getLocation().distanceSquared(current);
            if (distanceSquared <= 324.0D) {
                player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.0, 0), 1, 0.25, 0.3, 0.25, 0, DUST_REDSTONE);
                if (stateTicks % 20 == 0) {
                    player.getWorld().playSound(player.getLocation(), Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.25F, 1.2F + random.nextFloat() * 0.4F);
                }
                stirNearbyRedstone(player);
            }
            if (distanceSquared > 144.0D) {
                continue;
            }
            double distance = Math.sqrt(distanceSquared);
            int amplifier = distance <= 3.0D ? 3 : (distance <= 6.0D ? 2 : (distance <= 10.0D ? 1 : 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, amplifier, false, true, true));
            int freezeTicks = distance <= 3.0D ? 120 : (distance <= 6.0D ? 90 : (distance <= 10.0D ? 60 : 40));
            player.setFreezeTicks(Math.max(player.getFreezeTicks(), freezeTicks));
            player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 1, 0.4, 0.5, 0.4, 0.01);
            disturbCompass(player, current);
        }
    }

    private void stirNearbyRedstone(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material type = block.getType();
                    if (type == Material.REDSTONE_WIRE || type == Material.REDSTONE_TORCH || type == Material.REDSTONE_WALL_TORCH
                        || type == Material.REDSTONE_LAMP || type == Material.REPEATER || type == Material.COMPARATOR) {
                        world.spawnParticle(Particle.ELECTRIC_SPARK, block.getLocation().add(0.5, 0.45, 0.5), 1, 0.15, 0.1, 0.15, 0.01);
                        world.spawnParticle(Particle.DUST, block.getLocation().add(0.5, 0.45, 0.5), 1, 0.1, 0.08, 0.1, 0.0, DUST_REDSTONE);
                    }
                }
            }
        }
    }

    private void disturbCompass(Player player, Location current) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if ((main.getType() == Material.COMPASS || off.getType() == Material.COMPASS) && stateTicks % 6 == 0) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            Location fake = current.clone().add(Math.cos(angle) * 18.0D, 0, Math.sin(angle) * 18.0D);
            player.setCompassTarget(fake);
        }
    }

    private void trackPlayerPath(Player player) {
        if (stateTicks % 5 != 0) {
            return;
        }
        trackedPath.addLast(player.getLocation().clone());
        while (trackedPath.size() > 32) {
            trackedPath.removeFirst();
        }
    }

    private void startVanishing(int durationTicks) {
        if (forcedVisibleByLight) {
            return;
        }
        vanishingTicks = Math.max(vanishingTicks, durationTicks);
    }

    private void tickVanishing() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        if (forcedVisibleByLight) {
            vanishingTicks = 0;
            entity.removePotionEffect(PotionEffectType.INVISIBILITY);
            entity.setSilent(false);
            return;
        }
        if (vanishingTicks > 0) {
            vanishingTicks--;
            entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 10, 0, false, false, false));
            entity.setSilent(true);
        } else {
            entity.setSilent(false);
        }
    }

    private void breakVanishing() {
        vanishingTicks = 0;
    }

    /**
     * Emits a dramatic visual burst and sound when the ghost becomes vulnerable,
     * clearly telegraphing the damage window to nearby players.
     */
    private void announceVulnerabilityWindow() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location loc   = entity.getLocation();
        World    world = loc.getWorld();
        if (world == null) {
            return;
        }
        world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,          0.8F, 1.7F);
        world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME,     0.6F, 0.5F);
        world.spawnParticle(Particle.DUST,      loc.clone().add(0D, 1.0D, 0D), 30, 0.6D, 0.7D, 0.6D, 0D, DUST_WHITE);
        world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0D, 1.2D, 0D), 18, 0.4D, 0.5D, 0.4D, 0.06D);
        world.spawnParticle(Particle.END_ROD,   loc.clone().add(0D, 0.5D, 0D), 12, 0.5D, 0.3D, 0.5D, 0.04D);
        // Ring burst outward
        for (int i = 0; i < 20; i++) {
            double angle  = (Math.PI * 2.0D / 20.0D) * i;
            double rx     = Math.cos(angle) * 1.2D;
            double rz     = Math.sin(angle) * 1.2D;
            world.spawnParticle(Particle.DUST, loc.clone().add(rx, 1.0D, rz), 1, 0.02D, 0.02D, 0.02D, 0D, DUST_ICE);
        }
    }

    private void startPhaseWalk() {
        if (forcedVisibleByLight) {
            return;
        }
        phaseWalkTicks = 20;
        breakVanishing();
    }

    private void tickPhaseWalk(Player player) {
        if (forcedVisibleByLight) {
            phaseWalkTicks = 0;
            return;
        }
        if (phaseWalkTicks <= 0) {
            return;
        }
        phaseWalkTicks--;
        Location current = getCurrentLocation();
        Vector delta = player.getLocation().toVector().subtract(current.toVector());
        if (delta.lengthSquared() > 0.001D) {
            Location next = current.clone().add(delta.normalize().multiply(0.35D));
            npc.teleport(next, PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (next.getWorld() != null) {
                next.getWorld().spawnParticle(Particle.DUST, next.clone().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0, DUST_ICE);
            }
        }
    }

    private void emitGhostTellParticles() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location loc   = entity.getLocation();
        World    world = loc.getWorld();
        if (world == null) {
            return;
        }
        // Soft aura around body — always visible at close range
        if (stateTicks % 3 == 0) {
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 1, 0.18D, 0.24D, 0.18D, 0D, DUST_WHITE);
        }
        if (stateTicks % 7 == 0) {
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.2D, 0), 1, 0.14D, 0.18D, 0.14D, 0D, DUST_ICE);
        }
        // Drifting ethereal glow
        if (stateTicks % 12 == 0) {
            world.spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.5D, 0), 1, 0.25D, 0.1D, 0.25D, 0.01D);
        }
        // Snowflake drift when fully visible (not vanishing)
        if (vanishingTicks <= 0 && stateTicks % 8 == 0) {
            world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0, 1.5D, 0), 1, 0.25D, 0.25D, 0.25D, 0.02D);
        }
    }

    private void updateLightRevealState() {
        Location current = getCurrentLocation();
        World world = current.getWorld();
        if (world == null) {
            forcedVisibleByLight = false;
            return;
        }

        forcedVisibleByLight = isWithinTorchOrLanternLight(current);
        if (!forcedVisibleByLight) {
            return;
        }

        breakVanishing();
        phaseWalkTicks = 0;
        setUntargetable(false);
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            entity.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private boolean isWithinTorchOrLanternLight(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return false;
        }

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();

        // Soul Lantern has a dedicated 10-block range — they specifically ward against ghosts
        int soulRadius = 10;
        for (int dx = -soulRadius; dx <= soulRadius; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                for (int dz = -soulRadius; dz <= soulRadius; dz++) {
                    Block block = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material type = block.getType();
                    if (type != Material.SOUL_LANTERN && type != Material.SOUL_TORCH && type != Material.SOUL_WALL_TORCH) {
                        continue;
                    }
                    double distanceSquared = block.getLocation().add(0.5D, 0.5D, 0.5D).distanceSquared(origin);
                    if (distanceSquared <= 100.0D) { // 10 block radius
                        return true;
                    }
                }
            }
        }

        // Regular torches and lanterns also reveal the ghost at 8 blocks
        int torchRadius = 8;
        for (int dx = -torchRadius; dx <= torchRadius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -torchRadius; dz <= torchRadius; dz++) {
                    Block block = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material type = block.getType();
                    if (type != Material.TORCH && type != Material.WALL_TORCH && type != Material.LANTERN) {
                        continue;
                    }
                    double distanceSquared = block.getLocation().add(0.5D, 0.5D, 0.5D).distanceSquared(origin);
                    if (distanceSquared <= 64.0D) { // 8 block radius
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean trySnatchAndThrow(Player player, LivingEntity caster) {
        if (itemSnatchCooldown > 0) {
            return false;
        }
        if (recentDamageTicks > 0) {
            return false;
        }
        int slot = findSnatchSlot(player.getInventory());
        if (slot < 0) {
            return false;
        }

        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return false;
        }

        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.5F, 0.55F);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 12, 0.25, 0.35, 0.25, 0, DUST_WHITE);
        if (plugin.hasBossMessages(player.getUniqueId())) {
            player.sendMessage("§7§oThe ghost reaches for your item... hit it to interrupt!");
        }

        BukkitRunnable windup = new BukkitRunnable() {
            int ticks;

            @Override
            public void run() {
                ticks++;
                Location ghostLoc = getCurrentLocation().clone().add(0, 1.05D, 0);
                World ghostWorld = ghostLoc.getWorld();
                if (ghostWorld != null) {
                    ghostWorld.spawnParticle(Particle.DUST, ghostLoc, 3, 0.2, 0.25, 0.2, 0.0, DUST_CYAN);
                }

                if (isDead() || !player.isOnline() || player.isDead() || recentDamageTicks > 0
                    || ghostLoc.distanceSquared(player.getLocation().add(0, 1, 0)) > 16.0D) {
                    vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS + 20);
                    itemSnatchCooldown = Math.max(itemSnatchCooldown, 40);
                    cancel();
                    return;
                }

                if (ticks < 16) {
                    return;
                }

                ItemStack live = player.getInventory().getItem(slot);
                if (live == null || live.getType().isAir()) {
                    cancel();
                    return;
                }

                ItemStack stolen = live.clone();
                stolen.setAmount(1);
                if (live.getAmount() <= 1) {
                    player.getInventory().setItem(slot, new ItemStack(Material.AIR));
                } else {
                    live.setAmount(live.getAmount() - 1);
                    player.getInventory().setItem(slot, live);
                }

                itemSnatchCooldown = ITEM_SNATCH_COOLDOWN_TICKS;
                world.playSound(player.getLocation(), Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.6F, 0.6F);
                world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 8, 0.22, 0.32, 0.22, 0, DUST_WHITE);

                stolenItems.add(stolen);
                player.damage(4.0D, caster);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, 1, false, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 55, 0, false, true, true));
                world.playSound(player.getLocation(), Sound.ENTITY_ALLAY_ITEM_TAKEN, 0.75F, 0.5F);
                world.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.45F, 1.8F);
                world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 14, 0.24, 0.34, 0.24, 0, DUST_CYAN);
                cancel();
            }
        };
        tasks.add(windup);
        windup.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private void teleportBehindPlayer(Player player) {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location pl = player.getLocation();
        Vector backwards = pl.getDirection().setY(0).normalize().multiply(-1.35D);
        if (backwards.lengthSquared() < 0.001D) {
            backwards = new Vector(0, 0, -1.35D);
        }
        Location behind = pl.clone().add(backwards);
        behind.setY(behind.getWorld().getHighestBlockYAt(behind) + 1.0D);
        behind.setYaw(entity.getLocation().getYaw());
        npc.teleport(behind, PlayerTeleportEvent.TeleportCause.PLUGIN);
        World world = behind.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.DUST, behind.clone().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.0, DUST_WHITE);
            world.playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 0.25F, 1.9F);
        }
    }

    private int findSnatchSlot(PlayerInventory inventory) {
        List<Integer> filledSlots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) {
                filledSlots.add(i);
            }
        }
        return filledSlots.isEmpty() ? -1 : filledSlots.get(random.nextInt(filledSlots.size()));
    }

    private void setUntargetable(boolean untargetable) {
        this.untargetable = untargetable;
        npc.setProtected(untargetable);
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            entity.setCollidable(!untargetable);
            if (untargetable) {
                entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, false));
            }
        }
    }

    private void endHostControl() {
        if (controlledHost != null && controlledHost.isSpawned() && controlledHost.getEntity() != null) {
            try {
                if (controlledHost.hasTrait(SentinelTrait.class)) {
                    SentinelTrait sentinel = controlledHost.getOrAddTrait(SentinelTrait.class);
                    if (target != null) {
                        sentinel.removeTarget("player:" + target.getName());
                    }
                } else {
                    controlledHost.getNavigator().cancelNavigation();
                }
            } catch (Exception ignored) {
            }
            Location end = controlledHost.getEntity().getLocation();
            end.getWorld().playSound(end, Sound.ENTITY_WITHER_AMBIENT, 0.4F, 1.7F);
            end.getWorld().spawnParticle(Particle.SMOKE, end.clone().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.02);
        }
        controlledHost = null;
        setUntargetable(false);
    }

    private void destroyEcho(NPC echo) {
        if (echo == null) {
            return;
        }
        echoIllusions.remove(echo);
        if (echo.isSpawned()) {
            echo.despawn();
        }
        echo.destroy();
    }

    private void destroyEchoes() {
        for (NPC echo : new ArrayList<>(echoIllusions)) {
            destroyEcho(echo);
        }
    }

    private LivingEntity getLivingEntity() {
        if (!npc.isSpawned() || npc.getEntity() == null) {
            return null;
        }
        return npc.getEntity() instanceof LivingEntity livingEntity ? livingEntity : null;
    }

    private Player ensureTarget(double range) {
        if (target != null && target.isOnline() && !target.isDead()) {
            Location targetLocation = target.getLocation();
            Location currentLocation = getCurrentLocation();
            if (targetLocation.getWorld() == currentLocation.getWorld()
                && targetLocation.distanceSquared(currentLocation) <= range * range) {
                return target;
            }
        }
        return findNearestPlayer(getCurrentLocation(), range);
    }

    private Player findNearestPlayer(Location location, double range) {
        if (location.getWorld() == null) {
            return null;
        }
        Player nearest = null;
        double bestDistanceSquared = range * range;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.isDead() || !player.isOnline()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance < bestDistanceSquared) {
                bestDistanceSquared = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private void cancelControllerOnly() {
        if (controllerTask != null) {
            try {
                controllerTask.cancel();
            } catch (Exception ignored) {
            }
            controllerTask = null;
        }
    }

    private void cancelTasks() {
        for (BukkitRunnable task : tasks) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
        }
        tasks.clear();
    }

    // =========================================================================
    // Haunting intro state — ghost materialises from nothing
    // =========================================================================

    private void tickHaunting() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        World world = entity.getWorld();
        Location loc  = entity.getLocation();

        if (stateTicks == 1) {
            world.playSound(loc, Sound.ENTITY_PHANTOM_AMBIENT, 0.7F, 1.4F);
            world.playSound(loc, Sound.AMBIENT_CAVE,           0.5F, 1.7F);
            startVanishing(15);
            setUntargetable(true);
        }

        // Materialisation spiral — converging inward as progress advances
        if (stateTicks % 3 == 0) {
            double progress = (double) stateTicks / HAUNTING_TICKS;
            double angle    = stateTicks * 0.55D;
            for (int i = 0; i < 4; i++) {
                double offset = angle + (i * (Math.PI / 2.0D));
                double radius = 1.2D - progress * 0.8D;
                Location ring = loc.clone().add(
                    Math.cos(offset) * radius, 0.8D + progress * 0.6D, Math.sin(offset) * radius);
                world.spawnParticle(Particle.DUST,     ring, 1, 0.03D, 0.03D, 0.03D, 0D, DUST_WHITE);
                world.spawnParticle(Particle.SNOWFLAKE, ring, 1, 0.05D, 0.05D, 0.05D, 0.01D);
            }
        }

        if (stateTicks >= HAUNTING_TICKS) {
            state = GhostState.STALKING;
            stateTicks = 0;
            breakVanishing();
            setUntargetable(false);
            // Materialisation reveal
            world.playSound(loc, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.8F, 0.45F);
            world.spawnParticle(Particle.DUST,      loc.clone().add(0D, 1.0D, 0D), 20, 0.5D, 0.6D, 0.5D, 0D, DUST_WHITE);
            world.spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0D, 1.0D, 0D), 12, 0.4D, 0.5D, 0.4D, 0.04D);
        }
    }

    // =========================================================================
    // Casting state  (telltale → wind-up → execution)
    // =========================================================================

    private void startCasting(GhostAbility ability) {
        if (state == GhostState.CASTING || state == GhostState.DEAD) {
            return;
        }
        pendingAbility    = ability;
        stateBeforeCasting = state;
        state             = GhostState.CASTING;
        stateTicks        = 0;
        castingTicks = switch (ability) {
            case SPECTRAL_SURGE      -> 12;
            case WAILING_SHRIEK      -> 14;
            case PARANORMAL_ACTIVITY -> 16;
            case ECHO                -> 10;
            case POLTERGEIST_THROW   ->  8;
        };
        npc.getNavigator().cancelNavigation();

        Location loc   = getCurrentLocation();
        World    world = loc.getWorld();
        if (world == null) {
            return;
        }
        switch (ability) {
            case SPECTRAL_SURGE -> {
                world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 1.9F);
                world.playSound(loc, Sound.ENTITY_PHANTOM_AMBIENT,   0.65F, 1.6F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 14, 0.4D, 0.5D, 0.4D, 0D, DUST_ICE);
            }
            case WAILING_SHRIEK -> {
                world.playSound(loc, Sound.ENTITY_PHANTOM_HURT,   0.8F, 0.55F);
                world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 0.45F, 1.85F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.1D, 0D), 18, 0.55D, 0.4D, 0.55D, 0D, DUST_WHITE);
                world.spawnParticle(Particle.SNOWFLAKE,
                    loc.clone().add(0D, 1.0D, 0D), 6, 0.3D, 0.2D, 0.3D, 0.04D);
            }
            case PARANORMAL_ACTIVITY -> {
                world.playSound(loc, Sound.AMBIENT_CAVE,       0.7F, 0.6F + random.nextFloat() * 0.2F);
                world.playSound(loc, Sound.BLOCK_LEVER_CLICK,  0.55F, 0.75F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 12, 0.35D, 0.35D, 0.35D, 0D, DUST_REDSTONE);
            }
            case ECHO -> {
                world.playSound(loc, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.65F, 0.5F);
                world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5F, 0.6F + random.nextFloat() * 0.2F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 10, 0.3D, 0.4D, 0.3D, 0D, DUST_PALE);
            }
            case POLTERGEIST_THROW -> {
                world.playSound(loc, Sound.ENTITY_BREEZE_SHOOT, 0.75F, 0.8F);
                world.playSound(loc, Sound.ENTITY_PHANTOM_AMBIENT, 0.5F, 1.5F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 10, 0.3D, 0.4D, 0.3D, 0D, DUST_CYAN);
            }
        }
    }

    private void tickCasting() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        if (target != null && target.isOnline() && !target.isDead()) {
            npc.faceLocation(target.getEyeLocation());
        }
        runCastingParticles();
        updateCastingAnimation();
        if (stateTicks < castingTicks) {
            return;
        }
        GhostAbility ability = pendingAbility;
        pendingAbility     = null;
        resetCastingAnimation();
        state      = stateBeforeCasting;
        stateTicks = 0;
        castingTicks = 0;
        if (ability != null) {
            executeAbility(ability);
        }
    }

    // =========================================================================
    // Casting particle system
    // =========================================================================

    private void runCastingParticles() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location base  = entity.getLocation();
        World    world = base.getWorld();
        if (world == null) {
            return;
        }
        if (pendingAbility == null) {
            spawnGenericCastingRing(world, base);
            return;
        }
        switch (pendingAbility) {
            case SPECTRAL_SURGE      -> spawnSpectralSurgeCastingParticles(world, base);
            case WAILING_SHRIEK      -> spawnWailCastingParticles(world, base);
            case PARANORMAL_ACTIVITY -> spawnParanormalCastingParticles(world, base);
            case ECHO                -> spawnEchoCastingParticles(world, base);
            case POLTERGEIST_THROW   -> spawnPoltergeistCastingParticles(world, base);
        }
    }

    private void spawnGenericCastingRing(World world, Location base) {
        double angle = stateTicks * 0.42D;
        for (int step = 0; step < 4; step++) {
            double offset = angle + (step * (Math.PI / 2.0D));
            Location point = base.clone().add(
                Math.cos(offset) * 0.8D,
                0.5D + (stateTicks % 16) * 0.04D,
                Math.sin(offset) * 0.8D);
            world.spawnParticle(Particle.DUST, point, 1, 0.02D, 0.02D, 0.02D, 0D, DUST_WHITE);
        }
    }

    private void spawnSpectralSurgeCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST,      focus, 6, 0.2D, 0.25D, 0.2D, 0D, DUST_ICE);
        world.spawnParticle(Particle.SNOWFLAKE, focus, 2, 0.2D, 0.2D,  0.2D, 0.03D);
        if (target != null && target.isOnline() && !target.isDead()) {
            Location eye  = base.clone().add(0D, 1.3D, 0D);
            Location dest = target.getEyeLocation();
            for (int s = 0; s < 3; s++) {
                double progress = ((stateTicks * 0.15D) + (s * 0.25D)) % 1.0D;
                Location point  = eye.clone().add(
                    dest.clone().subtract(eye).toVector().multiply(progress));
                world.spawnParticle(Particle.DUST, point, 1, 0.04D, 0.04D, 0.04D, 0D, DUST_ICE);
            }
        }
        if (stateTicks % 10 == 0) {
            world.playSound(base, Sound.ENTITY_ENDERMAN_TELEPORT,
                0.2F, 1.9F + random.nextFloat() * 0.1F);
        }
    }

    private void spawnWailCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.62D;
        for (int i = 0; i < 6; i++) {
            double offset = angle + (i * (Math.PI / 3.0D));
            double radius = 0.6D + Math.sin(stateTicks * 0.22D + i) * 0.2D;
            double height = 0.5D + (i % 2) * 0.4D;
            Location point = base.clone().add(Math.cos(offset) * radius, height, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST,      point, 2, 0.04D, 0.05D, 0.04D, 0D, DUST_WHITE);
            if (i % 3 == 0) {
                world.spawnParticle(Particle.SNOWFLAKE, point, 1, 0.02D, 0.03D, 0.02D, 0.01D);
            }
        }
        if (stateTicks % 6 == 0) {
            world.playSound(base, Sound.ENTITY_PHANTOM_HURT,
                0.25F, 0.55F + random.nextFloat() * 0.15F);
        }
    }

    private void spawnParanormalCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.1D, 0D);
        world.spawnParticle(Particle.DUST,           focus, 5, 0.2D, 0.18D, 0.2D, 0D, DUST_REDSTONE);
        world.spawnParticle(Particle.ELECTRIC_SPARK, focus, 2, 0.15D, 0.1D, 0.15D, 0.01D);
        if (stateTicks % 8 == 0) {
            world.playSound(base, Sound.BLOCK_LEVER_CLICK, 0.3F, 0.8F + random.nextFloat() * 0.4F);
        }
    }

    private void spawnEchoCastingParticles(World world, Location base) {
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST, focus, 5, 0.2D, 0.25D, 0.2D, 0D, DUST_PALE);
        if (stateTicks % 5 == 0) {
            world.spawnParticle(Particle.END_ROD, focus, 2, 0.3D, 0.4D, 0.3D, 0.01D);
        }
        if (stateTicks % 8 == 0) {
            world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                0.25F, 0.65F + random.nextFloat() * 0.2F);
        }
    }

    private void spawnPoltergeistCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST, focus, 5, 0.2D, 0.25D, 0.2D, 0D, DUST_CYAN);
        if (stateTicks % 6 == 0) {
            world.playSound(base, Sound.ENTITY_BREEZE_SHOOT, 0.2F, 0.85F + random.nextFloat() * 0.3F);
        }
    }

    // =========================================================================
    // Casting animation system  (Citizens PlayerAnimation via reflection)
    // =========================================================================

    private void updateCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (!(entity instanceof Player npcPlayer)) {
            return;
        }
        if (target != null && target.isOnline() && !target.isDead()) {
            npc.faceLocation(target.getEyeLocation());
        }
        if (pendingAbility == null) {
            return;
        }
        switch (pendingAbility) {
            case SPECTRAL_SURGE      -> animateSpectralSurge(npcPlayer);
            case WAILING_SHRIEK      -> animateWailingScream(npcPlayer);
            case PARANORMAL_ACTIVITY -> animateParanormal(npcPlayer);
            case ECHO                -> animateEcho(npcPlayer);
            case POLTERGEIST_THROW   -> animatePoltergeist(npcPlayer);
        }
    }

    /** Spectral Surge: rapid bilateral arm-swings building to the teleport. */
    private void animateSpectralSurge(Player p) {
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING");
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
    }

    /** Wailing Scream: open-armed sweep with a two-handed hold before the release. */
    private void animateWailingScream(Player p) {
        if (stateTicks % 4 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
        if (stateTicks % 7 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
        if (stateTicks == castingTicks - 2) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
            playCitizensPlayerAnimation(p, "START_USE_OFFHAND_ITEM");
        }
    }

    /** Paranormal Activity: slow deliberate main-hand hold, channelling energy. */
    private void animateParanormal(Player p) {
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
        }
        if (stateTicks % 9 == 0) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
    }

    /** Echo: single-handed raised-arm summon. */
    private void animateEcho(Player p) {
        if (stateTicks % 6 == 0) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
        }
    }

    /** Poltergeist Throw: punching arm-swings as items are hurled. */
    private void animatePoltergeist(Player p) {
        if (stateTicks % 4 == 0) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
        if (stateTicks == castingTicks - 1) {
            p.swingOffHand();
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
    }

    private void resetCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (entity instanceof Player p) {
            playCitizensPlayerAnimation(p, "STOP_USE_ITEM");
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void playCitizensPlayerAnimation(Player player, String animationName) {
        try {
            Class<? extends Enum> animationClass =
                Class.forName("net.citizensnpcs.util.PlayerAnimation").asSubclass(Enum.class);
            Enum animation = Enum.valueOf(animationClass, animationName);
            Method playMethod = animationClass.getMethod("play", Player.class);
            playMethod.invoke(animation, player);
        } catch (ReflectiveOperationException ignored) {
            // Citizens implementation classes are not on the compile classpath; hand animation falls back to Bukkit swings.
        }
    }

    // =========================================================================
    // New abilities
    // =========================================================================

    /**
     * SPECTRAL SURGE — Ghost vanishes from its current position, phases behind
     * the target, reappears with a surge of icy particles, and deals damage
     * with a brief Levitation effect.
     */
    private void castSpectralSurge(Player player) {
        LivingEntity entity = getLivingEntity();
        if (entity == null || player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        World    world  = entity.getWorld();
        Location before = entity.getLocation().clone();

        // Calculate landing spot behind the player
        Vector backwards = player.getLocation().getDirection().setY(0).normalize().multiply(-1.8D);
        if (backwards.lengthSquared() < 0.001D) {
            backwards = new Vector(0, 0, -1.8D);
        }
        Location behind = player.getLocation().clone().add(backwards);
        behind.setY(Math.max(behind.getY(), world.getHighestBlockYAt(behind) + 1.0D));

        // Vanish from current spot
        setUntargetable(true);
        startVanishing(8);
        world.spawnParticle(Particle.DUST,      before.clone().add(0, 1, 0), 16, 0.3D, 0.4D, 0.3D, 0D, DUST_WHITE);
        world.spawnParticle(Particle.SNOWFLAKE, before.clone().add(0, 1, 0),  8, 0.3D, 0.3D, 0.3D, 0.04D);
        world.playSound(before, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 1.85F);

        BukkitRunnable surgeTask = new BukkitRunnable() {
            @Override
            public void run() {
                npc.teleport(behind, PlayerTeleportEvent.TeleportCause.PLUGIN);
                World w = behind.getWorld();
                if (w != null) {
                    w.spawnParticle(Particle.DUST,      behind.clone().add(0, 1, 0), 16, 0.3D, 0.4D, 0.3D, 0D, DUST_ICE);
                    w.spawnParticle(Particle.SNOWFLAKE, behind.clone().add(0, 1, 0), 10, 0.2D, 0.3D, 0.2D, 0.05D);
                    w.playSound(behind, Sound.ENTITY_PHANTOM_AMBIENT, 0.8F, 1.6F);
                }
                breakVanishing();
                setUntargetable(false);
                // Phase-through damage with brief Levitation
                if (player.isOnline() && !player.isDead()
                        && behind.distanceSquared(player.getLocation()) <= 16.0D) {
                    player.damage(5.0D, entity);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 30, 0, false, true, true));
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,   50, 1, false, true, true));
                    if (w != null) {
                        w.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_HURT, 0.7F, 1.2F);
                    }
                }
                vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS);
            }
        };
        tasks.add(surgeTask);
        surgeTask.runTaskLater(plugin, 4L);
    }

    /**
     * WAILING SHRIEK — Releases a piercing wail that blinds, nauseates, and
     * slows all players within an 18-block radius.  An expanding ring of
     * particles radiates outward so players can visually read the cast.
     */
    private void castWailingScream(Player player) {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location loc   = entity.getLocation();
        World    world = entity.getWorld();

        world.playSound(loc, Sound.ENTITY_PHANTOM_HURT,               1.0F, 0.42F);
        world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,             0.65F, 1.8F);
        world.playSound(loc, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, 0.8F, 0.40F);

        // Expanding shockwave ring
        BukkitRunnable wailTask = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                ticks++;
                double radius = ticks * 1.4D;
                if (radius > 20.0D || isDead()) {
                    cancel();
                    return;
                }
                int points = Math.max(6, (int) (radius * 2.5D));
                for (int i = 0; i < points; i++) {
                    double offset = (i / (double) points) * Math.PI * 2.0D;
                    Location ring = loc.clone().add(
                        Math.cos(offset) * radius, 1.0D, Math.sin(offset) * radius);
                    world.spawnParticle(Particle.DUST, ring, 1, 0.04D, 0.1D, 0.04D, 0D, DUST_WHITE);
                    if (ticks % 2 == 0) {
                        world.spawnParticle(Particle.SNOWFLAKE, ring, 1, 0.03D, 0.1D, 0.03D, 0.01D);
                    }
                }
            }
        };
        tasks.add(wailTask);
        wailTask.runTaskTimer(plugin, 0L, 1L);

        // Apply effects to all players inside the shriek radius
        for (Player nearby : world.getPlayers()) {
            if (nearby.isDead()) {
                continue;
            }
            double dist = nearby.getLocation().distance(loc);
            if (dist > 18.0D) {
                continue;
            }
            int blindTicks = dist <= 6.0D ? 80 : (dist <= 12.0D ? 50 : 30);
            nearby.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindTicks, 0, false, true, true));
            nearby.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,    blindTicks, 0, false, true, true));
            nearby.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,  40,         0, false, true, true));
            if (dist <= 10.0D) {
                nearby.damage(3.0D, entity);
            }
            world.playSound(nearby.getLocation(), Sound.ENTITY_PHANTOM_HURT, 0.6F, 0.45F);
        }
        vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS + 20);
    }
}






