package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.traits.WerewolfTrait;
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
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class WerewolfNPC {

    private static final Particle.DustOptions DUST_PURPLE = new Particle.DustOptions(Color.fromRGB(120, 30, 160), 1.2F);
    private static final Particle.DustOptions DUST_RED = new Particle.DustOptions(Color.fromRGB(180, 20, 20), 1.1F);
    private static final Particle.DustOptions DUST_SILVER = new Particle.DustOptions(Color.fromRGB(230, 230, 240), 1.2F);
    private static final String INFECTION_KEY = "bloodmoon-werewolf-infection";
    private static final String INFECTION_PULSE_KEY = "bloodmoon-werewolf-infection-pulse";
    private static final String ARMOR_BREAK_KEY = "bloodmoon-werewolf-shattered-armor";
    private static final long INFECTION_DURATION_MS = 15_000L;
    private static final long ARMOR_BREAK_DURATION_MS = 3_000L;

    private static final Map<UUID, BukkitRunnable> BLEED_TASKS = new HashMap<>();
    private static final Map<UUID, WerewolfBleedState> BLEED_STATES = new HashMap<>();
    private static final Map<UUID, BukkitRunnable> INFECTION_TASKS = new HashMap<>();

    private enum WerewolfState { COMBAT, CASTING, DEAD }
    private enum WerewolfAbility {
        BITE, FURIOUS_CLAWS, FAR_JUMP, WOLF_PACK, DEVOUR, TERRITORIAL_SNARL, PACK_FRENZY, BONE_SLAM
    }

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<WerewolfAbility, Integer> cooldowns = new EnumMap<>(WerewolfAbility.class);
    private final Map<WerewolfAbility, Integer> abilityUseCounts = new EnumMap<>(WerewolfAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private final List<Wolf> packWolves = new ArrayList<>();

    private WerewolfState state = WerewolfState.COMBAT;
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

    public boolean isDead() {
        return state == WerewolfState.DEAD || cleaned || deathStarted;
    }

    public Location getCurrentLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : (lastKnownLocation != null ? lastKnownLocation.clone() : spawnLocation.clone());
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
        if (!(event.getTarget() instanceof Player player) || state == WerewolfState.DEAD) {
            return;
        }
        target = player;
    }

    public void onTakeDamage() {
        LivingEntity entity = getLivingEntity();
        if (entity != null && entity.getHealth() <= entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.3D) {
            triggerLunarSurge();
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
        sentinel.chaseRange = 42.0D;
        sentinel.armor = 0.12D;
        sentinel.protectFromIgnores = false;
        sentinel.allTargets = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
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
        npc.getNavigator().setTarget(player, true);
        npc.faceLocation(player.getEyeLocation());

        if (entity.getHealth() <= entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.3D) {
            triggerLunarSurge();
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

        WerewolfAbility selected = chooseAbility(candidates);
        if (selected != null) {
            startCasting(selected, getCastTime(selected));
            return;
        }

        if (distanceSquared <= 6.25D && attackCooldown <= 0) {
            performScratch(player, entity);
        }
    }

    private void tickCasting() {
        if (stateTicks < castTicks) {
            return;
        }
        WerewolfAbility ability = pendingAbility;
        pendingAbility = null;
        state = beforeCast;
        stateTicks = 0;
        castTicks = 0;
        if (ability != null) {
            executeAbility(ability);
        }
    }

    private void startCasting(WerewolfAbility ability, int ticks) {
        if (state == WerewolfState.CASTING || state == WerewolfState.DEAD) {
            return;
        }
        pendingAbility = ability;
        beforeCast = state;
        state = WerewolfState.CASTING;
        stateTicks = 0;
        castTicks = ticks;
        Location loc = getCurrentLocation();
        World world = loc.getWorld();
        if (world != null) {
            world.playSound(loc, Sound.ENTITY_WOLF_GROWL, 1.0F, 0.65F + random.nextFloat() * 0.2F);
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 8, 0.25, 0.35, 0.25, 0, DUST_PURPLE);
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
        entity.getWorld().spawnParticle(Particle.DUST, entity.getLocation().add(0, 1.2, 0), healthFraction < 0.25D ? 4 : (healthFraction < 0.5D ? 2 : 1), 0.2, 0.3, 0.2, 0, DUST_RED);
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
        World world = current.getWorld();
        if (world != null && stateTicks % 6 == 0) {
            world.spawnParticle(Particle.DUST, current.clone().add(0, 1.5, 0), 6, 0.25, 0.35, 0.25, 0, DUST_SILVER);
        }
    }

    private void triggerLunarSurge() {
        if (lunarSurgeUsed) {
            return;
        }
        lunarSurgeUsed = true;
        lunarSurgeTicks = 240;
        Location current = getCurrentLocation();
        World world = current.getWorld();
        if (world != null) {
            world.playSound(current, Sound.ENTITY_WOLF_AMBIENT, 1.3F, 0.55F);
            world.spawnParticle(Particle.DUST, current.clone().add(0, 1.4, 0), 26, 0.35, 0.5, 0.35, 0, DUST_SILVER);
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

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.62D) world.dropItemNaturally(location, new ItemStack(Material.BONE, 2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.38D) world.dropItemNaturally(location, new ItemStack(Material.LEATHER, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.34D) world.dropItemNaturally(location, new ItemStack(Material.MUTTON, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.28D) world.dropItemNaturally(location, new ItemStack(Material.BEEF, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.22D) world.dropItemNaturally(location, new ItemStack(Material.RABBIT_HIDE, 1));
        if (random.nextDouble() <= 0.18D) world.dropItemNaturally(location, new ItemStack(Material.RABBIT_FOOT, 1));
        if (random.nextDouble() <= 0.30D) world.dropItemNaturally(location, new ItemStack(Material.STRING, 1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.16D) world.dropItemNaturally(location, new ItemStack(Material.LEAD, 1));
        if (random.nextDouble() <= 0.16D) world.dropItemNaturally(location, new ItemStack(Material.WHITE_WOOL, 1));
        if (random.nextDouble() <= 0.24D) world.dropItemNaturally(location, new ItemStack(Material.CHICKEN, 1 + random.nextInt(2)));

        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.BONE_BLOCK, 1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.ENCHANTED_BOOK, 1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.IRON_AXE, 1));
        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));
        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, new ItemStack(Material.CHAINMAIL_CHESTPLATE, 1));
        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, new ItemStack(Material.SHIELD, 1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.COMPASS, 1));
        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, new ItemStack(Material.IRON_BOOTS, 1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.RABBIT_STEW, 1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.GOAT_HORN, 1));
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
}