package com.cobbleworks.bloodmoon.mobs;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.traits.WerewolfTrait;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import java.lang.reflect.Method;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class WerewolfNPC {

    private static final Particle.DustOptions DUST_PURPLE     = new Particle.DustOptions(Color.fromRGB(120, 30, 160),  1.2F);
    private static final Particle.DustOptions DUST_RED        = new Particle.DustOptions(Color.fromRGB(180, 20, 20),   1.1F);
    private static final Particle.DustOptions DUST_SILVER     = new Particle.DustOptions(Color.fromRGB(230, 230, 240), 1.2F);
    private static final Particle.DustOptions DUST_AMBER      = new Particle.DustOptions(Color.fromRGB(220, 145, 30),  1.2F);
    private static final Particle.DustOptions DUST_DARK_BLOOD = new Particle.DustOptions(Color.fromRGB(110,   8,  8),  1.1F);
    private static final Particle.DustOptions DUST_MOONLIGHT  = new Particle.DustOptions(Color.fromRGB(195, 205, 255), 1.3F);
    private static final String INFECTION_KEY = "bloodmoon-werewolf-infection";
    private static final String INFECTION_PULSE_KEY = "bloodmoon-werewolf-infection-pulse";
    private static final String ARMOR_BREAK_KEY = "bloodmoon-werewolf-shattered-armor";
    private static final long INFECTION_DURATION_MS = 15_000L;
    private static final long ARMOR_BREAK_DURATION_MS = 3_000L;

    private static final Map<UUID, BukkitRunnable> BLEED_TASKS = new HashMap<>();
    private static final Map<UUID, WerewolfBleedState> BLEED_STATES = new HashMap<>();
    private static final Map<UUID, BukkitRunnable> INFECTION_TASKS = new HashMap<>();

    private enum WerewolfState { PROWLING, COMBAT, CASTING, DEAD }
    private static final int PROWLING_TICKS = 35;
    private enum WerewolfAbility {
        BITE, FURIOUS_CLAWS, FAR_JUMP, WOLF_PACK, DEVOUR, TERRITORIAL_SNARL, PACK_FRENZY, BONE_SLAM,
        MOON_HOWL, SAVAGE_CHARGE, ALPHA_CALL
    }

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<WerewolfAbility, Integer> cooldowns = new EnumMap<>(WerewolfAbility.class);
    private final Map<WerewolfAbility, Integer> abilityUseCounts = new EnumMap<>(WerewolfAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private final List<Wolf> packWolves = new ArrayList<>();

    private WerewolfState state = WerewolfState.PROWLING;
    private WerewolfState beforeCast = WerewolfState.COMBAT;
    private WerewolfAbility pendingAbility;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private Location territoryCenter;
    private Sheep devourTarget;
    private int territoryOutsideTicks;
    private int stateTicks;
    private int castTicks;
    private int attackCooldown;
    private int packFrenzyTicks;
    private int lunarSurgeTicks;
    private int bitesUntilInfection = 3 + random.nextInt(2);
    private boolean lunarSurgeUsed;
    private boolean feralRageActive;
    private boolean cleaned;
    private boolean deathStarted;

    private static final class WerewolfBleedState {
        private int biteTicks;
        private int scratchTicks;

        private WerewolfBleedState(int biteTicks, int scratchTicks) {
            this.biteTicks = biteTicks;
            this.scratchTicks = scratchTicks;
        }
    }

    public WerewolfNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        this.territoryCenter = spawnLocation.clone();
        configureNpc();
        startController();
    }

    public NPC getNpc() {
        return npc;
    }

    public boolean isDead() {
        return state == WerewolfState.DEAD || cleaned || deathStarted;
    }

    public Location getCurrentLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : (lastKnownLocation != null ? lastKnownLocation.clone() : spawnLocation.clone());
    }

    public double getCurrentHealth() {
        LivingEntity entity = getLivingEntity();
        return entity == null ? plugin.getConfigManager().getWerewolfHealth() : Math.max(0.0D, entity.getHealth());
    }

    public double getMaximumHealth() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return plugin.getConfigManager().getWerewolfHealth();
        }
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? plugin.getConfigManager().getWerewolfHealth() : Math.max(1.0D, attr.getValue());
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
        if (!(event.getTarget() instanceof Player player) || state == WerewolfState.DEAD) {
            return;
        }
        target = player;
    }

    public void onTakeDamage() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location loc   = entity.getLocation();
        World    world = entity.getWorld();
        world.playSound(loc, Sound.ENTITY_WOLF_HURT, 0.9F, 0.75F + random.nextFloat() * 0.25F);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 12, 0.3D, 0.35D, 0.3D, 0D, DUST_RED);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  5, 0.2D, 0.2D,  0.2D, 0D, DUST_DARK_BLOOD);
        world.spawnParticle(Particle.CRIT, loc.clone().add(0, 1.2D, 0),  4, 0.2D, 0.15D, 0.2D, 0.02D);
        if (state == WerewolfState.PROWLING) {
            state      = WerewolfState.COMBAT;
            stateTicks = 0;
        }
        if (entity.getHealth() <= entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.3D) {
            triggerLunarSurge();
        }
    }

    public void triggerSnapFromDamage() {
        if (state == WerewolfState.PROWLING) {
            state      = WerewolfState.COMBAT;
            stateTicks = 0;
        }
    }

    public void startDeathSequence() {
        if (deathStarted) {
            return;
        }
        deathStarted = true;
        state = WerewolfState.DEAD;
        cancelControllerOnly();
        cancelTasks();
        cleanupWolves();
        Location death = getCurrentLocation();
        World world = death.getWorld();
        if (world != null) {
            world.playSound(death, Sound.ENTITY_WOLF_DEATH, 1.0F, 0.65F);
            world.spawnParticle(Particle.DUST, death.clone().add(0, 1, 0), 35, 0.5, 0.7, 0.5, 0, DUST_RED);
            world.spawnParticle(Particle.SMOKE, death.clone().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0.03);
            dropLoot(world, death);
            if (random.nextDouble() <= Math.max(0.0D, plugin.getBloodMoonManager().getRewardMultiplier() - 1.0D)) {
                dropLoot(world, death);
            }
            ExperienceOrb orb = world.spawn(death.clone().add(0, 0.25, 0), ExperienceOrb.class);
            orb.setExperience((int) Math.max(1.0D,
                (45 + random.nextInt(20)) * plugin.getBloodMoonManager().getExpMultiplier()));
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
        cleanupWolves();
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            plugin.getOverheadHealthBarManager().removeBar(entity.getUniqueId());
        }
        if (npc.isSpawned()) {
            npc.despawn();
        }
        npc.destroy();
        plugin.getNPCManager().unregisterWerewolf(npc.getId());
    }

    public static void handleInfectedMove(Player player, org.bukkit.event.player.PlayerMoveEvent event, BloodMoonPlugin plugin) {
        if (!player.hasMetadata(INFECTION_KEY) || event.getTo() == null) {
            return;
        }
        long expiry = player.getMetadata(INFECTION_KEY).get(0).asLong();
        if (System.currentTimeMillis() >= expiry) {
            player.removeMetadata(INFECTION_KEY, plugin);
            player.removeMetadata(INFECTION_PULSE_KEY, plugin);
            return;
        }
        if (!player.hasMetadata(INFECTION_PULSE_KEY)) {
            return;
        }
        long pulseExpiry = player.getMetadata(INFECTION_PULSE_KEY).get(0).asLong();
        if (System.currentTimeMillis() >= pulseExpiry) {
            player.removeMetadata(INFECTION_PULSE_KEY, plugin);
            return;
        }
        Vector delta = event.getTo().toVector().subtract(event.getFrom().toVector());
        Location disrupted = event.getFrom().clone().subtract(delta.multiply(0.8D));
        disrupted.setYaw(event.getTo().getYaw());
        disrupted.setPitch(event.getTo().getPitch());
        event.setTo(disrupted);
    }

    private void configureNpc() {
        npc.data().set("bloodmoon-werewolf", true);
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        WerewolfTrait trait = npc.getOrAddTrait(WerewolfTrait.class);
        trait.bind(this);
        configureSkin();
        configureSentinel();
        if (!npc.isSpawned()) {
            npc.spawn(spawnLocation.clone());
        }
        npc.getNavigator().getDefaultParameters().speedModifier(0.9F).stationaryTicks(-1).avoidWater(false);
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
            hideNameplate(entity);
        }
    }

    private void configureSkin() {
        String skinName = plugin.getConfigManager().getWerewolfSkinName();
        String texture = plugin.getConfigManager().getWerewolfSkinTexture();
        String signature = plugin.getConfigManager().getWerewolfSkinSignature();
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
            plugin.getLogger().warning("Could not apply werewolf skin: " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.setInvincible(false);
        sentinel.setHealth(plugin.getConfigManager().getWerewolfHealth());
        sentinel.health = plugin.getConfigManager().getWerewolfHealth();
        sentinel.damage = 0.0D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange = 60.0D;
        sentinel.armor = 0.12D;
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
        double health = plugin.getConfigManager().getWerewolfHealth();
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
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (packFrenzyTicks > 0) {
            packFrenzyTicks--;
        }
        if (lunarSurgeTicks > 0) {
            lunarSurgeTicks--;
        }
        onTraitTick();
        cleanupInvalidWolves();
        tickPackWolves();

        if (state == WerewolfState.PROWLING) {
            tickProwling();
            return;
        }
        if (state == WerewolfState.CASTING) {
            tickCasting();
            return;
        }
        if (state == WerewolfState.DEAD) {
            return;
        }

        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }

        Player player = ensureTarget(48.0D);
        if (player == null) {
            player = findNearestPlayer(getCurrentLocation(), 48.0D);
            target = player;
        }
        if (player == null) {
            return;
        }

        updateBloodhuntAndMovement(entity, player);
        tickTerritory(entity.getLocation());
        applyTerritoryPressure(player);
        tickLunarSurge();

        double distanceSquared = entity.getLocation().distanceSquared(player.getLocation());
        setNavigationSpeed(0.9F);
        npc.getNavigator().setTarget(player, true);
        if (stateTicks % 8 == 0) {
            lockSentinelChase(player, 54.0D);
        }
        npc.faceLocation(player.getEyeLocation());

        if (entity.getHealth() <= entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.3D) {
            triggerLunarSurge();
        }

        // FERAL RAGE phase — triggers once at ≤25% health
        if (!feralRageActive) {
            AttributeInstance maxHpAttr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            double maxHp = maxHpAttr != null ? maxHpAttr.getValue() : plugin.getConfigManager().getWerewolfHealth();
            if (entity.getHealth() / maxHp <= 0.25D) {
                feralRageActive = true;
                triggerFeralRagePhase(entity);
            }
        }

        // Feral rage visual pulse — glowing claw swipes every 3 ticks
        if (feralRageActive && stateTicks % 3 == 0) {
            Location fl = entity.getLocation();
            fl.getWorld().spawnParticle(Particle.DUST, fl.clone().add(0D, 1.0D, 0D),
                3, 0.35D, 0.4D, 0.35D, 0D, DUST_RED);
        }

        int cadence = Math.max(16, (int) Math.round(26.0D * plugin.getBloodMoonManager().getAbilityCadenceMultiplier()));
        List<WerewolfAbility> candidates = new ArrayList<>();
        if (shouldDevour(entity) && cooldowns.getOrDefault(WerewolfAbility.DEVOUR, 0) <= 0) {
            candidates.add(WerewolfAbility.DEVOUR);
        }
        if (distanceSquared > 196.0D && cooldowns.getOrDefault(WerewolfAbility.FAR_JUMP, 0) <= 0) {
            candidates.add(WerewolfAbility.FAR_JUMP);
        }
        if (countLivingPackWolves() == 0 && cooldowns.getOrDefault(WerewolfAbility.WOLF_PACK, 0) <= 0 && stateTicks % Math.max(14, cadence - 8) == 0) {
            candidates.add(WerewolfAbility.WOLF_PACK);
        }
        if (countLivingPackWolves() > 0 && cooldowns.getOrDefault(WerewolfAbility.PACK_FRENZY, 0) <= 0 && stateTicks % Math.max(18, cadence + 8) == 0) {
            candidates.add(WerewolfAbility.PACK_FRENZY);
        }
        if (cooldowns.getOrDefault(WerewolfAbility.TERRITORIAL_SNARL, 0) <= 0 && stateTicks % Math.max(20, cadence + 20) == 0) {
            candidates.add(WerewolfAbility.TERRITORIAL_SNARL);
        }
        if (distanceSquared <= 16.0D && cooldowns.getOrDefault(WerewolfAbility.BONE_SLAM, 0) <= 0 && stateTicks % Math.max(18, cadence + 4) == 0) {
            candidates.add(WerewolfAbility.BONE_SLAM);
        }
        if (distanceSquared <= 30.0D && cooldowns.getOrDefault(WerewolfAbility.BITE, 0) <= 0 && stateTicks % Math.max(14, cadence - 4) == 0) {
            candidates.add(WerewolfAbility.BITE);
        }
        if (distanceSquared <= 64.0D && cooldowns.getOrDefault(WerewolfAbility.FURIOUS_CLAWS, 0) <= 0 && stateTicks % Math.max(15, cadence) == 0) {
            candidates.add(WerewolfAbility.FURIOUS_CLAWS);
        }
        if (cooldowns.getOrDefault(WerewolfAbility.MOON_HOWL, 0) <= 0 && stateTicks % Math.max(22, cadence + 14) == 0) {
            candidates.add(WerewolfAbility.MOON_HOWL);
        }
        if (distanceSquared <= 200.0D && cooldowns.getOrDefault(WerewolfAbility.SAVAGE_CHARGE, 0) <= 0 && stateTicks % Math.max(18, cadence + 6) == 0) {
            candidates.add(WerewolfAbility.SAVAGE_CHARGE);
        }
        if (cooldowns.getOrDefault(WerewolfAbility.ALPHA_CALL, 0) <= 0 && stateTicks % Math.max(28, cadence + 20) == 0) {
            candidates.add(WerewolfAbility.ALPHA_CALL);
        }

        WerewolfAbility selected = chooseAbility(candidates);
        if (selected != null) {
            startCasting(selected, getCastTime(selected));
            return;
        }

        // Ability-only combat: no fallback vanilla-style scratch here.
    }

    private void tickCasting() {
        LivingEntity entity = getLivingEntity();
        if (entity != null && target != null && target.isOnline() && !target.isDead()) {
            setNavigationSpeed(0.80F);
            npc.getNavigator().setTarget(target, true);
            if (stateTicks % 10 == 0) {
                lockSentinelChase(target, 54.0D);
            }
            npc.faceLocation(target.getEyeLocation());
        }
        runCastingParticles();
        updateCastingAnimation();
        if (stateTicks < castTicks) {
            return;
        }
        WerewolfAbility ability = pendingAbility;
        pendingAbility = null;
        resetCastingAnimation();
        state      = beforeCast;
        stateTicks = 0;
        castTicks  = 0;
        if (ability != null) {
            executeAbility(ability);
        }
    }

    private void startCasting(WerewolfAbility ability, int ticks) {
        if (state == WerewolfState.CASTING || state == WerewolfState.DEAD) {
            return;
        }
        pendingAbility = ability;
        beforeCast     = state;
        state          = WerewolfState.CASTING;
        stateTicks     = 0;
        castTicks      = ticks;
        npc.getNavigator().cancelNavigation();
        Location loc   = getCurrentLocation();
        World    world = loc.getWorld();
        if (world == null) {
            return;
        }
        switch (ability) {
            case BITE -> {
                world.playSound(loc, Sound.ENTITY_WOLF_GROWL,    1.1F, 0.55F);
                world.playSound(loc, Sound.ENTITY_SKELETON_HURT, 0.65F, 1.8F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 14, 0.35D, 0.4D, 0.35D, 0D, DUST_RED);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  6, 0.2D,  0.3D, 0.2D,  0D, DUST_DARK_BLOOD);
            }
            case FURIOUS_CLAWS -> {
                world.playSound(loc, Sound.ENTITY_WOLF_GROWL,         1.1F, 0.75F);
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 0.6F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.1D, 0), 12, 0.4D, 0.3D, 0.4D, 0D, DUST_PURPLE);
                world.spawnParticle(Particle.CRIT, loc.clone().add(0, 1.1D, 0),  4, 0.3D, 0.2D, 0.3D, 0.02D);
            }
            case FAR_JUMP -> {
                world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 1.2F, 0.45F);
                world.playSound(loc, Sound.ENTITY_WOLF_GROWL,   0.9F, 0.4F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 16, 0.4D, 0.5D, 0.4D, 0D, DUST_SILVER);
                world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 0.3D, 0),  6, 0.3D, 0.1D, 0.3D, 0.03D);
            }
            case WOLF_PACK -> {
                world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 1.2F, 0.65F);
                world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 0.9F, 0.8F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 14, 0.35D, 0.4D, 0.35D, 0D, DUST_AMBER);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  8, 0.25D, 0.3D, 0.25D, 0D, DUST_SILVER);
            }
            case DEVOUR -> {
                world.playSound(loc, Sound.ENTITY_WOLF_GROWL,  1.1F, 0.35F);
                world.playSound(loc, Sound.ENTITY_GENERIC_EAT, 0.7F, 0.5F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 14, 0.3D, 0.4D, 0.3D, 0D, DUST_DARK_BLOOD);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  6, 0.2D, 0.3D, 0.2D, 0D, DUST_RED);
            }
            case TERRITORIAL_SNARL -> {
                world.playSound(loc, Sound.ENTITY_WOLF_GROWL,  1.2F, 0.5F);
                world.playSound(loc, Sound.BLOCK_GRAVEL_BREAK, 0.9F, 0.7F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 12, 0.35D, 0.3D, 0.35D, 0D, DUST_PURPLE);
                world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.2D, 0), 16, 0.5D,  0.1D, 0.5D, Material.DIRT.createBlockData());
            }
            case PACK_FRENZY -> {
                world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT,         1.1F, 1.1F);
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP,  0.8F, 0.7F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 10, 0.3D,  0.4D, 0.3D,  0D, DUST_SILVER);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  8, 0.25D, 0.3D, 0.25D, 0D, DUST_RED);
            }
            case BONE_SLAM -> {
                world.playSound(loc, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.5F);
                world.playSound(loc, Sound.ENTITY_WOLF_GROWL,              0.9F, 0.5F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 12, 0.4D, 0.3D, 0.4D, 0D, DUST_PURPLE);
                world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.2D, 0), 10, 0.4D, 0.1D, 0.4D, Material.STONE.createBlockData());
            }
        }
    }

    private void executeAbility(WerewolfAbility ability) {
        abilityUseCounts.merge(ability, 1, Integer::sum);
        switch (ability) {
            case BITE -> castBite();
            case FURIOUS_CLAWS -> castFuriousClaws();
            case FAR_JUMP -> castFarJump();
            case WOLF_PACK -> castWolfPack();
            case DEVOUR -> castDevour();
            case TERRITORIAL_SNARL -> castTerritorialSnarl();
            case PACK_FRENZY -> castPackFrenzy();
            case BONE_SLAM -> castBoneSlam();
            case MOON_HOWL -> castMoonHowl();
            case SAVAGE_CHARGE -> castSavageCharge();
            case ALPHA_CALL -> castAlphaCall();
        }
        cooldowns.put(ability, switch (ability) {
            case BITE -> 110;
            case FURIOUS_CLAWS -> 120;
            case FAR_JUMP -> 160;
            case WOLF_PACK -> 260;
            case DEVOUR -> 260;
            case TERRITORIAL_SNARL -> 220;
            case PACK_FRENZY -> 180;
            case BONE_SLAM -> 170;
            case MOON_HOWL -> 280;
            case SAVAGE_CHARGE -> 200;
            case ALPHA_CALL -> 360;
        });
    }

    private WerewolfAbility chooseAbility(List<WerewolfAbility> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        int minUses = candidates.stream().mapToInt(a -> abilityUseCounts.getOrDefault(a, 0)).min().orElse(0);
        List<WerewolfAbility> underused = candidates.stream().filter(a -> abilityUseCounts.getOrDefault(a, 0) == minUses).toList();
        if (!underused.isEmpty() && random.nextDouble() <= 0.62D) {
            return underused.get(random.nextInt(underused.size()));
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private int getCastTime(WerewolfAbility ability) {
        return switch (ability) {
            case BITE -> 10;
            case FURIOUS_CLAWS -> 12;
            case FAR_JUMP -> 16;
            case WOLF_PACK -> 18;
            case DEVOUR -> 10;
            case TERRITORIAL_SNARL -> 14;
            case PACK_FRENZY -> 12;
            case BONE_SLAM -> 18;
            case MOON_HOWL -> 16;
            case SAVAGE_CHARGE -> 14;
            case ALPHA_CALL -> 20;
        };
    }

    private void performScratch(Player player, LivingEntity entity) {
        double damage = 4.0D * getBloodhuntDamageMultiplier() * getBloodScentMultiplier(player) * getTerritoryMultiplier(entity.getLocation());
        boolean cornered = isCorneredPrey(player, entity);
        if (cornered) {
            damage *= 1.45D;
        }
        player.damage(damage, entity);
        applyWerewolfBleed(plugin, player, 0, 40);
        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.ENTITY_WOLF_HURT, 0.85F, 0.7F);
        world.spawnParticle(Particle.SWEEP_ATTACK, player.getLocation().add(0, 1, 0), 1, 0.2, 0.1, 0.2, 0);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 10, 0.25, 0.25, 0.25, 0, DUST_RED);
        if (cornered) {
            player.setVelocity(player.getVelocity().multiply(0.2D));
        } else {
            Vector knockback = player.getLocation().toVector().subtract(entity.getLocation().toVector()).normalize().multiply(0.22D).setY(0.08D);
            player.setVelocity(player.getVelocity().add(knockback));
        }
        attackCooldown = getAttackCooldownTicks();
    }

    private void castBite() {
        LivingEntity entity = getLivingEntity();
        Player player = target;
        if (entity == null || player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        World world = entity.getWorld();
        Vector jump = player.getLocation().toVector().subtract(entity.getLocation().toVector()).normalize().multiply(0.8D).setY(0.38D);
        entity.setVelocity(jump);
        world.playSound(entity.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.1F, 0.85F);

        BukkitRunnable biteTask = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks++;
                if (ticks > 14 || isDead()) {
                    cancel();
                    return;
                }
                Location current = getCurrentLocation();
                world.spawnParticle(Particle.DUST, current.clone().add(0, 1, 0), 4, 0.2, 0.2, 0.2, 0, DUST_RED);
                if (player.isOnline() && !player.isDead() && current.distanceSquared(player.getLocation()) <= 5.0D) {
                    double damage = 6.5D * getBloodhuntDamageMultiplier() * getBloodScentMultiplier(player) * getTerritoryMultiplier(current);
                    if (isCorneredPrey(player, entity)) {
                        damage *= 1.35D;
                        player.setVelocity(player.getVelocity().multiply(0.2D));
                    }
                    player.damage(damage, entity);
                    applyWerewolfBleed(plugin, player, 60, 0);
                    applyDecayingSlow(player);
                    bitesUntilInfection--;
                    if (bitesUntilInfection <= 0) {
                        applyInfection(player);
                        bitesUntilInfection = 3 + random.nextInt(2);
                    }
                    attackCooldown = getAttackCooldownTicks();
                    cancel();
                }
            }
        };
        tasks.add(biteTask);
        biteTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void castFuriousClaws() {
        LivingEntity entity = getLivingEntity();
        Player player = target;
        if (entity == null || player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        World world = entity.getWorld();
        Location origin = entity.getEyeLocation();
        Vector forward = entity.getLocation().getDirection().setY(0).normalize();
        Vector perpendicular = new Vector(-forward.getZ(), 0, forward.getX());
        world.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 0.6F);

        for (int side = -1; side <= 1; side += 2) {
            final int dir = side;
            BukkitRunnable arc = new BukkitRunnable() {
                double progress = 0.0D;
                @Override
                public void run() {
                    progress += 0.6D;
                    if (progress > 7.0D || isDead()) {
                        cancel();
                        return;
                    }
                    double curve = Math.sin(progress * 0.6D) * 1.2D * dir;
                    Location point = origin.clone().add(forward.clone().multiply(progress)).add(perpendicular.clone().multiply(curve)).add(0, -0.4, 0);
                    world.spawnParticle(Particle.DUST, point, 6, 0.1, 0.1, 0.1, 0, DUST_PURPLE);
                    world.spawnParticle(Particle.CRIT, point, 2, 0.05, 0.05, 0.05, 0.01);
                    trimSoftBlocks(point.getBlock());
                    for (Player nearby : world.getPlayers()) {
                        if (!nearby.isDead() && nearby.getLocation().distanceSquared(point) <= 1.6D) {
                            nearby.damage(3.5D * getBloodhuntDamageMultiplier(), entity);
                            applyWerewolfBleed(plugin, nearby, 0, 20);
                        }
                    }
                }
            };
            tasks.add(arc);
            arc.runTaskTimer(plugin, 0L, 1L);
        }
    }

    private void castFarJump() {
        LivingEntity entity = getLivingEntity();
        Player player = target;
        if (entity == null || player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        World world = entity.getWorld();
        entity.setVelocity(new Vector(0, 0, 0));
        world.playSound(entity.getLocation(), Sound.ENTITY_WOLF_GROWL, 1.0F, 0.45F);
        world.spawnParticle(Particle.DUST, entity.getLocation().add(0, 0.2, 0), 10, 0.3, 0.1, 0.3, 0, DUST_PURPLE);

        BukkitRunnable jumpTask = new BukkitRunnable() {
            boolean launched;
            int ticks;
            @Override
            public void run() {
                ticks++;
                if (isDead()) {
                    cancel();
                    return;
                }
                if (!launched && ticks >= 10) {
                    launched = true;
                    Vector leap = player.getLocation().toVector().subtract(entity.getLocation().toVector()).normalize().multiply(1.2D).setY(0.82D);
                    entity.setVelocity(leap);
                    world.playSound(entity.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 1.1F, 0.9F);
                }
                if (launched && entity.isOnGround()) {
                    Location landing = entity.getLocation();
                    world.playSound(landing, Sound.ENTITY_GENERIC_EXPLODE, 0.7F, 1.4F);
                    world.spawnParticle(Particle.EXPLOSION, landing.add(0, 0.2, 0), 1, 0.2, 0.1, 0.2, 0);
                    for (Player nearby : world.getPlayers()) {
                        if (nearby.isDead() || nearby.getLocation().distanceSquared(landing) > 16.0D) {
                            continue;
                        }
                        nearby.damage(4.0D * getBloodhuntDamageMultiplier(), entity);
                        Vector shock = nearby.getLocation().toVector().subtract(landing.toVector()).normalize().multiply(0.25D).setY(0.42D);
                        nearby.setVelocity(nearby.getVelocity().add(shock));
                    }
                    cancel();
                }
                if (ticks > 40) {
                    cancel();
                }
            }
        };
        tasks.add(jumpTask);
        jumpTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void castWolfPack() {
        LivingEntity entity = getLivingEntity();
        Player player = target;
        if (entity == null || player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        World world = entity.getWorld();
        world.playSound(entity.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 1.2F, 0.75F);
        for (int index = countLivingPackWolves(); index < 2; index++) {
            Vector offset = new Vector(index == 0 ? 2.0D : -2.0D, 0, 1.2D);
            Location spawn = entity.getLocation().clone().add(offset);
            Wolf wolf = (Wolf) world.spawnEntity(spawn, EntityType.WOLF);
            wolf.setRemoveWhenFarAway(false);
            wolf.setAdult();
            wolf.setTarget(player);
            wolf.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(14.0D);
            wolf.setHealth(14.0D);
            wolf.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(3.0D);
            wolf.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.34D);
            wolf.setMetadata("bloodmoon-werewolf-pack", new FixedMetadataValue(plugin, npc.getId()));
            packWolves.add(wolf);
            world.spawnParticle(Particle.SMOKE, spawn.add(0, 0.3, 0), 10, 0.3, 0.2, 0.3, 0.02);
        }
    }

    private void castDevour() {
        LivingEntity entity = getLivingEntity();
        if (entity == null || devourTarget == null || !devourTarget.isValid()) {
            return;
        }
        Sheep sheep = devourTarget;
        World world = entity.getWorld();
        Vector rush = sheep.getLocation().toVector().subtract(entity.getLocation().toVector()).normalize().multiply(1.1D).setY(0.1D);
        entity.setVelocity(rush);

        BukkitRunnable devourTask = new BukkitRunnable() {
            int ticks;
            @Override
            public void run() {
                ticks++;
                if (isDead() || !sheep.isValid()) {
                    cancel();
                    return;
                }
                if (entity.getLocation().distanceSquared(sheep.getLocation()) <= 4.0D || ticks > 24) {
                    Location loc = sheep.getLocation();
                    sheep.damage(1000.0D, entity);
                    sheep.remove();
                    double heal = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.4D;
                    entity.setHealth(Math.min(entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue(), entity.getHealth() + heal));
                    world.playSound(loc, Sound.ENTITY_GENERIC_EAT, 1.0F, 0.65F);
                    world.spawnParticle(Particle.DUST, loc.add(0, 0.6, 0), 24, 0.3, 0.3, 0.3, 0, DUST_RED);
                    cancel();
                }
            }
        };
        tasks.add(devourTask);
        devourTask.runTaskTimer(plugin, 0L, 1L);
        devourTarget = null;
    }

    private void castTerritorialSnarl() {
        Location current = getCurrentLocation();
        territoryCenter = current.clone();
        territoryOutsideTicks = 0;
        World world = current.getWorld();
        if (world != null) {
            world.playSound(current, Sound.ENTITY_WOLF_GROWL, 1.1F, 0.55F);
            world.playSound(current, Sound.BLOCK_GRAVEL_BREAK, 0.8F, 0.75F);
            world.spawnParticle(Particle.BLOCK, current.clone().add(0, 0.1, 0), 14, 0.7, 0.0, 0.7, Material.DIRT.createBlockData());
            for (double angle = 0; angle < Math.PI * 2.0D; angle += Math.PI / 10.0D) {
                Location ring = current.clone().add(Math.cos(angle) * 10.0D, 0.15D, Math.sin(angle) * 10.0D);
                world.spawnParticle(Particle.DUST, ring, 1, 0.02, 0.0, 0.02, 0, DUST_PURPLE);
            }
        }
    }

    private void castPackFrenzy() {
        if (countLivingPackWolves() == 0) {
            return;
        }
        packFrenzyTicks = 120;
        Location current = getCurrentLocation();
        World world = current.getWorld();
        if (world != null) {
            world.playSound(current, Sound.ENTITY_WOLF_AMBIENT, 1.0F, 1.2F);
        }
    }

    private void castBoneSlam() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        World world = entity.getWorld();
        Location center = entity.getLocation().add(0, 1.0, 0);
        Vector forward = entity.getLocation().getDirection().setY(0).normalize();
        Vector perpendicular = new Vector(-forward.getZ(), 0, forward.getX());
        world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.55F);
        for (double depth = 1.0D; depth <= 5.0D; depth += 0.5D) {
            double width = 0.6D + depth * 0.35D;
            for (double lateral = -width; lateral <= width; lateral += 0.5D) {
                Location point = center.clone().add(forward.clone().multiply(depth)).add(perpendicular.clone().multiply(lateral)).add(0, -0.8, 0);
                world.spawnParticle(Particle.BLOCK, point, 1, 0.02, 0.02, 0.02, Material.STONE.createBlockData());
            }
        }
        for (Player nearby : world.getPlayers()) {
            if (nearby.isDead()) {
                continue;
            }
            Location playerLoc = nearby.getLocation().add(0, 1, 0);
            Vector toPlayer = playerLoc.toVector().subtract(center.toVector()).setY(0);
            if (toPlayer.lengthSquared() > 36.0D) {
                continue;
            }
            if (forward.dot(toPlayer.normalize()) < 0.35D) {
                continue;
            }
            nearby.damage(8.0D * getBloodhuntDamageMultiplier(), entity);
            nearby.setMetadata(ARMOR_BREAK_KEY, new FixedMetadataValue(plugin, System.currentTimeMillis() + ARMOR_BREAK_DURATION_MS));
        }
    }

    private void updateBloodhuntAndMovement(LivingEntity entity, Player player) {
        double maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double healthFraction = entity.getHealth() / maxHealth;
        double movementBase = 0.32D;
        double frenzy = 1.0D + (1.0D - healthFraction) * 0.42D;
        if (isBleedingForBloodScent(player)) {
            frenzy += 0.10D;
        }
        if (getTerritoryMultiplier(entity.getLocation()) > 1.0D) {
            frenzy += 0.08D;
        }
        if (lunarSurgeTicks > 0) {
            frenzy += 0.16D;
        }
        var speed = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(movementBase * frenzy);
        }
        var kb = entity.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE);
        if (kb != null) {
            kb.setBaseValue(lunarSurgeTicks > 0 ? 1.0D : 0.25D);
        }
        emitWerewolfTellParticles(entity, healthFraction);
    }

    private void tickTerritory(Location current) {
        if (territoryCenter == null) {
            territoryCenter = current.clone();
            return;
        }
        if (current.distanceSquared(territoryCenter) > 100.0D) {
            territoryOutsideTicks++;
            if (territoryOutsideTicks >= 100) {
                territoryCenter = current.clone();
                territoryOutsideTicks = 0;
            }
        } else {
            territoryOutsideTicks = 0;
        }
    }

    private void applyTerritoryPressure(Player player) {
        if (territoryCenter == null || territoryCenter.getWorld() != player.getWorld()) {
            return;
        }
        if (player.getLocation().distanceSquared(territoryCenter) <= 100.0D) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 0, false, true, true));
        }
    }

    private void tickLunarSurge() {
        if (lunarSurgeTicks <= 0) {
            return;
        }
        Location current = getCurrentLocation();
        World    world   = current.getWorld();
        if (world == null) {
            return;
        }
        // Spiral ring of moonlight particles
        double angle = stateTicks * 0.48D;
        for (int i = 0; i < 4; i++) {
            double offset = angle + (i * (Math.PI / 2.0D));
            Location ring = current.clone().add(
                Math.cos(offset) * 0.9D, 1.2D + Math.sin(stateTicks * 0.18D + i) * 0.15D, Math.sin(offset) * 0.9D);
            world.spawnParticle(Particle.DUST, ring, 1, 0.03D, 0.03D, 0.03D, 0D, DUST_MOONLIGHT);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, ring, 1, 0.02D, 0.02D, 0.02D, 0D, DUST_SILVER);
            }
        }
        // Periodic howl during surge
        if (stateTicks % 80 == 0) {
            world.playSound(current, Sound.ENTITY_WOLF_AMBIENT, 0.8F, 0.5F + random.nextFloat() * 0.15F);
        }
    }

    private void triggerLunarSurge() {
        if (lunarSurgeUsed) {
            return;
        }
        lunarSurgeUsed = true;
        lunarSurgeTicks = 240;
        Location current = getCurrentLocation();
        World    world   = current.getWorld();
        if (world == null) {
            return;
        }
        // Dramatic eruption burst — the wolf's rage peaks
        world.playSound(current, Sound.ENTITY_WOLF_AMBIENT,          1.4F, 0.42F);
        world.playSound(current, Sound.ENTITY_WOLF_GROWL,            1.2F, 0.35F);
        world.playSound(current, Sound.ENTITY_GENERIC_EXPLODE,       0.6F, 1.55F);
        world.spawnParticle(Particle.DUST,  current.clone().add(0, 1.4D, 0), 36, 0.5D, 0.65D, 0.5D, 0D, DUST_SILVER);
        world.spawnParticle(Particle.DUST,  current.clone().add(0, 1.4D, 0), 18, 0.35D, 0.5D, 0.35D, 0D, DUST_MOONLIGHT);
        world.spawnParticle(Particle.SMOKE, current.clone().add(0, 0.3D, 0), 20, 0.4D, 0.1D, 0.4D, 0.04D);
        // Eruption ring
        for (double a = 0; a < Math.PI * 2.0D; a += Math.PI / 10.0D) {
            Location ring = current.clone().add(Math.cos(a) * 1.4D, 0.4D, Math.sin(a) * 1.4D);
            world.spawnParticle(Particle.DUST, ring, 2, 0.04D, 0.1D, 0.04D, 0D, DUST_MOONLIGHT);
        }
    }

    private void tickPackWolves() {
        for (Wolf wolf : new ArrayList<>(packWolves)) {
            if (!wolf.isValid() || wolf.isDead()) {
                continue;
            }
            if (target != null && target.isOnline() && !target.isDead()) {
                wolf.setTarget(target);
            }
            wolf.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(packFrenzyTicks > 0 ? 0.52D : 0.34D);
            wolf.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(packFrenzyTicks > 0 ? 5.0D : 3.0D);
            if (packFrenzyTicks > 0) {
                wolf.getWorld().spawnParticle(Particle.DUST, wolf.getLocation().add(0, 0.5, 0), 2, 0.2, 0.2, 0.2, 0, DUST_RED);
            }
        }
    }

    private void cleanupInvalidWolves() {
        packWolves.removeIf(wolf -> wolf == null || !wolf.isValid() || wolf.isDead());
    }

    private void cleanupWolves() {
        for (Wolf wolf : new ArrayList<>(packWolves)) {
            if (wolf != null && wolf.isValid()) {
                wolf.remove();
            }
        }
        packWolves.clear();
    }

    private int countLivingPackWolves() {
        cleanupInvalidWolves();
        return packWolves.size();
    }

    private boolean shouldDevour(LivingEntity entity) {
        if (cooldowns.getOrDefault(WerewolfAbility.DEVOUR, 0) > 0) {
            return false;
        }
        if (entity.getHealth() / entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() > 0.65D) {
            return false;
        }
        for (Entity nearby : entity.getNearbyEntities(18.0D, 6.0D, 18.0D)) {
            if (nearby instanceof Sheep sheep && !sheep.isDead()) {
                devourTarget = sheep;
                return true;
            }
        }
        return false;
    }

    private Player ensureTarget(double range) {
        if (target != null && target.isOnline() && !target.isDead()) {
            Location targetLocation = target.getLocation();
            Location currentLocation = getCurrentLocation();
            if (targetLocation.getWorld() == currentLocation.getWorld() && targetLocation.distanceSquared(currentLocation) <= range * range) {
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
        double bestDistance = range * range;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.isDead() || !player.isOnline()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private void setNavigationSpeed(float speed) {
        try {
            npc.getNavigator().getDefaultParameters().speedModifier(speed);
        } catch (Exception ignored) {
        }
    }

    private void lockSentinelChase(Player player, double chaseRange) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        try {
            SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
            sentinel.allTargets = new SentinelTargetList();
            sentinel.addTarget("players");
            sentinel.addTarget("player:" + player.getName());
            sentinel.allIgnores = new SentinelTargetList();
            sentinel.addIgnore("npcs");
            sentinel.chaseRange = Math.max(sentinel.chaseRange, chaseRange);
            sentinel.respawnTime = -1;
        } catch (Exception ignored) {
        }
    }

    private LivingEntity getLivingEntity() {
        if (!npc.isSpawned() || npc.getEntity() == null) {
            return null;
        }
        return npc.getEntity() instanceof LivingEntity livingEntity ? livingEntity : null;
    }

    private int getAttackCooldownTicks() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return 18;
        }
        double maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double fraction = entity.getHealth() / maxHealth;
        if (lunarSurgeTicks > 0) {
            return 5;
        }
        if (fraction <= 0.25D) {
            return 7;
        }
        if (fraction <= 0.5D) {
            return 12;
        }
        return 18;
    }

    private double getBloodhuntDamageMultiplier() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return 1.0D;
        }
        double maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        double fraction = entity.getHealth() / maxHealth;
        double bonus = 1.0D + (1.0D - fraction) * 0.45D;
        if (lunarSurgeTicks > 0) {
            bonus += 0.35D;
        }
        return bonus;
    }

    private double getBloodScentMultiplier(Player player) {
        return isBleedingForBloodScent(player) ? 1.2D : 1.0D;
    }

    private boolean isBleedingForBloodScent(Player player) {
        return plugin.getBleedEffect().isBleeding(player) || BLEED_STATES.containsKey(player.getUniqueId());
    }

    private double getTerritoryMultiplier(Location current) {
        return territoryCenter != null && territoryCenter.getWorld() == current.getWorld() && current.distanceSquared(territoryCenter) <= 100.0D ? 1.18D : 1.0D;
    }

    private boolean isCorneredPrey(Player player, LivingEntity entity) {
        boolean nearWall = false;
        Block base = player.getLocation().getBlock();
        for (int dx = -1; dx <= 1 && !nearWall; dx++) {
            for (int dz = -1; dz <= 1 && !nearWall; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (base.getRelative(dx, 0, dz).getType().isSolid()) {
                    nearWall = true;
                }
            }
        }
        if (!nearWall) {
            return false;
        }
        Vector toWolf = entity.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0).normalize();
        Vector playerFacing = player.getLocation().getDirection().setY(0).normalize();
        return playerFacing.dot(toWolf) > 0.2D;
    }

    private void applyDecayingSlow(Player player) {
        for (int index = 0; index < 3; index++) {
            final int step = index;
            BukkitRunnable slowTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline() && !player.isDead()) {
                        int amplifier = Math.max(0, 2 - step);
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, amplifier, false, true, true));
                    }
                }
            };
            tasks.add(slowTask);
            slowTask.runTaskLater(plugin, index * 20L);
        }
    }

    private void applyInfection(Player player) {
        player.setMetadata(INFECTION_KEY, new FixedMetadataValue(plugin, System.currentTimeMillis() + INFECTION_DURATION_MS));
        BukkitRunnable oldTask = INFECTION_TASKS.remove(player.getUniqueId());
        if (oldTask != null) {
            oldTask.cancel();
        }
        BukkitRunnable infectionTask = new BukkitRunnable() {
            int ticks;
            @Override
            public void run() {
                ticks += 20;
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current == null || !current.isOnline() || current.isDead()) {
                    current.removeMetadata(INFECTION_KEY, plugin);
                    current.removeMetadata(INFECTION_PULSE_KEY, plugin);
                    INFECTION_TASKS.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                long expiry = current.getMetadata(INFECTION_KEY).get(0).asLong();
                if (System.currentTimeMillis() >= expiry) {
                    current.removeMetadata(INFECTION_KEY, plugin);
                    current.removeMetadata(INFECTION_PULSE_KEY, plugin);
                    INFECTION_TASKS.remove(player.getUniqueId());
                    cancel();
                    return;
                }
                current.getWorld().playSound(current.getLocation(), ticks % 80 == 0 ? Sound.ENTITY_WOLF_AMBIENT : Sound.ENTITY_WOLF_GROWL, 0.55F, 0.75F + random.nextFloat() * 0.3F);
                current.setMetadata(INFECTION_PULSE_KEY, new FixedMetadataValue(plugin, System.currentTimeMillis() + 500L));
            }
        };
        INFECTION_TASKS.put(player.getUniqueId(), infectionTask);
        infectionTask.runTaskTimer(plugin, 0L, 40L);
    }

    private void trimSoftBlocks(Block block) {
        Material type = block.getType();
        if (type.name().contains("LEAVES") || type.name().contains("FLOWER") || type == Material.TALL_GRASS || type == Material.SHORT_GRASS || type == Material.VINE) {
            block.breakNaturally();
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: MOON_HOWL — disorienting howl, nausea + blindness on nearby players
    // -------------------------------------------------------------------------

    private void castMoonHowl() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location loc = entity.getLocation();
        World world = entity.getWorld();

        world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 1.2F, 0.32F);
        world.playSound(loc, Sound.ENTITY_WOLF_GROWL,   1.0F, 0.28F);
        world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.5F, 1.6F);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.2D, 0D), 30, 0.6D, 0.7D, 0.6D, 0D, DUST_MOONLIGHT);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.2D, 0D), 16, 0.4D, 0.5D, 0.4D, 0D, DUST_SILVER);

        // Ring burst
        for (int i = 0; i < 20; i++) {
            double a = (Math.PI * 2.0D / 20.0D) * i;
            world.spawnParticle(Particle.DUST,
                loc.clone().add(Math.cos(a) * 2.0D, 1.0D, Math.sin(a) * 2.0D),
                1, 0.05D, 0.05D, 0.05D, 0D, DUST_SILVER);
        }

        double radiusSq = 100.0D; // 10-block howl range
        for (Player p : world.getPlayers()) {
            if (p.isDead() || p.getLocation().distanceSquared(loc) > radiusSq) {
                continue;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA,    80, 0, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,  50, 1, false, true, true));
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: SAVAGE_CHARGE — sprint dash in a line, damaging all in path
    // -------------------------------------------------------------------------

    private void castSavageCharge() {
        LivingEntity entity = getLivingEntity();
        Player player = target;
        if (entity == null || player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        World world = entity.getWorld();
        Location start = entity.getLocation();

        world.playSound(start, Sound.ENTITY_WOLF_GROWL,  1.0F, 0.55F);
        world.playSound(start, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8F, 1.1F);

        Vector dir = player.getLocation().toVector().subtract(start.toVector()).setY(0);
        if (dir.lengthSquared() < 0.001D) {
            return;
        }
        dir.normalize();

        final double chargeSpeed = 0.9D;
        final int chargeSteps   = 22;
        final double hitRadiusSq = 2.25D; // 1.5 block hit radius

        BukkitRunnable charge = new BukkitRunnable() {
            int step = 0;

            @Override
            public void run() {
                if (!entity.isValid() || step >= chargeSteps) {
                    cancel();
                    return;
                }
                step++;
                entity.setVelocity(dir.clone().multiply(chargeSpeed).setY(0.08D));
                Location pos = entity.getLocation();

                // Trail particles
                world.spawnParticle(Particle.DUST, pos.clone().add(0D, 0.5D, 0D),
                    4, 0.2D, 0.3D, 0.2D, 0D, DUST_RED);
                world.spawnParticle(Particle.BLOCK, pos.clone().add(0D, 0.1D, 0D),
                    3, 0.2D, 0.05D, 0.2D, 0.05D, Material.DIRT.createBlockData());

                // Damage players in path
                for (Player p : world.getPlayers()) {
                    if (!p.isDead() && p.getLocation().distanceSquared(pos) <= hitRadiusSq) {
                        p.damage(5.5D);
                        Vector kb = p.getLocation().toVector().subtract(pos.toVector()).setY(0);
                        if (kb.lengthSquared() > 0.001D) {
                            kb.normalize().multiply(1.2D).setY(0.6D);
                            p.setVelocity(kb);
                        }
                    }
                }
            }
        };
        tasks.add(charge);
        charge.runTaskTimer(plugin, 1L, 1L);
    }

    // -------------------------------------------------------------------------
    // Active ability: ALPHA_CALL — empowers wolves + heals self slightly
    // -------------------------------------------------------------------------

    private void castAlphaCall() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location loc = entity.getLocation();
        World world = entity.getWorld();

        world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 1.2F, 0.28F);
        world.playSound(loc, Sound.ENTITY_WOLF_GROWL,   1.0F, 0.38F);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.2D, 0D), 25, 0.5D, 0.6D, 0.5D, 0D, DUST_AMBER);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0D, 0.8D, 0D), 10, 0.4D, 0.3D, 0.4D, 0.04D);

        // Self-heal 3.0 HP
        double newHp = Math.min(entity.getHealth() + 3.0D,
            entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
                ? entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()
                : plugin.getConfigManager().getWerewolfHealth());
        entity.setHealth(newHp);

        // Buff all pack wolves — speed + frenzy-like state
        for (Wolf wolf : packWolves) {
            if (wolf == null || !wolf.isValid() || wolf.isDead()) {
                continue;
            }
            wolf.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,    120, 1, false, true));
            wolf.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 0, false, true));
            wolf.getWorld().spawnParticle(Particle.DUST,
                wolf.getLocation().clone().add(0D, 0.8D, 0D),
                8, 0.3D, 0.3D, 0.3D, 0D, DUST_AMBER);
        }

        // If no wolves are alive, summon one emergency wolf
        if (countLivingPackWolves() == 0 && cooldowns.getOrDefault(WerewolfAbility.WOLF_PACK, 0) > 0) {
            castWolfPack();
        }
    }

    // -------------------------------------------------------------------------
    // Phase transition: FERAL RAGE — triggers at ≤25% health
    // -------------------------------------------------------------------------

    private void triggerFeralRagePhase(LivingEntity entity) {
        Location loc = entity.getLocation();
        World world = entity.getWorld();

        world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT,       1.4F, 0.28F);
        world.playSound(loc, Sound.ENTITY_WOLF_GROWL,         1.2F, 0.25F);
        world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM,  0.7F, 0.38F);
        world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0D, 0.5D, 0D),  2, 0.4D, 0.2D, 0.4D, 0D);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.0D, 0D), 50, 1.0D, 0.8D, 1.0D, 0D, DUST_RED);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.0D, 0D), 20, 0.7D, 0.5D, 0.7D, 0D, DUST_DARK_BLOOD);

        // Speed boost
        AttributeInstance speed = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.40D);
        }

        // Claw damage boost — next scratch will deal more
        entity.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 1, false, false));
        entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,  Integer.MAX_VALUE, 0, false, false));

        // Periodic howl every 30 ticks handled via stateTicks % 30 in tickCombat — just announce once
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= 900.0D) {
                p.sendMessage("§4§l☠ The Werewolf enters a primal FERAL RAGE! ☠");
            }
        }
    }

    private void dropLoot(World world, Location location) {
        // --- Common drops ---
        if (random.nextDouble() <= 0.70D) world.dropItemNaturally(location, new ItemStack(Material.BONE,    2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.60D) world.dropItemNaturally(location, new ItemStack(Material.MUTTON,  1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.55D) world.dropItemNaturally(location, new ItemStack(Material.BEEF,    1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.50D) world.dropItemNaturally(location, new ItemStack(Material.LEATHER, 2 + random.nextInt(2)));

        // Wolf Fang — custom-named bone
        if (random.nextDouble() <= 0.35D) {
            ItemStack fang = new ItemStack(Material.BONE);
            org.bukkit.inventory.meta.ItemMeta m = fang.getItemMeta();
            if (m != null) {
                m.setDisplayName("§fWolf Fang");
                m.setLore(java.util.List.of("§7A razor-sharp fang, still warm."));
                fang.setItemMeta(m);
            }
            world.dropItemNaturally(location, fang);
        }

        // Silver Arrow — spectral arrow representing silver ammo
        if (random.nextDouble() <= 0.30D) world.dropItemNaturally(location, new ItemStack(Material.SPECTRAL_ARROW, 2 + random.nextInt(4)));

        // Moon Shard — quartz fragment
        if (random.nextDouble() <= 0.25D) {
            ItemStack shard = new ItemStack(Material.QUARTZ);
            org.bukkit.inventory.meta.ItemMeta m = shard.getItemMeta();
            if (m != null) {
                m.setDisplayName("§7Moon Shard");
                m.setLore(java.util.List.of("§8Crystallised moonlight, brittle yet cold."));
                shard.setItemMeta(m);
            }
            world.dropItemNaturally(location, shard);
        }

        if (random.nextDouble() <= 0.20D) world.dropItemNaturally(location, new ItemStack(Material.SPIDER_EYE, 1));
        if (random.nextDouble() <= 0.18D) world.dropItemNaturally(location, new ItemStack(Material.LEAD,        1 + random.nextInt(2)));

        // Wolf Hide — leather chestplate with custom name
        if (random.nextDouble() <= 0.08D) {
            ItemStack hide = new ItemStack(Material.LEATHER_CHESTPLATE);
            org.bukkit.inventory.meta.ItemMeta m = hide.getItemMeta();
            if (m != null) {
                m.setDisplayName("§6Wolf Hide");
                m.setLore(java.util.List.of("§7Thick pelt, matted with dried blood."));
                hide.setItemMeta(m);
            }
            world.dropItemNaturally(location, hide);
        }

        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, new ItemStack(Material.GOAT_HORN,     1));

        // Pack Howl — paper item with lore
        if (random.nextDouble() <= 0.08D) {
            ItemStack scroll = new ItemStack(Material.PAPER);
            org.bukkit.inventory.meta.ItemMeta m = scroll.getItemMeta();
            if (m != null) {
                m.setDisplayName("§bPack Howl");
                m.setLore(java.util.List.of("§7Echoes of the pack still linger.", "§8(Rare trophy drop)"));
                scroll.setItemMeta(m);
            }
            world.dropItemNaturally(location, scroll);
        }

        // Rare weapon drop
        if (random.nextDouble() <= 0.10D) {
            ItemStack axe = new ItemStack(Material.IRON_AXE);
            axe.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 2);
            world.dropItemNaturally(location, axe);
        }

        // Very rare drops
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.WITHER_SKELETON_SKULL, 1));
        if (random.nextDouble() <= 0.04D) world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));

        // Experience
        int xp = 40 + random.nextInt(31);
        ExperienceOrb orb = (ExperienceOrb) world.spawnEntity(location, EntityType.EXPERIENCE_ORB);
        orb.setExperience(xp);
    }

    private static void applyWerewolfBleed(BloodMoonPlugin plugin, Player player, int biteTicks, int scratchTicks) {
        if (player == null || !player.isOnline() || player.isDead()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        WerewolfBleedState state = BLEED_STATES.get(uuid);
        if (state == null) {
            state = new WerewolfBleedState(biteTicks, scratchTicks);
            BLEED_STATES.put(uuid, state);
        } else {
            state.biteTicks += biteTicks;
            state.scratchTicks += scratchTicks;
        }

        if (BLEED_TASKS.containsKey(uuid)) {
            return;
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                Player current = Bukkit.getPlayer(uuid);
                WerewolfBleedState bleedState = BLEED_STATES.get(uuid);
                if (current == null || bleedState == null || !current.isOnline() || current.isDead()) {
                    cancelWerewolfBleed(uuid);
                    return;
                }
                double damage = 0.0D;
                if (bleedState.biteTicks > 0) {
                    damage += 1.0D;
                    bleedState.biteTicks -= 10;
                }
                if (bleedState.scratchTicks > 0) {
                    damage += 0.5D;
                    bleedState.scratchTicks -= 10;
                }
                if (damage > 0.0D) {
                    current.damage(damage);
                    current.getWorld().spawnParticle(Particle.DUST, current.getLocation().add(0, 0.1, 0), 3, 0.22, 0.05, 0.22, 0, DUST_RED);
                    current.getWorld().playSound(current.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.35F, 0.8F);
                }
                if (bleedState.biteTicks <= 0 && bleedState.scratchTicks <= 0) {
                    cancelWerewolfBleed(uuid);
                }
            }
        };
        BLEED_TASKS.put(uuid, task);
        task.runTaskTimer(plugin, 10L, 10L);
    }

    private static void cancelWerewolfBleed(UUID uuid) {
        BukkitRunnable task = BLEED_TASKS.remove(uuid);
        if (task != null) {
            task.cancel();
        }
        BLEED_STATES.remove(uuid);
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
    // Prowling intro state — wolf slinks in before combat begins
    // =========================================================================

    private void tickProwling() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        World    world = entity.getWorld();
        Location loc   = entity.getLocation();

        // Find target before the first reveal
        if (stateTicks == 1) {
            Player nearest = findNearestPlayer(loc, 56.0D);
            if (nearest != null) {
                target = nearest;
            }
            world.playSound(loc, Sound.ENTITY_WOLF_GROWL,   0.55F, 0.45F + random.nextFloat() * 0.1F);
            world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 0.45F, 0.5F);
        }

        // Stalking footstep dust trail
        if (stateTicks % 5 == 0) {
            world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 0.15D, 0), 2, 0.18D, 0.05D, 0.18D, 0.01D);
            world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.1D, 0),  1, 0.12D, 0.12D, 0.12D, 0D, DUST_RED);
        }

        // Low-growl building sequence
        if (stateTicks % 12 == 0) {
            world.playSound(loc, Sound.ENTITY_WOLF_GROWL, 0.4F + (stateTicks / (float) PROWLING_TICKS) * 0.55F, 0.5F);
        }

        // Chase the target during the prowl
        if (target != null && target.isOnline() && !target.isDead()) {
            setNavigationSpeed(0.85F);
            npc.getNavigator().setTarget(target, true);
            if (stateTicks % 10 == 0) {
                lockSentinelChase(target, 54.0D);
            }
            npc.faceLocation(target.getEyeLocation());
        }

        if (stateTicks >= PROWLING_TICKS) {
            state      = WerewolfState.COMBAT;
            stateTicks = 0;
            // Combat reveal — explosive burst
            world.playSound(loc, Sound.ENTITY_WOLF_AMBIENT, 1.3F, 0.55F);
            world.playSound(loc, Sound.ENTITY_WOLF_GROWL,   1.1F, 0.6F);
            world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 24, 0.5D, 0.55D, 0.5D, 0D, DUST_RED);
            world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 0.3D, 0), 12, 0.4D, 0.1D,  0.4D, 0.04D);
            for (double a = 0; a < Math.PI * 2.0D; a += Math.PI / 8.0D) {
                Location ring = loc.clone().add(Math.cos(a) * 1.1D, 0.3D, Math.sin(a) * 1.1D);
                world.spawnParticle(Particle.DUST, ring, 1, 0.03D, 0.08D, 0.03D, 0D, DUST_RED);
            }
        }
    }

    // =========================================================================
    // Ambient tell particles — scale with health and lunar surge
    // =========================================================================

    private void emitWerewolfTellParticles(LivingEntity entity, double healthFraction) {
        World    world = entity.getWorld();
        Location loc   = entity.getLocation();

        // Red blood-scent drip — intensity scales with lost health
        int count = healthFraction < 0.25D ? 4 : (healthFraction < 0.5D ? 2 : 1);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.2D, 0), count, 0.2D, 0.3D, 0.2D, 0D, DUST_RED);

        // Dark blood drip at very low health
        if (healthFraction < 0.3D && stateTicks % 6 == 0) {
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 0.8D, 0), 2, 0.15D, 0.15D, 0.15D, 0D, DUST_DARK_BLOOD);
        }

        // Moonlight shimmer during lunar surge
        if (lunarSurgeTicks > 0 && stateTicks % 4 == 0) {
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.5D, 0), 3, 0.22D, 0.3D, 0.22D, 0D, DUST_MOONLIGHT);
        }

        // Amber frenzy glow when pack wolves are alive and frenzied
        if (packFrenzyTicks > 0 && stateTicks % 5 == 0) {
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.3D, 0), 2, 0.18D, 0.22D, 0.18D, 0D, DUST_AMBER);
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
            case BITE            -> spawnBiteCastingParticles(world, base);
            case FURIOUS_CLAWS   -> spawnFuriousClawsCastingParticles(world, base);
            case FAR_JUMP        -> spawnFarJumpCastingParticles(world, base);
            case WOLF_PACK       -> spawnWolfPackCastingParticles(world, base);
            case DEVOUR          -> spawnDevourCastingParticles(world, base);
            case TERRITORIAL_SNARL -> spawnSnarlCastingParticles(world, base);
            case PACK_FRENZY     -> spawnPackFrenzyCastingParticles(world, base);
            case BONE_SLAM       -> spawnBoneSlamCastingParticles(world, base);
        }
    }

    private void spawnGenericCastingRing(World world, Location base) {
        double angle = stateTicks * 0.44D;
        for (int step = 0; step < 4; step++) {
            double offset = angle + (step * (Math.PI / 2.0D));
            Location point = base.clone().add(
                Math.cos(offset) * 0.8D,
                0.4D + (stateTicks % 18) * 0.04D,
                Math.sin(offset) * 0.8D);
            world.spawnParticle(Particle.DUST, point, 1, 0.02D, 0.02D, 0.02D, 0D, DUST_RED);
        }
    }

    private void spawnBiteCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST, focus, 5, 0.2D, 0.25D, 0.2D, 0D, DUST_RED);
        world.spawnParticle(Particle.DUST, focus, 3, 0.15D, 0.2D, 0.15D, 0D, DUST_DARK_BLOOD);
        if (stateTicks % 5 == 0) {
            world.playSound(base, Sound.ENTITY_WOLF_GROWL, 0.3F, 0.55F + random.nextFloat() * 0.1F);
        }
        if (target != null && target.isOnline() && !target.isDead()) {
            Location eye  = base.clone().add(0D, 1.4D, 0D);
            Location dest = target.getEyeLocation();
            for (int s = 0; s < 3; s++) {
                double progress = ((stateTicks * 0.18D) + (s * 0.28D)) % 1.0D;
                Location point  = eye.clone().add(dest.clone().subtract(eye).toVector().multiply(progress));
                world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.03D, 0.03D, 0D, DUST_RED);
            }
        }
    }

    private void spawnFuriousClawsCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.55D;
        for (int i = 0; i < 5; i++) {
            double offset = angle + (i * (Math.PI * 2.0D / 5.0D));
            double radius = 0.55D + Math.sin(stateTicks * 0.28D + i) * 0.15D;
            double height = 0.4D + (i % 2) * 0.5D;
            Location point = base.clone().add(Math.cos(offset) * radius, height, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST, point, 2, 0.04D, 0.05D, 0.04D, 0D, DUST_PURPLE);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.CRIT, point, 1, 0.04D, 0.05D, 0.04D, 0.01D);
            }
        }
        if (stateTicks % 5 == 0) {
            world.playSound(base, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.25F, 0.65F + random.nextFloat() * 0.2F);
        }
    }

    private void spawnFarJumpCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST,  focus, 6, 0.22D, 0.28D, 0.22D, 0D, DUST_SILVER);
        world.spawnParticle(Particle.SMOKE, base.clone().add(0D, 0.3D, 0D), 2, 0.2D, 0.05D, 0.2D, 0.02D);
        if (stateTicks % 6 == 0) {
            world.playSound(base, Sound.ENTITY_WOLF_GROWL, 0.25F, 0.4F + random.nextFloat() * 0.1F);
        }
    }

    private void spawnWolfPackCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.5D;
        for (int i = 0; i < 4; i++) {
            double offset = angle + (i * (Math.PI / 2.0D));
            double radius = 0.7D + Math.sin(stateTicks * 0.22D + i) * 0.1D;
            Location point = base.clone().add(Math.cos(offset) * radius, 0.6D + (i % 2) * 0.35D, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST, point, 2, 0.04D, 0.05D, 0.04D, 0D, DUST_AMBER);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.04D, 0.03D, 0D, DUST_SILVER);
            }
        }
        if (stateTicks % 7 == 0) {
            world.playSound(base, Sound.ENTITY_WOLF_AMBIENT, 0.25F, 0.68F + random.nextFloat() * 0.15F);
        }
    }

    private void spawnDevourCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST, focus, 6, 0.22D, 0.28D, 0.22D, 0D, DUST_DARK_BLOOD);
        world.spawnParticle(Particle.DUST, focus, 3, 0.15D, 0.2D,  0.15D, 0D, DUST_RED);
        if (stateTicks % 6 == 0) {
            world.playSound(base, Sound.ENTITY_GENERIC_EAT, 0.2F, 0.5F + random.nextFloat() * 0.1F);
        }
    }

    private void spawnSnarlCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST, focus, 5, 0.2D, 0.25D, 0.2D, 0D, DUST_PURPLE);
        world.spawnParticle(Particle.BLOCK, base.clone().add(0D, 0.2D, 0D), 3, 0.3D, 0.1D, 0.3D, Material.DIRT.createBlockData());
        if (stateTicks % 7 == 0) {
            world.playSound(base, Sound.ENTITY_WOLF_GROWL, 0.3F, 0.5F + random.nextFloat() * 0.1F);
        }
    }

    private void spawnPackFrenzyCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.58D;
        for (int i = 0; i < 4; i++) {
            double offset = angle + (i * (Math.PI / 2.0D));
            double radius = 0.6D + Math.sin(stateTicks * 0.3D + i) * 0.15D;
            Location point = base.clone().add(Math.cos(offset) * radius, 0.5D + (i % 2) * 0.45D, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST, point, 2, 0.04D, 0.05D, 0.04D, 0D, DUST_SILVER);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.04D, 0.03D, 0D, DUST_RED);
            }
        }
        if (stateTicks % 5 == 0) {
            world.playSound(base, Sound.ENTITY_WOLF_AMBIENT, 0.3F, 1.1F + random.nextFloat() * 0.1F);
        }
    }

    private void spawnBoneSlamCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0D, 1.0D, 0D);
        world.spawnParticle(Particle.DUST,  focus, 6, 0.25D, 0.3D, 0.25D, 0D, DUST_PURPLE);
        world.spawnParticle(Particle.BLOCK, base.clone().add(0D, 0.2D, 0D), 3, 0.3D, 0.1D, 0.3D, Material.STONE.createBlockData());
        if (stateTicks % 6 == 0) {
            world.playSound(base, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.25F, 0.5F + random.nextFloat() * 0.1F);
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
            case BITE            -> animateBite(npcPlayer);
            case FURIOUS_CLAWS   -> animateFuriousClaws(npcPlayer);
            case FAR_JUMP        -> animateFarJump(npcPlayer);
            case WOLF_PACK       -> animateWolfPack(npcPlayer);
            case DEVOUR          -> animateDevour(npcPlayer);
            case TERRITORIAL_SNARL -> animateSnarl(npcPlayer);
            case PACK_FRENZY     -> animatePackFrenzy(npcPlayer);
            case BONE_SLAM       -> animateBoneSlam(npcPlayer);
        }
    }

    /** Bite: snap-lunge forward arm swing building up. */
    private void animateBite(Player p) {
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
        if (stateTicks == castTicks - 2) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
        }
    }

    /** Furious Claws: rapid bilateral sweep arms. */
    private void animateFuriousClaws(Player p) {
        if (stateTicks % 4 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
        if (stateTicks % 6 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
    }

    /** Far Jump: coiling crouch before the leap. */
    private void animateFarJump(Player p) {
        if (stateTicks % 6 == 0) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
        }
        if (stateTicks == castTicks - 1) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
    }

    /** Wolf Pack: raised summon arms. */
    private void animateWolfPack(Player p) {
        if (stateTicks % 6 == 0) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
            playCitizensPlayerAnimation(p, "START_USE_OFFHAND_ITEM");
        }
    }

    /** Devour: reach-out lunge toward prey. */
    private void animateDevour(Player p) {
        if (stateTicks % 5 == 0) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
    }

    /** Territorial Snarl: stomp-stamp on the ground. */
    private void animateSnarl(Player p) {
        if (stateTicks % 7 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING");
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
    }

    /** Pack Frenzy: frenzied double-arm signal. */
    private void animatePackFrenzy(Player p) {
        if (stateTicks % 4 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
    }

    /** Bone Slam: giant overhead two-handed slam. */
    private void animateBoneSlam(Player p) {
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
        }
        if (stateTicks == castTicks - 2) {
            p.swingMainHand();
            p.swingOffHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
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
        }
    }
}






