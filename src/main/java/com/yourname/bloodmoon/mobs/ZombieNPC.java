package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.traits.ZombieTrait;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
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

/**
 * Blood Moon Zombie boss controller.
 *
 * Passive mechanics:
 *   - Green movement trail that deals continuous damage when stepped on
 *   - Hunger drain aura for nearby players
 *   - Crop destruction aura (crops within range wither to dead bushes)
 *
 * Active abilities:
 *   - ACID_SPIT   : Projectile that poisons + flags faster armor wear on subsequent hits
 *   - ROT_ZONE    : Expanding green cloud; food items in inventory convert to rotten flesh
 *   - POWER_LEAP  : Stronger melee that launches the player upward with extra knockback
 *
 * On-hit debuff:
 *   - Each melee hit reduces the player's outgoing damage by 15 % for 5 seconds
 *     (detected via "bloodmoon-zombie-weakened" player metadata in PlayerListener)
 *
 * Death sequence:
 *   - Shaking animation → delayed explosion → persistent green damage trail at death site
 */
public final class ZombieNPC {

    // -------------------------------------------------------------------------
    // State / ability enums
    // -------------------------------------------------------------------------

    public enum ZombieState {
        INFECTED_RAGE,
        COMBAT,
        CASTING,
        DEAD
    }

    public enum ZombieAbility {
        ACID_SPIT(22),
        ROT_ZONE(20),
        POWER_LEAP(18),
        CHARGE_LEAP(16);

        private final int weight;

        ZombieAbility(int weight) {
            this.weight = weight;
        }

        public int getWeight() {
            return weight;
        }
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int COMBAT_ABILITY_INTERVAL = 40;
    private static final int COMBAT_AMBIENT_INTERVAL  = 32;
    private static final int RAGE_TICKS               = 50;
    private static final int DEATH_SHAKE_TICKS        = 30;
    private static final int DEATH_REMOVE_DELAY       = 220;

    private static final int ACID_SPIT_COOLDOWN  = 180;
    private static final int ROT_ZONE_COOLDOWN   = 320;
    private static final int POWER_LEAP_COOLDOWN = 220;
    private static final int CHARGE_LEAP_COOLDOWN = 260;

    private static final int MELEE_COOLDOWN = 20;

    // Passive timings (in global ticks)
    private static final int TRAIL_SAMPLE_INTERVAL  = 2;
    private static final int TRAIL_PARTICLE_INTERVAL = 3;
    private static final int TRAIL_DAMAGE_INTERVAL  = 10;
    private static final int TRAIL_DURATION_TICKS   = 100; // 5 s

    private static final int HUNGER_DRAIN_INTERVAL = 30;
    private static final double HUNGER_DRAIN_RADIUS = 12.0D;

    private static final int CROP_CHECK_INTERVAL = 40;
    private static final int CROP_CHECK_RADIUS   = 6;

    private static final long DEBUFF_DURATION_MS = 5000L;
    private static final long ACID_DURATION_MS   = 6000L;

    // Trail damage radius squared (1.2 block radius)
    private static final double TRAIL_RADIUS_SQUARED = 1.44D;

    // Crops that the zombie wilts on proximity
    private static final Set<Material> CROPS = Set.of(
        Material.WHEAT,
        Material.CARROTS,
        Material.POTATOES,
        Material.BEETROOTS,
        Material.NETHER_WART,
        Material.MELON_STEM,
        Material.PUMPKIN_STEM,
        Material.SWEET_BERRY_BUSH,
        Material.TORCHFLOWER_CROP
    );

    // -------------------------------------------------------------------------
    // Inner class: green movement trail segment
    // -------------------------------------------------------------------------

    private static final class TrailSegment {
        final Location loc;
        final int birthTick;

        TrailSegment(Location loc, int tick) {
            this.loc = loc.clone();
            this.birthTick = tick;
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random;
    private final Map<ZombieAbility, Integer> cooldowns;
    private final Map<ZombieAbility, Integer> abilityUseCounts;
    private final List<BukkitRunnable> ownedTasks;
    private final List<TrailSegment> trailSegments;

    private ZombieState state;
    private ZombieState stateBeforeCasting;
    private ZombieAbility pendingAbility;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private Location lastTrailPoint;

    private int stateTicks;
    private int castingTicks;
    private int globalTick;
    private int meleeCooldown;

    private boolean cleanedUp;
    private boolean deathSequenceStarted;
    private boolean combatInitialized;
    private boolean leaping;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ZombieNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin          = plugin;
        this.npc             = npc;
        this.spawnLocation   = spawnLocation.clone();
        this.random          = new Random();
        this.cooldowns       = new EnumMap<>(ZombieAbility.class);
        this.abilityUseCounts = new EnumMap<>(ZombieAbility.class);
        this.ownedTasks      = new ArrayList<>();
        this.trailSegments   = new ArrayList<>();
        this.state           = ZombieState.INFECTED_RAGE;
        this.stateBeforeCasting = ZombieState.COMBAT;
        this.target          = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public NPC getNpc() {
        return npc;
    }

    public boolean isDead() {
        return state == ZombieState.DEAD || cleanedUp || deathSequenceStarted;
    }

    public Location getCurrentLocation() {
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            return entity.getLocation();
        }
        return lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone();
    }

    public double getCurrentHealth() {
        LivingEntity entity = getLivingEntity();
        return entity == null ? plugin.getConfigManager().getZombieHealth() : Math.max(0.0D, entity.getHealth());
    }

    public double getMaximumHealth() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return plugin.getConfigManager().getZombieHealth();
        }
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? plugin.getConfigManager().getZombieHealth() : Math.max(1.0D, attr.getValue());
    }

    public void onTraitTick() {
        updateLastKnownLocation();
    }

    public void onNpcSpawn() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        entity.setRemoveWhenFarAway(false);
        applyConfiguredHealth(entity);
    }

    /**
     * Called by Sentinel on melee attack.
     * Applies the 15 % outgoing-damage debuff to the player.
     */
    public void onMeleeHit(Player player) {
        if (player == null || player.isDead()) {
            return;
        }
        // Mark the player so PlayerListener can reduce their outgoing damage by 15 %
        player.setMetadata("bloodmoon-zombie-weakened",
            new FixedMetadataValue(plugin, System.currentTimeMillis() + DEBUFF_DURATION_MS));

        player.getWorld().playSound(player.getLocation(),
            Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.7F, 0.8F);
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        if (state == ZombieState.DEAD) {
            event.setCancelled(true);
            return;
        }
        target = player;
        onMeleeHit(player);

        // Occasionally trigger POWER_LEAP directly from melee outside a casting state
        if (state != ZombieState.CASTING && meleeCooldown <= 0 && random.nextDouble() < 0.08D) {
            if (canUseAbility(ZombieAbility.POWER_LEAP)) {
                startCasting(ZombieAbility.POWER_LEAP);
            }
        }
    }

    /**
     * Called from PlayerListener when the acid-spit projectile hits a player.
     * Applies poison, direct damage, and marks the player for accelerated armor wear.
     */
    public void handleAcidSpit(Player hitPlayer) {
        if (hitPlayer == null || hitPlayer.isDead()) {
            return;
        }
        hitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 1, true, true, true));
        hitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 80, 1, true, true, true));
        hitPlayer.damage(2.5D);

        // Flag accelerated armor wear for ACID_DURATION_MS
        hitPlayer.setMetadata("bloodmoon-zombie-acid-hit",
            new FixedMetadataValue(plugin, System.currentTimeMillis() + ACID_DURATION_MS));

        World world = hitPlayer.getWorld();
        Location loc = hitPlayer.getLocation().add(0, 1, 0);
        world.spawnParticle(Particle.DUST, loc, 24, 0.45D, 0.5D, 0.45D, 0D,
            new Particle.DustOptions(Color.fromRGB(50, 220, 50), 1.1F));
        world.spawnParticle(Particle.SNEEZE, loc, 8, 0.3D, 0.3D, 0.3D, 0D);
        world.playSound(hitPlayer.getLocation(), Sound.ENTITY_SLIME_SQUISH_SMALL, 1.0F, 0.6F);
        world.playSound(hitPlayer.getLocation(), Sound.ENTITY_ZOMBIE_INFECT, 0.75F, 0.9F);
    }

    public void startDeathSequence() {
        if (deathSequenceStarted) {
            return;
        }
        deathSequenceStarted = true;
        state = ZombieState.DEAD;
        stateTicks = 0;

        Location deathLocation = getCurrentLocation();
        lastKnownLocation = deathLocation.clone();
        cancelControllerOnly();
        cancelOwnedTasks();
        trailSegments.clear();

        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            entity.setAI(false);
            entity.setInvulnerable(true);
        }

        dropLoot(deathLocation.getWorld(), deathLocation);
        if (random.nextDouble() <= Math.max(0.0D, plugin.getBloodMoonManager().getRewardMultiplier() - 1.0D)) {
            dropLoot(deathLocation.getWorld(), deathLocation);
        }

        final Location deathLoc = deathLocation.clone();

        // Shaking animation for DEATH_SHAKE_TICKS, then explode
        BukkitRunnable shake = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                LivingEntity e = getLivingEntity();

                if (tick > DEATH_SHAKE_TICKS || e == null) {
                    triggerDeathExplosion(deathLoc);
                    cancel();
                    return;
                }

                // Jitter the entity back and forth
                if (tick % 3 == 0) {
                    double ox = (random.nextDouble() - 0.5D) * 0.28D;
                    double oz = (random.nextDouble() - 0.5D) * 0.28D;
                    e.teleport(e.getLocation().add(ox, 0D, oz));
                }

                World w = deathLoc.getWorld();
                if (w != null) {
                    w.spawnParticle(Particle.DUST,
                        deathLoc.clone().add(0D, 1.1D, 0D), 5, 0.35D, 0.45D, 0.35D, 0D,
                        new Particle.DustOptions(Color.fromRGB(40, 190, 40), 1.0F));
                    w.spawnParticle(Particle.SMOKE,
                        deathLoc.clone().add(0D, 0.5D, 0D), 2, 0.2D, 0.2D, 0.2D, 0.01D);
                    if (tick % 10 == 0) {
                        w.playSound(deathLoc, Sound.ENTITY_ZOMBIE_AMBIENT, 1.0F, 0.3F);
                    }
                }
            }
        };
        ownedTasks.add(shake);
        shake.runTaskTimer(plugin, 1L, 1L);

        // Cleanup after the full death sequence finishes
        BukkitRunnable removal = new BukkitRunnable() {
            @Override
            public void run() {
                cleanup();
            }
        };
        ownedTasks.add(removal);
        removal.runTaskLater(plugin, DEATH_REMOVE_DELAY);
    }

    public void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        cancelControllerOnly();
        cancelOwnedTasks();
        trailSegments.clear();
        if (npc.isSpawned()) {
            npc.despawn();
        }
        npc.destroy();
        plugin.getNPCManager().unregisterZombie(npc.getId());
    }

    public Player findNearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        double radiusSquared = radius * radius;
        return location.getWorld().getPlayers().stream()
            .filter(p -> !p.isDead())
            .filter(p -> p.getLocation().distanceSquared(location) <= radiusSquared)
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    // -------------------------------------------------------------------------
    // NPC setup
    // -------------------------------------------------------------------------

    private void configureNpc() {
        npc.data().set("bloodmoon-zombie", true);
        npc.data().set("nameplate-visible", false);
        npc.data().set("always-use-name-hologram", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        configureZombieTrait();
        configureSkin();
        configureSentinel();
        spawnNpc();
    }

    private void configureZombieTrait() {
        ZombieTrait trait = npc.getOrAddTrait(ZombieTrait.class);
        trait.bind(this);
    }

    private void configureSkin() {
        String skinName  = plugin.getConfigManager().getZombieSkinName();
        String texture   = plugin.getConfigManager().getZombieSkinTexture();
        String signature = plugin.getConfigManager().getZombieSkinSignature();
        if ((skinName == null || skinName.isBlank()) && (texture == null || texture.isBlank())) {
            return;
        }
        try {
            Class<? extends Trait> skinTraitClass =
                Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = npc.getOrAddTrait(skinTraitClass);
            Method setShouldUpdateSkins = skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class);
            Method setFetchDefaultSkin  = skinTraitClass.getMethod("setFetchDefaultSkin",  boolean.class);
            setShouldUpdateSkins.invoke(skinTrait, false);
            setFetchDefaultSkin.invoke(skinTrait, false);

            if (texture != null && !texture.isBlank() && signature != null && !signature.isBlank()) {
                Method setSkinPersistent = skinTraitClass.getMethod(
                    "setSkinPersistent", String.class, String.class, String.class);
                String cacheKey = (skinName == null || skinName.isBlank())
                    ? "bloodmoon_selected_zombie" : skinName;
                setSkinPersistent.invoke(skinTrait, cacheKey, signature, texture);
                return;
            }
            if (skinName != null && !skinName.isBlank()) {
                Method setSkinName = skinTraitClass.getMethod("setSkinName", String.class, boolean.class);
                setSkinName.invoke(skinTrait, skinName, true);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning(
                "Could not apply Citizens SkinTrait to zombie NPC " + npc.getId() + ": " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.setInvincible(false);
        sentinel.setHealth(plugin.getConfigManager().getZombieHealth());
        sentinel.health      = plugin.getConfigManager().getZombieHealth();
        sentinel.damage      = 5.5D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange  = 30.0D;
        sentinel.armor       = 0.15D;
        sentinel.protectFromIgnores = false;
        sentinel.allTargets  = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores  = new SentinelTargetList();
        npc.setProtected(false);
    }

    private void spawnNpc() {
        if (!npc.isSpawned()) {
            npc.spawn(spawnLocation.clone());
        }
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
            entity.setInvulnerable(false);
            entity.setMaximumNoDamageTicks(0);
            entity.setNoDamageTicks(0);
            hideNameplate(entity);
        }
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
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not hide zombie nameplate: " + ex.getMessage());
        }
    }

    private void applyConfiguredHealth(LivingEntity entity) {
        double hp = plugin.getConfigManager().getZombieHealth();
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(hp);
            entity.setHealth(Math.min(hp, entity.getHealth()));
        }
    }

    // -------------------------------------------------------------------------
    // Controller loop
    // -------------------------------------------------------------------------

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
        if (cleanedUp || deathSequenceStarted) {
            return;
        }
        stateTicks++;
        globalTick++;
        if (meleeCooldown > 0) {
            meleeCooldown--;
        }
        decrementCooldowns();
        updateLastKnownLocation();

        // Always-on passive effects
        tickGreenTrail();
        if (globalTick % HUNGER_DRAIN_INTERVAL == 0) {
            tickHungerDrain();
        }
        if (globalTick % CROP_CHECK_INTERVAL == 0) {
            tickCropDestruction();
        }

        switch (state) {
            case INFECTED_RAGE -> tickInfectedRage();
            case COMBAT        -> tickCombat();
            case CASTING       -> tickCasting();
            case DEAD          -> { /* nothing */ }
        }
    }

    // -------------------------------------------------------------------------
    // State ticks
    // -------------------------------------------------------------------------

    private void tickInfectedRage() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        if (stateTicks == 1) {
            World world = entity.getWorld();
            world.playSound(entity.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 0.6F);
            world.playSound(entity.getLocation(), Sound.ENTITY_WARDEN_ROAR,          0.45F, 1.7F);
        }
        if (stateTicks % 6 == 0) {
            entity.getWorld().spawnParticle(Particle.DUST,
                entity.getLocation().add(0D, 1.1D, 0D), 8, 0.4D, 0.45D, 0.4D, 0D,
                new Particle.DustOptions(Color.fromRGB(60, 160, 60), 1.0F));
        }
        if (stateTicks >= RAGE_TICKS) {
            state = ZombieState.COMBAT;
            stateTicks = 0;
            setNavigationSpeed(1.0F);
        }
    }

    private void tickCombat() {
        initializeCombat();

        Player player = ensureTarget(52.0D);
        if (player == null) {
            player = findNearestPlayer(getCurrentLocation(), 52.0D);
            target = player;
        }
        if (player == null) {
            setNavigationSpeed(0.75F);
            return;
        }

        target = player;
        setNavigationSpeed(0.88F);
        if (leaping) {
            return; // paused during charge leap arc
        }
        npc.getNavigator().setTarget(player, true);
        npc.faceLocation(player.getEyeLocation());

        if (stateTicks % COMBAT_AMBIENT_INTERVAL == 0) {
            getCurrentLocation().getWorld().playSound(
                getCurrentLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.9F, 0.65F);
        }

        int abilityInterval = Math.max(16, (int) Math.round(COMBAT_ABILITY_INTERVAL * plugin.getBloodMoonManager().getAbilityCadenceMultiplier()));
        if (stateTicks % abilityInterval == 0) {
            ZombieAbility ability = chooseAbility();
            if (ability != null && canUseAbility(ability)) {
                startCasting(ability);
            }
        }
    }

    private void tickCasting() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        // Keep slowly drifting toward the last known target instead of standing frozen
        if (target != null && target.isOnline() && !target.isDead()) {
            npc.getNavigator().setTarget(target, true);
        }
        if (stateTicks == 1) {
            entity.getWorld().spawnParticle(Particle.SMOKE,
                entity.getLocation().add(0D, 1.0D, 0D), 15, 0.4D, 0.5D, 0.4D, 0.03D);
            entity.getWorld().playSound(
                entity.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.8F, 0.45F);
        }
        if (stateTicks < castingTicks) {
            return;
        }
        ZombieAbility ability = pendingAbility;
        pendingAbility = null;
        state = stateBeforeCasting;
        stateTicks = 0;
        castingTicks = 0;
        if (ability != null) {
            executeAbility(ability);
        }
    }

    private void startCasting(ZombieAbility ability) {
        if (state == ZombieState.CASTING || state == ZombieState.DEAD) {
            return;
        }
        pendingAbility = ability;
        stateBeforeCasting = state;
        state = ZombieState.CASTING;
        stateTicks = 0;
        castingTicks = ability == ZombieAbility.CHARGE_LEAP ? 25 : 10;
    }

    private void executeAbility(ZombieAbility ability) {
        abilityUseCounts.merge(ability, 1, Integer::sum);
        switch (ability) {
            case ACID_SPIT   -> castAcidSpit();
            case ROT_ZONE    -> castRotZone();
            case POWER_LEAP  -> castPowerLeap();
            case CHARGE_LEAP -> castChargeLeap();
        }
        setCooldown(ability);
    }

    // -------------------------------------------------------------------------
    // Passive: green movement trail
    // -------------------------------------------------------------------------

    private void tickGreenTrail() {
        LivingEntity entity = getLivingEntity();
        Location current = entity != null ? entity.getLocation() : null;

        // Sample new trail points every TRAIL_SAMPLE_INTERVAL ticks
        if (globalTick % TRAIL_SAMPLE_INTERVAL == 0 && current != null) {
            if (lastTrailPoint == null
                || lastTrailPoint.getWorld() != current.getWorld()
                || lastTrailPoint.distanceSquared(current) >= 0.09D) {
                trailSegments.add(new TrailSegment(current, globalTick));
                lastTrailPoint = current.clone();
            }
        }

        // Expire old segments
        trailSegments.removeIf(seg -> globalTick - seg.birthTick > TRAIL_DURATION_TICKS);

        if (trailSegments.isEmpty()) {
            return;
        }

        // Emit particles
        if (globalTick % TRAIL_PARTICLE_INTERVAL == 0) {
            for (TrailSegment seg : trailSegments) {
                World w = seg.loc.getWorld();
                if (w == null) {
                    continue;
                }
                w.spawnParticle(Particle.DUST,
                    seg.loc.clone().add(0D, 0.05D, 0D), 1, 0.28D, 0.04D, 0.28D, 0D,
                    new Particle.DustOptions(Color.fromRGB(40, 200, 40), 1.0F));
            }
        }

        // Deal damage to players standing inside the trail
        if (globalTick % TRAIL_DAMAGE_INTERVAL == 0) {
            for (TrailSegment seg : trailSegments) {
                World w = seg.loc.getWorld();
                if (w == null) {
                    continue;
                }
                for (Player p : w.getPlayers()) {
                    if (!p.isDead()
                        && p.getLocation().distanceSquared(seg.loc) <= TRAIL_RADIUS_SQUARED) {
                        p.damage(0.5D);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 30, 0, true, true, true));
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Passive: hunger drain aura
    // -------------------------------------------------------------------------

    private void tickHungerDrain() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location center = entity.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        double radiusSq = HUNGER_DRAIN_RADIUS * HUNGER_DRAIN_RADIUS;
        for (Player p : world.getPlayers()) {
            if (p.isDead()) {
                continue;
            }
            if (p.getLocation().distanceSquared(center) <= radiusSq) {
                int food = p.getFoodLevel();
                if (food > 0) {
                    p.setFoodLevel(Math.max(0, food - 2));
                    p.setSaturation(Math.max(0F, p.getSaturation() - 1.0F));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 50, 0, true, false, true));
                } else {
                    p.damage(0.7D);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 0, true, true, true));
                }
                world.spawnParticle(Particle.SNEEZE, p.getLocation().add(0D, 1D, 0D), 2, 0.15D, 0.2D, 0.15D, 0D);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Passive: crop destruction aura
    // -------------------------------------------------------------------------

    private void tickCropDestruction() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location center = entity.getLocation();
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dx = -CROP_CHECK_RADIUS; dx <= CROP_CHECK_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -CROP_CHECK_RADIUS; dz <= CROP_CHECK_RADIUS; dz++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!CROPS.contains(block.getType())) {
                        continue;
                    }
                    // Clear the crop, then optionally place a dead bush
                    block.setType(Material.AIR, false);
                    Block below = block.getRelative(0, -1, 0);
                    if (below.getType().isSolid()) {
                        block.setType(Material.DEAD_BUSH, false);
                    }
                    world.spawnParticle(Particle.DUST,
                        block.getLocation().clone().add(0.5D, 0.5D, 0.5D),
                        6, 0.3D, 0.3D, 0.3D, 0D,
                        new Particle.DustOptions(Color.fromRGB(100, 60, 20), 0.9F));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: ACID_SPIT
    // -------------------------------------------------------------------------

    private void castAcidSpit() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(30.0D);
        if (entity == null || player == null) {
            return;
        }

        Vector dir = player.getEyeLocation().toVector()
            .subtract(entity.getEyeLocation().toVector());
        if (dir.lengthSquared() < 0.0001D) {
            return;
        }
        dir.normalize().multiply(1.4D);

        Snowball spit = entity.launchProjectile(Snowball.class, dir);
        spit.setMetadata("bloodmoon-zombie-acid-spit",
            new FixedMetadataValue(plugin, npc.getId()));

        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_DROWNED_SHOOT, 0.9F, 0.62F);
        entity.getWorld().spawnParticle(Particle.DUST,
            entity.getEyeLocation(), 6, 0.1D, 0.1D, 0.1D, 0D,
            new Particle.DustOptions(Color.fromRGB(50, 220, 50), 0.9F));

        // Particle trail while in flight
        BukkitRunnable trail = new BukkitRunnable() {
            @Override
            public void run() {
                if (!spit.isValid() || spit.isDead()) {
                    cancel();
                    return;
                }
                spit.getWorld().spawnParticle(Particle.DUST,
                    spit.getLocation(), 3, 0.08D, 0.08D, 0.08D, 0D,
                    new Particle.DustOptions(Color.fromRGB(60, 230, 60), 0.85F));
            }
        };
        ownedTasks.add(trail);
        trail.runTaskTimer(plugin, 1L, 1L);
    }

    // -------------------------------------------------------------------------
    // Active ability: ROT_ZONE
    // -------------------------------------------------------------------------

    private void castRotZone() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(28.0D);
        if (entity == null || player == null) {
            return;
        }

        Location center = player.getLocation().clone();
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        world.playSound(center, Sound.ENTITY_ZOMBIE_AMBIENT, 1.0F, 0.38F);
        world.spawnParticle(Particle.DUST, center.clone().add(0D, 0.8D, 0D),
            20, 2.0D, 0.5D, 2.0D, 0D,
            new Particle.DustOptions(Color.fromRGB(40, 180, 40), 1.2F));

        // Visual cloud that expands over time
        AreaEffectCloud cloud = (AreaEffectCloud) world.spawnEntity(center, EntityType.AREA_EFFECT_CLOUD);
        cloud.setRadius(1.5F);
        cloud.setRadiusPerTick(0F);
        cloud.setDuration(200);
        cloud.setWaitTime(0);
        cloud.setReapplicationDelay(Integer.MAX_VALUE);
        cloud.setParticle(Particle.DUST,
            new Particle.DustOptions(Color.fromRGB(30, 160, 30), 0.8F));

        BukkitRunnable zone = new BukkitRunnable() {
            int iteration = 0;
            float currentRadius = 1.5F;

            @Override
            public void run() {
                iteration++;
                if (iteration > 9 || isDead() || !cloud.isValid()) {
                    if (cloud.isValid()) {
                        cloud.remove();
                    }
                    cancel();
                    return;
                }

                // Grow the cloud each second
                currentRadius = Math.min(6.0F, 1.5F + iteration * 0.55F);
                cloud.setRadius(currentRadius);

                double radiusSq = (double) currentRadius * currentRadius;
                for (Player p : world.getPlayers()) {
                    if (p.isDead()) {
                        continue;
                    }
                    if (p.getLocation().distanceSquared(center) <= radiusSq) {
                        corruptFoodItem(p);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 60, 1, true, true, true));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 0, true, true, true));
                        if (iteration >= 6) {
                            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 30, 0, true, true, true));
                        }
                        world.spawnParticle(Particle.SNEEZE, p.getLocation().add(0D, 1D, 0D), 4, 0.25D, 0.25D, 0.25D, 0D);
                    }
                }
            }
        };
        ownedTasks.add(zone);
        zone.runTaskTimer(plugin, 20L, 20L);
    }

    /** Converts the first food stack found in the player's inventory into 1× rotten flesh. */
    private void corruptFoodItem(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            if (item.getType().isEdible() && item.getType() != Material.ROTTEN_FLESH) {
                inv.setItem(i, new ItemStack(Material.ROTTEN_FLESH, 1));
                player.getWorld().playSound(
                    player.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.55F, 0.72F);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: CHARGE_LEAP
    // -------------------------------------------------------------------------

    private void castChargeLeap() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(32.0D);
        if (entity == null || player == null) {
            return;
        }

        Location start = entity.getLocation().clone();
        Location end   = player.getLocation().clone();

        // Windup cue
        entity.getWorld().playSound(start, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 0.9F, 0.45F);
        entity.getWorld().spawnParticle(Particle.DUST,
            start.clone().add(0D, 1D, 0D), 25, 0.45D, 0.5D, 0.45D, 0D,
            new Particle.DustOptions(Color.fromRGB(40, 210, 40), 1.3F));

        leaping = true;
        npc.getNavigator().cancelNavigation();

        final int TOTAL_TICKS = 16;
        final double JUMP_HEIGHT = 4.5D;
        final double totalDist = start.distance(end);
        final double dx = (end.getX() - start.getX()) / TOTAL_TICKS;
        final double dz = (end.getZ() - start.getZ()) / TOTAL_TICKS;

        BukkitRunnable leap = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;

                if (tick > TOTAL_TICKS || isDead()) {
                    leaping = false;
                    Location landing = isDead() ? start : entity.getLocation().clone();
                    triggerChargeLeapShockwave(landing, player);
                    cancel();
                    return;
                }

                // Parabolic arc: horizontal lerp + sine-based vertical
                double progress = (double) tick / TOTAL_TICKS;
                double arcY = Math.sin(Math.PI * progress) * JUMP_HEIGHT;
                Location next = start.clone().add(dx * tick, arcY, dz * tick);
                next.setYaw(entity.getLocation().getYaw());
                next.setPitch(-25F);
                entity.teleport(next);

                // Green trail during flight
                entity.getWorld().spawnParticle(Particle.DUST,
                    next.clone().add(0D, 0.5D, 0D), 5, 0.2D, 0.2D, 0.2D, 0D,
                    new Particle.DustOptions(Color.fromRGB(50, 220, 50), 1.0F));
            }
        };
        ownedTasks.add(leap);
        leap.runTaskTimer(plugin, 1L, 1L);
    }

    private void triggerChargeLeapShockwave(Location landingLoc, Player targetPlayer) {
        World world = landingLoc.getWorld();
        if (world == null) {
            return;
        }

        // Impact sounds
        world.playSound(landingLoc, Sound.ENTITY_WARDEN_SONIC_BOOM,    0.7F, 0.45F);
        world.playSound(landingLoc, Sound.ENTITY_GENERIC_EXPLODE,      0.55F, 1.4F);
        world.playSound(landingLoc, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0F, 0.7F);

        // Expanding shockwave ring
        BukkitRunnable ring = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                if (tick > 14) {
                    cancel();
                    return;
                }
                double radius = tick * 0.45D;
                for (int i = 0; i < 24; i++) {
                    double angle = (Math.PI * 2D / 24D) * i;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location ringLoc = landingLoc.clone().add(x, 0.1D, z);
                    world.spawnParticle(Particle.DUST, ringLoc, 1, 0.05D, 0.02D, 0.05D, 0D,
                        new Particle.DustOptions(Color.fromRGB(50, 215, 50), 1.1F));
                    world.spawnParticle(Particle.BLOCK, ringLoc, 1, 0.05D, 0.02D, 0.05D, 0.01D,
                        Material.GRASS_BLOCK.createBlockData());
                }
            }
        };
        ownedTasks.add(ring);
        ring.runTaskTimer(plugin, 1L, 1L);

        // Knockback + damage all players in shockwave radius
        double shockwaveRadiusSq = 25.0D; // 5 block radius
        double closeSq           = 4.0D;  // 2 block radius — harder hit
        for (Player p : world.getPlayers()) {
            if (p.isDead()) {
                continue;
            }
            double distSq = p.getLocation().distanceSquared(landingLoc);
            if (distSq > shockwaveRadiusSq) {
                continue;
            }
            // Outward + upward knockback
            Vector kbDir = p.getLocation().toVector().subtract(landingLoc.toVector());
            if (kbDir.lengthSquared() > 0.01D) {
                kbDir.normalize().multiply(1.4D);
            } else {
                kbDir = new Vector(0D, 0D, 0.5D);
            }
            kbDir.setY(0.85D);
            p.setVelocity(kbDir);
            p.damage(distSq <= closeSq ? 4.5D : 2.5D);
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: POWER_LEAP
    // -------------------------------------------------------------------------

    private void castPowerLeap() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(20.0D);
        if (entity == null || player == null) {
            return;
        }

        // Close the gap toward the player
        Location entityLoc = entity.getLocation();
        Vector toPlayer = player.getLocation().toVector().subtract(entityLoc.toVector());
        if (toPlayer.lengthSquared() > 0.01D) {
            Location dest = entityLoc.clone().add(toPlayer.normalize().clone().multiply(1.0D));
            dest.setYaw(entityLoc.getYaw());
            dest.setPitch(entityLoc.getPitch());
            entity.teleport(dest);
        }

        // Deal bonus damage
        player.damage(4.0D);

        // Launch the player outward and upward
        Vector knockback = player.getLocation().toVector().subtract(entity.getLocation().toVector());
        if (knockback.lengthSquared() > 0.01D) {
            knockback.normalize().multiply(1.3D);
        } else {
            knockback = new Vector(0D, 0D, 0.5D);
        }
        knockback.setY(0.75D);
        player.setVelocity(knockback);

        World world = entity.getWorld();
        world.playSound(entity.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1.0F, 0.65F);
        world.spawnParticle(Particle.CRIT,
            player.getLocation().add(0D, 1D, 0D), 18, 0.4D, 0.4D, 0.4D, 0.12D);
        world.spawnParticle(Particle.DUST,
            player.getLocation().add(0D, 1D, 0D), 12, 0.4D, 0.4D, 0.4D, 0D,
            new Particle.DustOptions(Color.fromRGB(40, 190, 40), 1.1F));

        meleeCooldown = MELEE_COOLDOWN;
    }

    // -------------------------------------------------------------------------
    // Death explosion + lingering green trail
    // -------------------------------------------------------------------------

    private void triggerDeathExplosion(Location loc) {
        if (npc.isSpawned()) {
            npc.despawn();
        }

        World world = loc.getWorld();
        if (world == null) {
            return;
        }

        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.2F, 0.5F);
        world.spawnParticle(Particle.EXPLOSION,
            loc.clone().add(0D, 0.5D, 0D), 3, 0.5D, 0.3D, 0.5D, 0D);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 0.5D, 0D),
            42, 2.0D, 1.0D, 2.0D, 0D,
            new Particle.DustOptions(Color.fromRGB(40, 190, 40), 1.5F));
        world.spawnParticle(Particle.SMOKE, loc.clone().add(0D, 0.5D, 0D),
            20, 1.0D, 0.5D, 1.0D, 0.05D);

        // Blast damage (4 block radius)
        for (Player p : world.getPlayers()) {
            if (!p.isDead() && p.getLocation().distanceSquared(loc) <= 16.0D) {
                p.damage(5.0D);
            }
        }

        spawnDeathTrail(world, loc);
    }

    /** Lingers for ~6 seconds after the death explosion, damaging players inside. */
    private void spawnDeathTrail(World world, Location center) {
        BukkitRunnable deathTrail = new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                tick++;
                if (tick > 120) {
                    cancel();
                    return;
                }

                // Expanding ring of green particles
                if (tick % 2 == 0) {
                    for (int i = 0; i < 16; i++) {
                        double angle = (Math.PI * 2D / 16D) * i;
                        double x = Math.cos(angle) * 2.5D;
                        double z = Math.sin(angle) * 2.5D;
                        world.spawnParticle(Particle.DUST,
                            center.clone().add(x, 0.1D, z), 1, 0.1D, 0.05D, 0.1D, 0D,
                            new Particle.DustOptions(Color.fromRGB(40, 210, 40), 1.0F));
                    }
                    world.spawnParticle(Particle.SNEEZE,
                        center.clone().add(0D, 0.05D, 0D), 2, 1.5D, 0.05D, 1.5D, 0D);
                }

                // Damage every 0.5 s (10 ticks), 3 block radius
                if (tick % 10 == 0) {
                    for (Player p : world.getPlayers()) {
                        if (!p.isDead() && p.getLocation().distanceSquared(center) <= 9.0D) {
                            p.damage(0.5D);
                        }
                    }
                }
            }
        };
        ownedTasks.add(deathTrail);
        deathTrail.runTaskTimer(plugin, 1L, 1L);
    }

    // -------------------------------------------------------------------------
    // Loot drop
    // -------------------------------------------------------------------------

    private void dropLoot(World world, Location location) {
        if (world == null) {
            return;
        }
        if (random.nextDouble() <= 0.75D) {
            world.dropItemNaturally(location, new ItemStack(Material.ROTTEN_FLESH, randomRange(2, 5)));
        }
        if (random.nextDouble() <= 0.60D) {
            world.dropItemNaturally(location, new ItemStack(Material.BONE, randomRange(1, 4)));
        }
        if (random.nextDouble() <= 0.40D) {
            world.dropItemNaturally(location, new ItemStack(Material.SLIME_BALL, randomRange(2, 4)));
        }
        if (random.nextDouble() <= 0.34D) {
            world.dropItemNaturally(location, new ItemStack(Material.POTATO, randomRange(1, 2)));
        }
        if (random.nextDouble() <= 0.28D) {
            world.dropItemNaturally(location, new ItemStack(Material.CARROT, randomRange(1, 2)));
        }
        if (random.nextDouble() <= 0.26D) {
            world.dropItemNaturally(location, new ItemStack(Material.LEATHER, randomRange(1, 2)));
        }
        if (random.nextDouble() <= 0.25D) {
            world.dropItemNaturally(location, new ItemStack(Material.STRING, randomRange(1, 3)));
        }
        if (random.nextDouble() <= 0.25D) {
            world.dropItemNaturally(location, new ItemStack(Material.FLINT, randomRange(1, 2)));
        }
        if (random.nextDouble() <= 0.20D) {
            world.dropItemNaturally(location, new ItemStack(Material.IRON_NUGGET, randomRange(2, 4)));
        }
        if (random.nextDouble() <= 0.18D) {
            world.dropItemNaturally(location, new ItemStack(Material.SPIDER_EYE, 1));
        }

        if (random.nextDouble() <= 0.25D) {
            world.dropItemNaturally(location, new ItemStack(Material.IRON_INGOT, randomRange(1, 2)));
        }
        if (random.nextDouble() <= 0.15D) {
            world.dropItemNaturally(location, new ItemStack(Material.CARVED_PUMPKIN, 1));
        }
        if (random.nextDouble() <= 0.08D) {
            world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));
        }
        if (random.nextDouble() <= 0.06D) {
            ItemStack sword = new ItemStack(Material.IRON_SWORD, 1);
            ItemMeta meta = sword.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.SHARPNESS, 2, true);
                sword.setItemMeta(meta);
            }
            world.dropItemNaturally(location, sword);
        }
        if (random.nextDouble() <= 0.10D) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
            if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(Enchantment.PROTECTION, 3, true);
                book.setItemMeta(meta);
            }
            world.dropItemNaturally(location, book);
        }
        if (random.nextDouble() <= 0.06D) {
            world.dropItemNaturally(location, new ItemStack(Material.CHAINMAIL_CHESTPLATE, 1));
        }
        if (random.nextDouble() <= 0.06D) {
            world.dropItemNaturally(location, new ItemStack(Material.SHIELD, 1));
        }
        if (random.nextDouble() <= 0.07D) {
            world.dropItemNaturally(location, new ItemStack(Material.IRON_SHOVEL, 1));
        }
        if (random.nextDouble() <= 0.05D) {
            world.dropItemNaturally(location, new ItemStack(Material.NAME_TAG, 1));
        }
        if (random.nextDouble() <= 0.03D) {
            world.dropItemNaturally(location, new ItemStack(Material.ZOMBIE_HEAD, 1));
        }
        ExperienceOrb orb = world.spawn(location.clone().add(0D, 0.25D, 0D), ExperienceOrb.class);
        orb.setExperience((int) Math.max(1.0D,
            randomRange(35, 55) * plugin.getBloodMoonManager().getExpMultiplier()));
    }

    // -------------------------------------------------------------------------
    // Ability helpers
    // -------------------------------------------------------------------------

    private Player ensureTarget(double radius) {
        if (target == null || !target.isOnline() || target.isDead()) {
            target = null;
            return null;
        }
        Location current = getCurrentLocation();
        if (current.getWorld() != target.getWorld()
            || current.distanceSquared(target.getLocation()) > radius * radius) {
            target = null;
            return null;
        }
        return target;
    }

    private ZombieAbility chooseAbility() {
        List<ZombieAbility> available = new ArrayList<>();
        for (ZombieAbility ability : ZombieAbility.values()) {
            if (canUseAbility(ability)) {
                available.add(ability);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        int minUses = available.stream().mapToInt(a -> abilityUseCounts.getOrDefault(a, 0)).min().orElse(0);
        List<ZombieAbility> underused = available.stream().filter(a -> abilityUseCounts.getOrDefault(a, 0) == minUses).toList();
        if (!underused.isEmpty() && random.nextDouble() <= 0.60D) {
            return underused.get(random.nextInt(underused.size()));
        }

        int total = 0;
        for (ZombieAbility ability : available) {
            total += ability.getWeight();
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (ZombieAbility ability : available) {
            cumulative += ability.getWeight();
            if (roll < cumulative) {
                return ability;
            }
        }
        return null;
    }

    private boolean canUseAbility(ZombieAbility ability) {
        return cooldowns.getOrDefault(ability, 0) <= 0;
    }

    private void setCooldown(ZombieAbility ability) {
        switch (ability) {
            case ACID_SPIT   -> cooldowns.put(ability, ACID_SPIT_COOLDOWN);
            case ROT_ZONE    -> cooldowns.put(ability, ROT_ZONE_COOLDOWN);
            case POWER_LEAP  -> cooldowns.put(ability, POWER_LEAP_COOLDOWN);
            case CHARGE_LEAP -> cooldowns.put(ability, CHARGE_LEAP_COOLDOWN);
        }
    }

    private void decrementCooldowns() {
        for (Map.Entry<ZombieAbility, Integer> entry : cooldowns.entrySet()) {
            int value = entry.getValue();
            if (value > 0) {
                entry.setValue(value - 1);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private void initializeCombat() {
        if (combatInitialized) {
            return;
        }
        combatInitialized = true;
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.allTargets  = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores  = new SentinelTargetList();
        sentinel.chaseRange  = 32.0D;
        sentinel.respawnTime = -1;
    }

    private void updateLastKnownLocation() {
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            lastKnownLocation = entity.getLocation();
        }
    }

    private void setNavigationSpeed(float speed) {
        try {
            npc.getNavigator().getLocalParameters().speedModifier(speed);
        } catch (Exception ignored) {
        }
    }

    private LivingEntity getLivingEntity() {
        if (!npc.isSpawned()) {
            return null;
        }
        if (!(npc.getEntity() instanceof LivingEntity livingEntity)) {
            return null;
        }
        return livingEntity;
    }

    private int randomRange(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private void cancelControllerOnly() {
        if (controllerTask != null) {
            controllerTask.cancel();
            controllerTask = null;
        }
    }

    private void cancelOwnedTasks() {
        for (BukkitRunnable task : ownedTasks) {
            try {
                task.cancel();
            } catch (Exception ignored) {
            }
        }
        ownedTasks.clear();
    }
}
