package com.cobbleworks.bloodmoon.mobs;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.effects.BloodMagicProjectile;
import com.cobbleworks.bloodmoon.traits.VampireTrait;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.Color;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Bat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

/**
 * Main Blood Moon vampire controller.
 *
 * <p>The controller owns the disguise bat, Citizens NPC, Sentinel combat setup,
 * state machine, ability scheduling, minion bats, loot drops, and cleanup.</p>
 */
public final class VampireNPC {

    public enum VampireState {
        DISGUISED_BAT,
        STALKING,
        COMBAT,
        CASTING,
        BAT_FORM_ESCAPE,
        DEAD
    }

    public enum VampireAbility {
        BLOOD_MAGIC(30),
        DRAIN_LIFE(18),
        HEMOPLAGUE(10),
        BAT_FORM_ESCAPE(8),
        SUMMON_BATS(14),
        SHADOW_DASH(16),
        EXECUTION_DASH(11),
        TIDES_OF_BLOOD(12),
        BLOOD_SHIELD(6);

        private final int weight;

        VampireAbility(int weight) {
            this.weight = weight;
        }

        public int getWeight() {
            return weight;
        }
    }

    private static final int TRANSFORM_RADIUS = 12;
    private static final int DARKNESS_RADIUS = 8;
    private static final int COMBAT_ABILITY_INTERVAL = 40;
    private static final int COMBAT_AMBIENT_INTERVAL = 30;
    private static final int STALK_SOUND_INTERVAL = 20;
    private static final int BAT_MOVE_INTERVAL = 5;
    private static final int BAT_PROXIMITY_INTERVAL = 10;
    private static final int SHADOW_DASH_COOLDOWN = 300;
    private static final int TIDES_OF_BLOOD_COOLDOWN = 420;
    private static final int BAT_ESCAPE_COOLDOWN = 360;
    private static final int BLOOD_SHIELD_DURATION = 100;
    private static final int BLOOD_SHIELD_COOLDOWN = 1200;
    private static final int BLOOD_MAGIC_COOLDOWN = 80;
    private static final int BLOOD_MAGIC_VOLLEY_COUNT = 3;
    private static final int BLOOD_MAGIC_VOLLEY_DELAY = 12;
    private static final int DRAIN_LIFE_COOLDOWN = 180;
    private static final int HEMOPLAGUE_COOLDOWN = 260;
    private static final int SUMMON_BATS_COOLDOWN = 180;
    private static final int EXECUTION_DASH_COOLDOWN = 260;
    private static final int DEATH_REMOVE_DELAY = 60;
    private static final int MAX_SUMMONED_BATS = 3;
    private static final int DRAIN_LIFE_DURATION_TICKS = 8;
    private static final int HEMOPLAGUE_MARK_TICKS = 140;
    private static final double MELEE_BLEED_GRACE_TICKS = 10.0D;
    private static final double DRAIN_LIFE_RANGE = 6.0D;
    private static final double DRAIN_LIFE_TICK_DAMAGE = 4.0D;
    private static final double DRAIN_LIFE_EMPOWERED_MULTIPLIER = 1.55D;
    private static final double HEMOPLAGUE_RADIUS = 4.5D;
    private static final double HEMOPLAGUE_EXPLOSION_DAMAGE = 6.0D;
    private static final double HEMOPLAGUE_HEAL_MULTIPLIER = 1.00D;
    private static final double HEMOPLAGUE_DAMAGE_BONUS = 1.20D;
    private static final int MISSING_HEALTH_REGEN_INTERVAL = 20;
    private static final double MAX_MISSING_HEALTH_REGEN_PER_PULSE = 2.4D;
    private static final double TIDES_OF_BLOOD_RANGE = 7.0D;
    private static final double TIDES_OF_BLOOD_BOLT_DAMAGE = 3.0D;
    private static final double EXECUTION_DASH_DAMAGE = 7.0D;
    private static final double EXECUTION_DASH_EXECUTE_THRESHOLD = 0.15D;
    private static final double BLOOD_MAGIC_INITIAL_SPEED = 0.48D;
    private static final double BLOOD_MAGIC_HOMING_STRENGTH = 0.022D;
    private static final double BLOOD_MAGIC_HEAL_ON_HIT_PERCENT = 0.05D;
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(140, 0, 0), 1.2F);
    private static final Particle.DustOptions DARK_BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(80, 0, 0), 1.0F);
    private static final Particle.DustOptions BRIGHT_BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(190, 0, 0), 1.45F);
    private static final Particle.DustOptions SHADOW_DUST = new Particle.DustOptions(Color.fromRGB(18, 18, 22), 1.0F);

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random;
    private final Map<VampireAbility, Integer> cooldowns;
    private final Map<UUID, Long> bleedApplicationTicks;
    private final Map<UUID, BukkitRunnable> hemoplagueMarks;
    private final List<BukkitRunnable> ownedTasks;
    private final VampireBatSwarm batSwarm;
    private VampireState state;
    private VampireState stateBeforeCasting;
    private VampireAbility pendingAbility;
    private Bat disguiseBat;
    private Bat escapeBat;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private long lifeTicks;
    private int stateTicks;
    private int stalkDurationTicks;
    private int castingDurationTicks;
    private int batEscapeTicks;
    private int batEscapeSwoopTick;
    private boolean batEscapeDescending;
    private boolean cleanedUp;
    private boolean deathSequenceStarted;
    private boolean shielded;
    private boolean shieldUsed;
    private boolean visible;
    private boolean combatInitialized;

    public VampireNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.random = new Random();
        this.cooldowns = new EnumMap<>(VampireAbility.class);
        this.bleedApplicationTicks = new HashMap<>();
        this.hemoplagueMarks = new HashMap<>();
        this.ownedTasks = new ArrayList<>();
        this.batSwarm = new VampireBatSwarm(plugin, this);
        this.state = VampireState.DISGUISED_BAT;
        this.stateBeforeCasting = VampireState.COMBAT;
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        this.stalkDurationTicks = randomStalkDuration();
        configureNpc();
        spawnDisguiseBat();
        startController();
    }

    /**
     * Returns the Citizens NPC backing this vampire.
     *
     * @return Citizens NPC
     */
    public NPC getNpc() {
        return npc;
    }

    /**
     * Returns the current state.
     *
     * @return vampire state
     */
    public VampireState getState() {
        return state;
    }

    /**
     * Returns whether the vampire is dead or cleaned up.
     *
     * @return true if no longer active
     */
    public boolean isDead() {
        return state == VampireState.DEAD || cleanedUp || deathSequenceStarted;
    }

    /**
     * Returns whether the temporary blood shield is active.
     *
     * @return shield state
     */
    public boolean isShielded() {
        return shielded;
    }

    /**
     * Returns the best current location for this vampire.
     *
     * @return active entity location or last known location
     */
    public Location getCurrentLocation() {
        if (state == VampireState.DISGUISED_BAT && disguiseBat != null && disguiseBat.isValid()) {
            return disguiseBat.getLocation();
        }
        if (state == VampireState.BAT_FORM_ESCAPE && escapeBat != null && escapeBat.isValid()) {
            return escapeBat.getLocation();
        }
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            return entity.getLocation();
        }
        return lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone();
    }

    public double getCurrentHealth() {
        LivingEntity entity = getLivingEntity();
        return entity == null ? plugin.getConfigManager().getVampireHealth() : Math.max(0.0D, entity.getHealth());
    }

    public double getMaximumHealth() {
        LivingEntity entity = getLivingEntity();
        return entity == null ? plugin.getConfigManager().getVampireHealth() : getMaxHealth(entity);
    }

    public LivingEntity getHealthBarCarrier() {
        if (state == VampireState.DISGUISED_BAT && disguiseBat != null && disguiseBat.isValid()) {
            return disguiseBat;
        }
        if (state == VampireState.BAT_FORM_ESCAPE && escapeBat != null && escapeBat.isValid()) {
            return escapeBat;
        }
        return getLivingEntity();
    }

    /**
     * Called by the Citizens trait every tick as a secondary driving point.
     */
    public void onTraitTick() {
        updateLastKnownLocation();
    }

    /**
     * Called when the NPC entity is spawned by Citizens.
     *
     */
    public void onNpcSpawn() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        entity.setRemoveWhenFarAway(false);
        applyHiddenStateIfNeeded(entity);
        applyConfiguredHealth(entity);
    }

    /**
     * Applies melee side effects after a vampire hit lands.
     *
     * @param player damaged player
     */
    public void onMeleeHit(Player player) {
        if (player == null || player.isDead()) {
            return;
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.5F, 0.75F);
        player.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, player.getLocation().add(0.0D, 1.0D, 0.0D), 5, 0.25D, 0.25D, 0.25D, 0.0D);
        tryApplyBleed(player);
        if (state == VampireState.STALKING) {
            transitionToCombat(player);
        }
    }

    /**
     * Handles a Sentinel attack event.
     *
     * @param event attack event
     */
    public void handleSentinelAttack(SentinelAttackEvent event) {
        event.setCancelled(true);
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        if (state == VampireState.DISGUISED_BAT || state == VampireState.STALKING || state == VampireState.CASTING || state == VampireState.BAT_FORM_ESCAPE || state == VampireState.DEAD) {
            if (state == VampireState.STALKING) {
                maintainStalkingTarget(player);
            }
            return;
        }
        target = player;
        if (random.nextDouble() < 0.12D) {
            VampireAbility ability = chooseAbility();
            if (ability != null && canUseAbility(ability)) {
                startCasting(ability);
            }
        }
    }

    /**
     * Applies shield reduction to incoming damage.
     *
     * @param damage raw incoming damage
     * @return reduced damage
     */
    public double reduceIncomingDamage(double damage) {
        if (!shielded) {
            return damage;
        }
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 1.1D, 0.0D), 12, 0.45D, 0.55D, 0.45D, 0.0D, BRIGHT_BLOOD_DUST);
            world.playSound(location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.35F, 1.7F);
        }
        return damage * 0.5D;
    }

    /**
     * Starts the death animation, loot drop, and delayed cleanup.
     */
    public void startDeathSequence() {
        if (deathSequenceStarted) {
            return;
        }
        deathSequenceStarted = true;
        state = VampireState.DEAD;
        stateTicks = 0;

        Location deathLocation = getCurrentLocation();
        lastKnownLocation = deathLocation.clone();
        cancelControllerOnly();
        cancelOwnedTasks();
        removeDisguiseBat();
        removeEscapeBat();
        batSwarm.cleanup();
        setNpcVisible();

        World world = deathLocation.getWorld();
        if (world != null) {
            spawnDeathParticles(world, deathLocation);
            world.playSound(deathLocation, Sound.ENTITY_WITHER_DEATH, 1.0F, 0.55F);
            world.playSound(deathLocation, Sound.ENTITY_EVOKER_DEATH, 0.9F, 0.72F);
            dropLoot(world, deathLocation);
        }

        BukkitRunnable removalTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanup();
            }
        };
        ownedTasks.add(removalTask);
        removalTask.runTaskLater(plugin, DEATH_REMOVE_DELAY);
    }

    /**
     * Removes every entity and task owned by this vampire.
     */
    public void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        cancelControllerOnly();
        clearHemoplagueMarks();
        cancelOwnedTasks();
        removeDisguiseBat();
        removeEscapeBat();
        batSwarm.cleanup();
        LivingEntity carrier = getHealthBarCarrier();
        if (carrier != null) {
            plugin.getOverheadHealthBarManager().removeBar(carrier.getUniqueId());
        }
        if (npc.isSpawned()) {
            npc.despawn();
        }
        npc.destroy();
        plugin.getNPCManager().unregisterVampire(npc.getId());
    }

    /**
     * Checks whether a player should trigger bat transformation.
     *
     * @param player moving player
     */
    public void checkProximity(Player player) {
        if (state != VampireState.DISGUISED_BAT || disguiseBat == null || !disguiseBat.isValid()) {
            return;
        }
        if (player.getWorld() != disguiseBat.getWorld()) {
            return;
        }
        if (player.getLocation().distanceSquared(disguiseBat.getLocation()) <= TRANSFORM_RADIUS * TRANSFORM_RADIUS) {
            transformFromBat(player);
        }
    }

    /**
     * Returns a random double in range.
     *
     * @param min minimum
     * @param max maximum
     * @return random value
     */
    public double randomDouble(double min, double max) {
        return min + (random.nextDouble() * (max - min));
    }

    /**
     * Finds the nearest player to a location.
     *
     * @param location search center
     * @param radius search radius
     * @return nearest player or null
     */
    public Player findNearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        double radiusSquared = radius * radius;
        return location.getWorld().getPlayers().stream()
            .filter(player -> !player.isDead())
            .filter(player -> player.getLocation().distanceSquared(location) <= radiusSquared)
            .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    private void configureNpc() {
        npc.data().set("bloodmoon-vampire", true);
        npc.data().set("nameplate-visible", false);
        npc.data().set("always-use-name-hologram", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        configureVampireTrait();
        configureSkin();
        configureSentinel();
        spawnHiddenNpc();
    }

    private void configureVampireTrait() {
        VampireTrait trait = npc.getOrAddTrait(VampireTrait.class);
        trait.bind(this);
    }

    private void configureSkin() {
        String skinName = plugin.getConfigManager().getVampireSkinName();
        String texture = plugin.getConfigManager().getVampireSkinTexture();
        String signature = plugin.getConfigManager().getVampireSkinSignature();
        if ((skinName == null || skinName.isBlank()) && (texture == null || texture.isBlank())) {
            return;
        }
        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = npc.getOrAddTrait(skinTraitClass);
            Method setShouldUpdateSkins = skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class);
            Method setFetchDefaultSkin = skinTraitClass.getMethod("setFetchDefaultSkin", boolean.class);
            setShouldUpdateSkins.invoke(skinTrait, false);
            setFetchDefaultSkin.invoke(skinTrait, false);

            if (texture != null && !texture.isBlank() && signature != null && !signature.isBlank()) {
                Method setSkinPersistent = skinTraitClass.getMethod("setSkinPersistent", String.class, String.class, String.class);
                String cacheKey = (skinName == null || skinName.isBlank()) ? "bloodmoon_selected_vampire" : skinName;
                setSkinPersistent.invoke(skinTrait, cacheKey, signature, texture);
                return;
            }

            if (skinName != null && !skinName.isBlank()) {
                Method setSkinName = skinTraitClass.getMethod("setSkinName", String.class, boolean.class);
                setSkinName.invoke(skinTrait, skinName, true);
                return;
            }

            plugin.getLogger().warning("Vampire NPC " + npc.getId() + " has no valid Citizens skin configuration.");
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply Citizens SkinTrait to vampire NPC " + npc.getId() + ": " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.setInvincible(false);
        sentinel.setHealth(plugin.getConfigManager().getVampireHealth());
        sentinel.health = plugin.getConfigManager().getVampireHealth();
        sentinel.damage = 0.0D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange = 30.0D;
        sentinel.armor = 0.2D;
        sentinel.protectFromIgnores = false;
        sentinel.allTargets = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
        sentinel.addIgnore("npcs");
        npc.setProtected(false);
    }

    private void spawnHiddenNpc() {
        Location hiddenLocation = spawnLocation.clone();
        if (!npc.isSpawned()) {
            npc.spawn(hiddenLocation);
        }
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
            entity.setInvulnerable(false);
            entity.setMaximumNoDamageTicks(0);
            entity.setNoDamageTicks(0);
            hideNameplate(entity);
            applyHiddenStateIfNeeded(entity);
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
            plugin.getLogger().warning("Could not hide NPC nameplate: " + ex.getMessage());
        }
    }

    private void spawnDisguiseBat() {
        World world = spawnLocation.getWorld();
        if (world == null) {
            return;
        }
        disguiseBat = (Bat) world.spawnEntity(spawnLocation, EntityType.BAT);
        disguiseBat.setCustomName(null);
        disguiseBat.setCustomNameVisible(false);
        disguiseBat.setAwake(true);
        disguiseBat.setRemoveWhenFarAway(false);
        plugin.getNPCManager().registerBat(disguiseBat);
        world.spawnParticle(Particle.LARGE_SMOKE, spawnLocation, 8, 0.3D, 0.3D, 0.3D, 0.02D);
        lastKnownLocation = spawnLocation.clone();
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
        if (cleanedUp || deathSequenceStarted) {
            return;
        }
        lifeTicks++;
        stateTicks++;
        decrementCooldowns();
        updateLastKnownLocation();
        switch (state) {
            case DISGUISED_BAT -> tickDisguisedBat();
            case STALKING -> tickStalking();
            case COMBAT -> tickCombat();
            case CASTING -> tickCasting();
            case BAT_FORM_ESCAPE -> tickBatFormEscape();
            case DEAD -> {
            }
        }
    }

    private void tickDisguisedBat() {
        if (disguiseBat == null || !disguiseBat.isValid() || disguiseBat.isDead()) {
            Location respawn = lastKnownLocation == null ? spawnLocation : lastKnownLocation;
            spawnDisguiseBatAt(respawn);
            return;
        }

        if (stateTicks % BAT_MOVE_INTERVAL == 0) {
            Player nearest = findNearestPlayer(disguiseBat.getLocation(), 64.0D);
            if (nearest != null) {
                moveDisguisedBatToward(nearest);
            } else {
                wanderDisguisedBat();
            }
        }

        if (stateTicks % BAT_PROXIMITY_INTERVAL == 0) {
            Player nearest = findNearestPlayer(disguiseBat.getLocation(), TRANSFORM_RADIUS);
            if (nearest != null) {
                transformFromBat(nearest);
            }
        }
    }

    private void spawnDisguiseBatAt(Location location) {
        spawnDisguiseBat();
        if (disguiseBat != null && location != null) {
            disguiseBat.teleport(location);
        }
    }

    private void moveDisguisedBatToward(Player player) {
        Vector direction = player.getEyeLocation().toVector().subtract(disguiseBat.getLocation().toVector());
        if (direction.lengthSquared() < 0.05D) {
            return;
        }
        direction.normalize().multiply(0.30D);
        Vector erratic = new Vector(
            randomDouble(-0.11D, 0.11D),
            randomDouble(-0.06D, 0.13D),
            randomDouble(-0.11D, 0.11D)
        );
        disguiseBat.setVelocity(direction.add(erratic));
        disguiseBat.getWorld().spawnParticle(Particle.PORTAL, disguiseBat.getLocation(), 2, 0.12D, 0.12D, 0.12D, 0.01D);
    }

    private void wanderDisguisedBat() {
        Vector velocity = new Vector(
            randomDouble(-0.20D, 0.20D),
            randomDouble(-0.05D, 0.15D),
            randomDouble(-0.20D, 0.20D)
        );
        disguiseBat.setVelocity(velocity);
    }

    private void transformFromBat(Player player) {
        if (state != VampireState.DISGUISED_BAT) {
            return;
        }

        target = player;
        Location transformLocation = disguiseBat != null && disguiseBat.isValid()
            ? disguiseBat.getLocation().clone()
            : getCurrentLocation();
        World world = transformLocation.getWorld();

        if (world != null) {
            world.playSound(transformLocation, Sound.ENTITY_BAT_DEATH, 1.0F, 0.7F);
            world.spawnParticle(Particle.LARGE_SMOKE, transformLocation, 50, 0.6D, 0.8D, 0.6D, 0.08D);
            world.spawnParticle(Particle.PORTAL, transformLocation, 50, 0.6D, 0.8D, 0.6D, 0.2D);
        }

        removeDisguiseBat();
        ensureNpcSpawned(transformLocation);
        npc.teleport(transformLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        setNpcVisible();
        applyTransformDarkness(transformLocation);

        if (world != null) {
            world.playSound(transformLocation, Sound.ENTITY_WITHER_AMBIENT, 1.0F, 0.6F);
        }

        transitionToStalking(player);
    }

    private void applyTransformDarkness(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        double radiusSquared = DARKNESS_RADIUS * DARKNESS_RADIUS;
        for (Player nearby : world.getPlayers()) {
            if (nearby.getLocation().distanceSquared(location) <= radiusSquared) {
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 1, true, true, true));
            }
        }
    }

    private void transitionToStalking(Player player) {
        state = VampireState.STALKING;
        stateTicks = 0;
        stalkDurationTicks = randomStalkDuration();
        target = player;
        combatInitialized = false;
        setNavigationSpeed(0.6F);
        maintainStalkingTarget(player);
    }

    private void tickStalking() {
        Player player = ensureTarget(32.0D);
        if (player == null) {
            player = findNearestPlayer(getCurrentLocation(), 48.0D);
            target = player;
        }

        if (player != null) {
            maintainStalkingTarget(player);
        }

        if (stateTicks % STALK_SOUND_INTERVAL == 0) {
            Location location = getCurrentLocation();
            World world = location.getWorld();
            if (world != null) {
                world.playSound(location, Sound.ENTITY_ENDERMAN_STARE, 0.3F, 0.8F);
            }
        }

        if (stateTicks % 5 == 0) {
            spawnStalkingParticles();
        }

        if (stateTicks >= stalkDurationTicks && player != null) {
            transitionToCombat(player);
        }
    }

    private void maintainStalkingTarget(Player player) {
        if (player == null || !npc.isSpawned()) {
            return;
        }
        npc.faceLocation(player.getEyeLocation());
        Navigator navigator = npc.getNavigator();
        navigator.getDefaultParameters().speedModifier(0.6F);
        navigator.setTarget(player, false);
    }

    private void spawnStalkingParticles() {
        Location location = getCurrentLocation().add(randomDouble(-0.25D, 0.25D), randomDouble(0.4D, 1.7D), randomDouble(-0.25D, 0.25D));
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.DUST, location, 3, 0.12D, 0.12D, 0.12D, 0.0D, DARK_BLOOD_DUST);
    }

    private void transitionToCombat(Player player) {
        state = VampireState.COMBAT;
        stateTicks = 0;
        target = player;
        setNpcVisible();
        initializeCombat();
    }

    private void initializeCombat() {
        if (combatInitialized) {
            return;
        }
        combatInitialized = true;
        setNavigationSpeed(1.4F);
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.allTargets = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
        sentinel.addIgnore("npcs");
        sentinel.chaseRange = 30.0D;
        sentinel.respawnTime = -1;
    }

    private void tickCombat() {
        initializeCombat();
        Player player = ensureTarget(48.0D);
        if (player == null) {
            player = findNearestPlayer(getCurrentLocation(), 48.0D);
            target = player;
        }
        if (player == null) {
            setNavigationSpeed(0.8F);
            return;
        }

        target = player;
        chaseCombatTarget(player);
        maybeTriggerBloodShieldAtLowHealth();
        tickMissingHealthRegeneration();

        if (stateTicks % COMBAT_AMBIENT_INTERVAL == 0) {
            Location location = getCurrentLocation();
            World world = location.getWorld();
            if (world != null) {
                world.playSound(location, Sound.ENTITY_BLAZE_AMBIENT, 0.65F, 0.45F);
            }
        }

        if (stateTicks % COMBAT_ABILITY_INTERVAL == 0) {
            VampireAbility ability = chooseAbility();
            if (ability != null && canUseAbility(ability)) {
                startCasting(ability);
            }
        }
    }

    private void chaseCombatTarget(Player player) {
        if (!npc.isSpawned()) {
            return;
        }
        setNavigationSpeed(1.4F);
        npc.getNavigator().setTarget(player, true);
        npc.faceLocation(player.getEyeLocation());
    }

    private void maybeTriggerBloodShieldAtLowHealth() {
        if (shieldUsed || shielded || state != VampireState.COMBAT) {
            return;
        }
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        double max = getMaxHealth(entity);
        if (max <= 0.0D) {
            return;
        }
        if (entity.getHealth() <= max * 0.5D) {
            startCasting(VampireAbility.BLOOD_SHIELD);
        }
    }

    private void startCasting(VampireAbility ability) {
        if (ability == null || state == VampireState.DEAD || state == VampireState.CASTING) {
            return;
        }
        if (!canUseAbility(ability)) {
            return;
        }
        pendingAbility = ability;
        stateBeforeCasting = state == VampireState.BAT_FORM_ESCAPE ? VampireState.COMBAT : state;
        state = VampireState.CASTING;
        stateTicks = 0;
        castingDurationTicks = switch (ability) {
            case HEMOPLAGUE -> random.nextInt(11) + 30;
            case TIDES_OF_BLOOD -> random.nextInt(9) + 34;
            case EXECUTION_DASH -> random.nextInt(5) + 18;
            default -> random.nextInt(11) + 20;
        };
        Navigator navigator = npc.getNavigator();
        navigator.cancelNavigation();
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9F, 0.75F);
        }
    }

    private void tickCasting() {
        runCastingParticles();
        updateCastingAnimation();
        if (stateTicks < castingDurationTicks) {
            return;
        }
        VampireAbility ability = pendingAbility;
        pendingAbility = null;
        executeAbility(ability);
        resetCastingAnimation();
        if (state == VampireState.CASTING) {
            state = stateBeforeCasting == VampireState.STALKING ? VampireState.COMBAT : stateBeforeCasting;
            stateTicks = 0;
        }
    }

    private void runCastingParticles() {
        Location base = getCurrentLocation();
        World world = base.getWorld();
        if (world == null) {
            return;
        }
        VampireAbility ability = pendingAbility;
        if (ability == null) {
            spawnGenericCastingRing(world, base);
            return;
        }

        switch (ability) {
            case BLOOD_MAGIC -> spawnBloodMagicCastingParticles(world, base);
            case DRAIN_LIFE -> spawnDrainLifeCastingParticles(world, base);
            case HEMOPLAGUE -> spawnHemoplagueCastingParticles(world, base);
            case SUMMON_BATS -> spawnSummonBatsCastingParticles(world, base);
            case SHADOW_DASH -> spawnShadowDashCastingParticles(world, base);
            case EXECUTION_DASH -> spawnExecutionDashCastingParticles(world, base);
            case TIDES_OF_BLOOD -> spawnTidesOfBloodCastingParticles(world, base);
            case BLOOD_SHIELD -> spawnBloodShieldCastingParticles(world, base);
            case BAT_FORM_ESCAPE -> spawnGenericCastingRing(world, base);
        }
    }

    private void updateCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (!(entity instanceof Player player)) {
            return;
        }
        if (target != null && target.isOnline() && !target.isDead()) {
            npc.faceLocation(target.getEyeLocation());
        }

        VampireAbility ability = pendingAbility;
        if (ability == null) {
            return;
        }

        switch (ability) {
            case BLOOD_MAGIC -> animateBloodMagicCasting(player);
            case DRAIN_LIFE -> animateDrainLifeCasting(player);
            case HEMOPLAGUE -> animateHemoplagueCasting(player);
            case SUMMON_BATS -> animateSummonBatsCasting(player);
            case SHADOW_DASH -> animateShadowDashCasting(player);
            case EXECUTION_DASH -> animateExecutionDashCasting(player);
            case TIDES_OF_BLOOD -> animateTidesOfBloodCasting(player);
            case BLOOD_SHIELD -> animateBloodShieldCasting(player);
            case BAT_FORM_ESCAPE -> animateEscapeCasting(player);
        }
    }

    private void resetCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (entity instanceof Player player) {
            playCitizensPlayerAnimation(player, "STOP_USE_ITEM");
        }
    }

    private void spawnGenericCastingRing(World world, Location base) {
        double angle = stateTicks * 0.45D;
        for (int step = 0; step < 3; step++) {
            double offset = angle + (step * ((Math.PI * 2.0D) / 3.0D));
            Location point = base.clone().add(Math.cos(offset) * 0.75D, 0.35D + (stateTicks % 20) * 0.045D, Math.sin(offset) * 0.75D);
            world.spawnParticle(Particle.WITCH, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
    }

    private void spawnBloodMagicCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        double angle = stateTicks * 0.42D;
        for (int step = 0; step < 3; step++) {
            double offset = angle + (step * ((Math.PI * 2.0D) / 3.0D));
            double radius = 0.48D + Math.sin((stateTicks * 0.25D) + step) * 0.08D;
            double height = 1.0D + (step * 0.16D);
            Location handPoint = base.clone().add(Math.cos(offset) * radius, height, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST, handPoint, 3, 0.07D, 0.10D, 0.07D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.DUST, handPoint, 2, 0.05D, 0.08D, 0.05D, 0.0D, DARK_BLOOD_DUST);
            world.spawnParticle(Particle.DUST, handPoint, 1, 0.04D, 0.06D, 0.04D, 0.0D, SHADOW_DUST);
            world.spawnParticle(Particle.SMOKE, handPoint, 1, 0.03D, 0.03D, 0.03D, 0.002D);
        }
        if (stateTicks % 5 == 0) {
            Location crown = base.clone().add(0.0D, 1.8D, 0.0D);
            world.spawnParticle(Particle.DUST, crown, 4, 0.18D, 0.08D, 0.18D, 0.0D, DARK_BLOOD_DUST);
            world.spawnParticle(Particle.DUST, crown, 3, 0.14D, 0.06D, 0.14D, 0.0D, SHADOW_DUST);
        }
    }

    private void spawnDrainLifeCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        if (target == null || !target.isOnline() || target.isDead()) {
            return;
        }
        Location source = base.clone().add(0.0D, 1.2D, 0.0D);
        Location destination = target.getEyeLocation();
        for (int step = 0; step < 6; step++) {
            double progress = ((stateTicks * 0.11D) + (step * 0.14D)) % 1.0D;
            Location point = source.clone().add(destination.clone().subtract(source).toVector().multiply(progress));
            world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.03D, 0.03D, 0.0D, DARK_BLOOD_DUST);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, point, 1, 0.02D, 0.02D, 0.02D, 0.0D,
                new Particle.DustTransition(Color.fromRGB(60, 0, 0), Color.fromRGB(180, 0, 0), 1.0F));
            world.spawnParticle(Particle.DAMAGE_INDICATOR, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
    }

    private void spawnHemoplagueCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        double angle = stateTicks * 0.34D;
        for (int step = 0; step < 8; step++) {
            double offset = angle + (step * ((Math.PI * 2.0D) / 8.0D));
            double radius = 0.8D + Math.sin((stateTicks * 0.18D) + step) * 0.18D;
            double height = 0.45D + (step % 3) * 0.28D;
            Location point = base.clone().add(Math.cos(offset) * radius, height, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST, point, 2, 0.04D, 0.06D, 0.04D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.05D, 0.03D, 0.0D, DARK_BLOOD_DUST);
            if (step % 2 == 0) {
                world.spawnParticle(Particle.SMOKE, point, 1, 0.02D, 0.03D, 0.02D, 0.01D);
            }
        }
    }

    private void spawnSummonBatsCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.72D;
        for (int step = 0; step < 10; step++) {
            double offset = angle + (step * ((Math.PI * 2.0D) / 10.0D));
            double radius = 0.7D + (step % 2) * 0.45D;
            double height = 0.2D + ((step % 4) * 0.22D);
            Location point = base.clone().add(Math.cos(offset) * radius, height, Math.sin(offset) * radius);
            world.spawnParticle(Particle.PORTAL, point, 2, 0.05D, 0.06D, 0.05D, 0.02D);
            world.spawnParticle(Particle.SMOKE, point, 2, 0.03D, 0.03D, 0.03D, 0.015D);
            world.spawnParticle(Particle.SOUL, point, 1, 0.02D, 0.02D, 0.02D, 0.005D);
        }
        if (stateTicks % 6 == 0) {
            world.playSound(base, Sound.ENTITY_BAT_TAKEOFF, 0.35F, 0.8F + (random.nextFloat() * 0.5F));
        }
    }

    private void spawnShadowDashCastingParticles(World world, Location base) {
        double angle = stateTicks * 1.05D;
        for (int step = 0; step < 9; step++) {
            double offset = angle + (step * ((Math.PI * 2.0D) / 9.0D));
            double radius = 0.35D + (step * 0.08D);
            Location point = base.clone().add(Math.cos(offset) * radius, 0.05D + (step % 3) * 0.18D, Math.sin(offset) * radius);
            world.spawnParticle(Particle.SMOKE, point, 2, 0.04D, 0.02D, 0.04D, 0.018D);
            world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.02D, 0.03D, 0.0D, DARK_BLOOD_DUST);
            if (step % 2 == 0) {
                world.spawnParticle(Particle.CRIT, point, 1, 0.01D, 0.01D, 0.01D, 0.0D);
            }
        }
    }

    private void spawnExecutionDashCastingParticles(World world, Location base) {
        double pulse = 0.22D + Math.sin(stateTicks * 0.25D) * 0.08D;
        for (int step = 0; step < 14; step++) {
            double angle = (Math.PI * 2.0D * step) / 14.0D;
            Location point = base.clone().add(Math.cos(angle) * (0.55D + pulse), 1.0D + Math.sin(angle * 2.0D) * 0.22D, Math.sin(angle) * (0.55D + pulse));
            world.spawnParticle(Particle.DUST, point, 1, 0.02D, 0.02D, 0.02D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.DUST, point, 1, 0.02D, 0.02D, 0.02D, 0.0D, DARK_BLOOD_DUST);
            if (step % 2 == 0) {
                world.spawnParticle(Particle.SMOKE, point, 1, 0.02D, 0.02D, 0.02D, 0.008D);
            }
        }
    }

    private void spawnTidesOfBloodCastingParticles(World world, Location base) {
        Location orbCenter = base.clone().add(0.0D, 2.1D + Math.sin(stateTicks * 0.10D) * 0.12D, 0.0D);
        world.spawnParticle(Particle.DUST, orbCenter, 8, 0.18D, 0.16D, 0.18D, 0.0D, BRIGHT_BLOOD_DUST);
        world.spawnParticle(Particle.DUST, orbCenter, 4, 0.14D, 0.12D, 0.14D, 0.0D, DARK_BLOOD_DUST);

        double orbit = stateTicks * 0.34D;
        for (int step = 0; step < 10; step++) {
            double angle = orbit + step * ((Math.PI * 2.0D) / 10.0D);
            double radius = 0.18D + (step % 3) * 0.08D;
            Location point = orbCenter.clone().add(Math.cos(angle) * radius, Math.sin(angle * 1.7D) * 0.11D, Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, point, 1, 0.02D, 0.02D, 0.02D, 0.0D, BRIGHT_BLOOD_DUST);
            if (step % 2 == 0) {
                world.spawnParticle(Particle.CRIT, point, 1, 0.01D, 0.01D, 0.01D, 0.0D);
            }
        }

        for (int strand = 0; strand < 6; strand++) {
            double phase = stateTicks * 0.22D + strand;
            double x = Math.cos(phase) * 0.32D;
            double z = Math.sin(phase) * 0.32D;
            Location handArc = base.clone().add(x, 1.05D + strand * 0.16D, z);
            world.spawnParticle(Particle.DUST, handArc, 1, 0.015D, 0.015D, 0.015D, 0.0D, DARK_BLOOD_DUST);
        }

        if (stateTicks % 7 == 0) {
            world.playSound(base, Sound.BLOCK_BREWING_STAND_BREW, 0.25F, 0.55F);
        }
    }

    private void spawnBloodShieldCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.34D;
        for (int step = 0; step < 14; step++) {
            double offset = angle + (step * ((Math.PI * 2.0D) / 14.0D));
            double height = 0.25D + ((step % 4) * 0.38D);
            double radius = 0.9D + ((step % 3) * 0.12D);
            Location point = base.clone().add(Math.cos(offset) * radius, height, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST, point, 2, 0.04D, 0.06D, 0.04D, 0.0D, BLOOD_DUST);
            if (step % 2 == 0) {
                world.spawnParticle(Particle.WITCH, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
        }
        if (stateTicks % 6 == 0) {
            world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.25F, 0.45F);
        }
    }

    private void animateBloodMagicCasting(Player player) {
        if (stateTicks % 4 == 0) {
            player.swingMainHand();
            playCitizensPlayerAnimation(player, "ARM_SWING");
        }
        if (stateTicks % 8 == 0) {
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
            playCitizensPlayerAnimation(player, "START_USE_MAINHAND_ITEM");
            playCitizensPlayerAnimation(player, "START_USE_OFFHAND_ITEM");
        }
    }

    private void animateDrainLifeCasting(Player player) {
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(player, "START_USE_MAINHAND_ITEM");
            playCitizensPlayerAnimation(player, "START_USE_OFFHAND_ITEM");
        }
        if (stateTicks % 10 == 0) {
            player.swingMainHand();
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING");
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
        }
    }

    private void animateHemoplagueCasting(Player player) {
        if (stateTicks % 4 == 0) {
            playCitizensPlayerAnimation(player, "START_USE_MAINHAND_ITEM");
            playCitizensPlayerAnimation(player, "START_USE_OFFHAND_ITEM");
        }
        if (stateTicks % 8 == 0) {
            player.swingMainHand();
            playCitizensPlayerAnimation(player, "ARM_SWING");
        }
        if (stateTicks % 12 == 0) {
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
        }
    }

    private void animateSummonBatsCasting(Player player) {
        if (stateTicks % 2 == 0) {
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
        }
        if (stateTicks % 5 == 0) {
            player.swingMainHand();
            playCitizensPlayerAnimation(player, "ARM_SWING");
        }
    }

    private void animateShadowDashCasting(Player player) {
        if (stateTicks % 2 == 0) {
            player.swingMainHand();
            playCitizensPlayerAnimation(player, "ARM_SWING");
        }
        if (stateTicks % 4 == 0) {
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
        }
    }

    private void animateExecutionDashCasting(Player player) {
        if (stateTicks % 3 == 0) {
            player.swingMainHand();
            playCitizensPlayerAnimation(player, "ARM_SWING");
        }
        if (stateTicks % 6 == 0) {
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
            playCitizensPlayerAnimation(player, "START_USE_MAINHAND_ITEM");
        }
    }

    private void animateTidesOfBloodCasting(Player player) {
        if (stateTicks % 3 == 0) {
            playCitizensPlayerAnimation(player, "START_USE_MAINHAND_ITEM");
        }
        if (stateTicks % 9 == 0) {
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
        }
    }

    private void animateBloodShieldCasting(Player player) {
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(player, "START_USE_MAINHAND_ITEM");
            playCitizensPlayerAnimation(player, "START_USE_OFFHAND_ITEM");
        }
        if (stateTicks % 10 == 0) {
            player.swingOffHand();
            playCitizensPlayerAnimation(player, "ARM_SWING_OFFHAND");
        }
    }

    private void animateEscapeCasting(Player player) {
        if (stateTicks % 4 == 0) {
            player.swingMainHand();
            playCitizensPlayerAnimation(player, "ARM_SWING");
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void playCitizensPlayerAnimation(Player player, String animationName) {
        try {
            Class<? extends Enum> animationClass = Class.forName("net.citizensnpcs.util.PlayerAnimation").asSubclass(Enum.class);
            Enum animation = Enum.valueOf(animationClass, animationName);
            Method playMethod = animationClass.getMethod("play", Player.class);
            playMethod.invoke(animation, player);
        } catch (ReflectiveOperationException ignored) {
            // Citizens implementation classes are not on the compile classpath; hand animation falls back to Bukkit swings.
        }
    }

    private void executeAbility(VampireAbility ability) {
        if (ability == null) {
            return;
        }
        switch (ability) {
            case BLOOD_MAGIC -> castBloodMagic();
            case DRAIN_LIFE -> castDrainLife();
            case HEMOPLAGUE -> castHemoplague();
            case BAT_FORM_ESCAPE -> castBatFormEscape();
            case SUMMON_BATS -> castSummonBats();
            case SHADOW_DASH -> castShadowDash();
            case EXECUTION_DASH -> castExecutionDash();
            case TIDES_OF_BLOOD -> castTidesOfBlood();
            case BLOOD_SHIELD -> castBloodShield();
        }
    }

    private VampireAbility chooseAbility() {
        Player player = ensureTarget(48.0D);
        if (player == null) {
            return null;
        }

        List<AbilityWeight> weights = new ArrayList<>();
        for (VampireAbility ability : VampireAbility.values()) {
            if (!canUseAbility(ability)) {
                continue;
            }
            int weight = ability.getWeight();
            if (ability == VampireAbility.DRAIN_LIFE && isBelowHealthPercent(0.30D)) {
                weight *= 2;
            }
            if (ability == VampireAbility.EXECUTION_DASH && isBelowHealthPercent(0.35D)) {
                weight += 4;
            }
            if (ability == VampireAbility.HEMOPLAGUE) {
                int hemoplagueTargets = findHemoplagueTargets(player).size();
                int requiredTargets = getHemoplagueRequiredTargetCount();
                if (hemoplagueTargets < requiredTargets) {
                    continue;
                }
                if (hemoplagueTargets >= 3) {
                    weight += 6;
                } else if (requiredTargets == 1 && hemoplagueTargets == 1) {
                    weight += 4;
                }
            }
            if (ability == VampireAbility.BLOOD_SHIELD && (!isBelowHealthPercent(0.50D) || shieldUsed)) {
                continue;
            }
            if (ability == VampireAbility.SUMMON_BATS && batSwarm.size() >= MAX_SUMMONED_BATS) {
                continue;
            }
            weights.add(new AbilityWeight(ability, weight));
        }

        int total = weights.stream().mapToInt(AbilityWeight::weight).sum();
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        int cursor = 0;
        for (AbilityWeight weight : weights) {
            cursor += weight.weight();
            if (roll < cursor) {
                return weight.ability();
            }
        }
        return weights.get(weights.size() - 1).ability();
    }

    private boolean canUseAbility(VampireAbility ability) {
        if (ability == null || state == VampireState.DEAD) {
            return false;
        }
        if (cooldowns.getOrDefault(ability, 0) > 0) {
            return false;
        }
        return switch (ability) {
            case BLOOD_SHIELD -> !shieldUsed && !shielded;
            case HEMOPLAGUE -> target != null && target.isOnline() && !target.isDead()
                && findHemoplagueTargets(target).size() >= getHemoplagueRequiredTargetCount();
            case SUMMON_BATS -> batSwarm.size() < MAX_SUMMONED_BATS;
            case SHADOW_DASH, EXECUTION_DASH -> target != null && target.isOnline() && !target.isDead();
            case TIDES_OF_BLOOD -> countNearbyHostiles(getCurrentLocation(), TIDES_OF_BLOOD_RANGE) > 0;
            case BAT_FORM_ESCAPE -> target != null && target.isOnline() && !target.isDead();
            case BLOOD_MAGIC, DRAIN_LIFE -> target != null && target.isOnline() && !target.isDead();
        };
    }

    private int getHemoplagueRequiredTargetCount() {
        return isBelowHealthPercent(0.30D) ? 1 : 2;
    }

    private int countNearbyHostiles(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return 0;
        }
        int count = 0;
        for (Entity entity : world.getNearbyEntities(center, radius, 3.5D, radius)) {
            if (entity instanceof LivingEntity living && isValidHemoplagueTarget(living)) {
                count++;
            }
        }
        return count;
    }

    private void setCooldown(VampireAbility ability, int ticks) {
        cooldowns.put(ability, Math.max(1, ticks));
    }

    private void decrementCooldowns() {
        for (VampireAbility ability : VampireAbility.values()) {
            int cooldown = cooldowns.getOrDefault(ability, 0);
            if (cooldown > 0) {
                cooldowns.put(ability, cooldown - 1);
            }
        }
    }

    private void castBloodMagic() {
        Player player = ensureTarget(40.0D);
        if (player == null) {
            state = VampireState.COMBAT;
            return;
        }
        setCooldown(VampireAbility.BLOOD_MAGIC, BLOOD_MAGIC_COOLDOWN);
        LivingEntity caster = getLivingEntity();
        if (caster == null) {
            return;
        }
        Location start = caster.getEyeLocation().add(caster.getLocation().getDirection().normalize().multiply(0.8D));
        World world = start.getWorld();
        if (world != null) {
            world.playSound(start, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.0F, 0.55F);
            world.playSound(start, Sound.BLOCK_BREWING_STAND_BREW, 0.65F, 0.75F);
            world.spawnParticle(Particle.DUST, start, 18, 0.35D, 0.25D, 0.35D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.DUST, start, 12, 0.32D, 0.22D, 0.32D, 0.0D, DARK_BLOOD_DUST);
            world.spawnParticle(Particle.DUST, start, 8, 0.28D, 0.20D, 0.28D, 0.0D, SHADOW_DUST);
            world.spawnParticle(Particle.SMOKE, start, 8, 0.40D, 0.25D, 0.40D, 0.010D);
        }
        for (int index = 0; index < BLOOD_MAGIC_VOLLEY_COUNT; index++) {
            BloodMagicProjectile projectile = new BloodMagicProjectile(
                plugin,
                caster,
                player,
                start.clone().add(0.0D, index * 0.12D, 0.0D),
                index,
                BLOOD_MAGIC_VOLLEY_DELAY,
                BLOOD_MAGIC_INITIAL_SPEED,
                BLOOD_MAGIC_HOMING_STRENGTH,
                BLOOD_MAGIC_HEAL_ON_HIT_PERCENT
            );
            projectile.launch();
        }
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void castDrainLife() {
        Player player = ensureTarget(DRAIN_LIFE_RANGE);
        LivingEntity vampire = getLivingEntity();
        if (player == null || vampire == null || !hasClearBloodPath(vampire, player, DRAIN_LIFE_RANGE)) {
            state = VampireState.COMBAT;
            return;
        }

        final boolean empowered = isBelowHealthPercent(0.30D);
        final double drainDamage = DRAIN_LIFE_TICK_DAMAGE * (empowered ? DRAIN_LIFE_EMPOWERED_MULTIPLIER : 1.0D);

        setCooldown(VampireAbility.DRAIN_LIFE, DRAIN_LIFE_COOLDOWN);
        Location location = vampire.getLocation();
        World world = location.getWorld();
        if (world != null) {
            playDrainLifeSound(location);
            if (empowered) {
                world.playSound(location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.35F, 1.65F);
                world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 1.2D, 0.0D), 24, 0.45D, 0.35D, 0.45D, 0.0D, BRIGHT_BLOOD_DUST);
            }
        }

        BukkitRunnable drainTask = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (ticks > DRAIN_LIFE_DURATION_TICKS || player.isDead() || !player.isOnline() || !npc.isSpawned()) {
                    cancel();
                    return;
                }

                LivingEntity currentVampire = getLivingEntity();
                if (currentVampire == null || currentVampire.isDead() || !hasClearBloodPath(currentVampire, player, DRAIN_LIFE_RANGE)) {
                    cancel();
                    return;
                }

                drawDrainStream(player, currentVampire, ticks * 2);

                if (ticks == 0 || ticks == 4) {
                    playDrainLifePulse(player.getLocation());
                }

                if (ticks >= DRAIN_LIFE_DURATION_TICKS) {
                    double beforeHealth = player.getHealth();
                    player.damage(drainDamage, currentVampire);
                    double actualDamage = Math.max(0.0D, beforeHealth - Math.max(0.0D, player.getHealth()));
                    healVampire(actualDamage);
                    Location vLoc = currentVampire.getLocation().add(0.0D, 1.1D, 0.0D);
                    currentVampire.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, vLoc, 8, 0.25D, 0.3D, 0.25D, 0.02D);
                    currentVampire.getWorld().spawnParticle(Particle.DUST, vLoc, 14, 0.28D, 0.25D, 0.28D, 0.0D, BRIGHT_BLOOD_DUST);
                    currentVampire.getWorld().spawnParticle(Particle.DUST, vLoc, 6, 0.2D, 0.18D, 0.2D, 0.0D, DARK_BLOOD_DUST);
                    cancel();
                    return;
                }
                ticks++;
            }
        };
        ownedTasks.add(drainTask);
        drainTask.runTaskTimer(plugin, 0L, 1L);
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void castHemoplague() {
        Player primaryTarget = ensureTarget(20.0D);
        LivingEntity vampire = getLivingEntity();
        if (primaryTarget == null || vampire == null || !hasClearBloodPath(vampire, primaryTarget, 20.0D)) {
            state = VampireState.COMBAT;
            return;
        }

        List<LivingEntity> targets = findHemoplagueTargets(primaryTarget);
        if (targets.size() < getHemoplagueRequiredTargetCount()) {
            state = VampireState.COMBAT;
            return;
        }

        setCooldown(VampireAbility.HEMOPLAGUE, HEMOPLAGUE_COOLDOWN);
        Location epicenter = primaryTarget.getLocation().clone().add(0.0D, 0.2D, 0.0D);
        World world = epicenter.getWorld();
        if (world != null) {
            spawnHemoplagueBurst(world, epicenter, targets.size());
            spawnHemoplaguePool(epicenter);
            playHemoplagueCastSound(epicenter);
        }

        for (LivingEntity marked : targets) {
            applyHemoplagueMark(marked);
        }
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void drawDrainStream(LivingEntity source, LivingEntity vampire, int animationTick) {
        drawBloodStream(source.getEyeLocation().subtract(0.0D, 0.2D, 0.0D), vampire.getEyeLocation().subtract(0.0D, 0.15D, 0.0D), animationTick, 3, 0.34D, 0.18D, true);
    }

    private void drawBloodStream(Location start, Location end, int animationTick, int strands, double waveStrength, double widthScale, boolean spawnMist) {
        World world = start.getWorld();
        if (world == null || end.getWorld() != world) {
            return;
        }

        Vector vector = end.toVector().subtract(start.toVector());
        double length = vector.length();
        if (length <= 0.15D) {
            return;
        }

        Vector direction = vector.clone().normalize();
        Vector lateral = direction.clone().crossProduct(new Vector(0.0D, 1.0D, 0.0D));
        if (lateral.lengthSquared() < 1.0E-4D) {
            lateral = direction.clone().crossProduct(new Vector(1.0D, 0.0D, 0.0D));
        }
        lateral.normalize();
        Vector vertical = lateral.clone().crossProduct(direction).normalize();

        for (int strand = 0; strand < strands; strand++) {
            double phase = (animationTick * 0.42D) + (strand * 2.1D);
            for (double progress = 0.0D; progress <= 1.0D; progress += 0.06D) {
                double coreWidth = Math.sin(progress * Math.PI) * widthScale;
                double ribbon = Math.sin((progress * Math.PI * 2.6D) + phase) * waveStrength * (0.45D + (coreWidth * 0.75D));
                double lift = Math.sin(progress * Math.PI) * 0.34D;
                double sway = Math.cos((progress * Math.PI * 3.2D) + phase * 0.8D) * 0.08D;
                Vector offset = lateral.clone().multiply(ribbon + (strand - ((strands - 1) / 2.0D)) * 0.08D)
                    .add(vertical.clone().multiply(lift + sway));
                Location point = start.clone().add(vector.clone().multiply(progress)).add(offset);
                world.spawnParticle(Particle.DUST, point, 1, 0.01D, 0.01D, 0.01D, 0.0D, BRIGHT_BLOOD_DUST);
                world.spawnParticle(Particle.DUST, point, 1, 0.015D, 0.015D, 0.015D, 0.0D, DARK_BLOOD_DUST);
                if (spawnMist && progress > 0.08D && progress < 0.92D && strand == 1) {
                    world.spawnParticle(Particle.SMOKE, point, 1, 0.008D, 0.008D, 0.008D, 0.005D);
                }
            }
        }
    }

    private boolean hasClearBloodPath(LivingEntity source, LivingEntity target, double maxDistance) {
        if (source == null || target == null || source.getWorld() != target.getWorld()) {
            return false;
        }
        if (source.getLocation().distanceSquared(target.getLocation()) > maxDistance * maxDistance) {
            return false;
        }
        return hasLineWithoutBlocking(source.getEyeLocation(), target.getEyeLocation())
            && hasLineWithoutBlocking(source.getEyeLocation(), target.getLocation().add(0.0D, 1.0D, 0.0D));
    }

    private boolean hasLineWithoutBlocking(Location from, Location to) {
        World world = from.getWorld();
        if (world == null || to.getWorld() != world) {
            return false;
        }
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance <= 0.01D) {
            return true;
        }
        RayTraceResult hit = world.rayTraceBlocks(from, direction.normalize(), distance, FluidCollisionMode.NEVER, true);
        return hit == null || hit.getHitBlock() == null;
    }

    private void playDrainLifeSound(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.playSound(location, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.1F, 1.35F);
        world.playSound(location, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.7F, 0.6F);
        world.playSound(location, Sound.ENTITY_GENERIC_DRINK, 0.55F, 0.75F);
    }

    private void playDrainLifePulse(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.playSound(location, Sound.BLOCK_HONEY_BLOCK_STEP, 0.45F, 0.7F);
        world.playSound(location, Sound.ENTITY_PLAYER_BREATH, 0.35F, 0.55F);
    }

    private List<LivingEntity> findHemoplagueTargets(LivingEntity primaryTarget) {
        World world = primaryTarget.getWorld();
        Location center = primaryTarget.getLocation();
        List<LivingEntity> targets = new ArrayList<>();
        if (!isValidHemoplagueTarget(primaryTarget)) {
            return targets;
        }
        targets.add(primaryTarget);
        for (Entity entity : world.getNearbyEntities(center, HEMOPLAGUE_RADIUS, 3.0D, HEMOPLAGUE_RADIUS)) {
            if (!(entity instanceof LivingEntity living) || living.equals(primaryTarget) || !isValidHemoplagueTarget(living)) {
                continue;
            }
            targets.add(living);
        }
        targets.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(center)));
        return targets;
    }

    private boolean isValidHemoplagueTarget(LivingEntity entity) {
        LivingEntity vampire = getLivingEntity();
        return entity != null
            && !entity.isDead()
            && entity.isValid()
            && entity.getType() != EntityType.ARMOR_STAND
            && entity.getType() != EntityType.BAT
            && entity != vampire
            && !plugin.getNPCManager().isBloodMoonNpc(entity);
    }

    private void spawnHemoplagueBurst(World world, Location center, int targetCount) {
        double radius = 1.8D + (targetCount * 0.2D);
        for (double angle = 0.0D; angle < Math.PI * 2.0D; angle += Math.PI / 14.0D) {
            Vector direction = new Vector(Math.cos(angle), 0.08D, Math.sin(angle)).multiply(radius);
            Location point = center.clone().add(direction);
            world.spawnParticle(Particle.DUST, point, 4, 0.12D, 0.05D, 0.12D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.DAMAGE_INDICATOR, point, 2, 0.08D, 0.05D, 0.08D, 0.0D);
        }
        world.spawnParticle(Particle.DUST, center.clone().add(0.0D, 0.8D, 0.0D), 32, 0.85D, 0.55D, 0.85D, 0.0D, DARK_BLOOD_DUST);
        world.spawnParticle(Particle.SMOKE, center.clone().add(0.0D, 0.5D, 0.0D), 20, 0.6D, 0.25D, 0.6D, 0.04D);
    }

    private void spawnHemoplaguePool(Location center) {
        BukkitRunnable poolTask = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (ticks > HEMOPLAGUE_MARK_TICKS) {
                    cancel();
                    return;
                }

                World world = center.getWorld();
                if (world == null) {
                    cancel();
                    return;
                }

                Location base = center.clone().add(0.0D, 0.08D, 0.0D);
                double phase = ticks * 0.09D;
                for (double radius = 0.45D; radius <= HEMOPLAGUE_RADIUS + 0.45D; radius += 0.32D) {
                    int points = Math.max(10, (int) Math.round(radius * 18.0D));
                    for (int step = 0; step < points; step++) {
                        double angle = ((Math.PI * 2.0D) / points) * step + phase + (radius * 0.18D);
                        double ripple = Math.sin((ticks * 0.12D) + step * 0.7D) * 0.07D;
                        Location point = base.clone().add(Math.cos(angle) * (radius + ripple), 0.0D, Math.sin(angle) * (radius + ripple));
                        world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.01D, 0.03D, 0.0D, step % 3 == 0 ? BRIGHT_BLOOD_DUST : DARK_BLOOD_DUST);
                    }
                }

                for (int blotch = 0; blotch < 8; blotch++) {
                    double angle = phase + (blotch * ((Math.PI * 2.0D) / 8.0D));
                    double radius = 0.55D + Math.sin((ticks * 0.08D) + blotch) * 0.35D;
                    Location point = base.clone().add(Math.cos(angle) * radius, 0.02D, Math.sin(angle) * radius);
                    world.spawnParticle(Particle.DUST, point, 2, 0.08D, 0.01D, 0.08D, 0.0D, BRIGHT_BLOOD_DUST);
                }

                if (ticks % 20 == 0) {
                    world.playSound(base, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.25F, 0.85F);
                    world.playSound(base, Sound.BLOCK_HONEY_BLOCK_STEP, 0.3F, 0.55F);
                }
                ticks += 2;
            }
        };
        ownedTasks.add(poolTask);
        poolTask.runTaskTimer(plugin, 0L, 2L);
    }

    private void playHemoplagueCastSound(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.playSound(location, Sound.BLOCK_SLIME_BLOCK_BREAK, 1.0F, 0.55F);
        world.playSound(location, Sound.BLOCK_HONEY_BLOCK_BREAK, 0.9F, 0.45F);
        world.playSound(location, Sound.ENTITY_WITHER_AMBIENT, 0.45F, 1.55F);
    }

    private void applyHemoplagueMark(LivingEntity victim) {
        removeHemoplagueMark(victim.getUniqueId());
        World world = victim.getWorld();
        Location location = victim.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        world.spawnParticle(Particle.DUST, location, 22, 0.45D, 0.65D, 0.45D, 0.0D, BRIGHT_BLOOD_DUST);
        world.spawnParticle(Particle.DUST, location, 12, 0.35D, 0.55D, 0.35D, 0.0D, DARK_BLOOD_DUST);
        world.playSound(location, Sound.ENTITY_GENERIC_DRINK, 0.55F, 0.6F);

        BukkitRunnable markTask = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                LivingEntity currentVampire = getLivingEntity();
                if (currentVampire == null || currentVampire.isDead() || victim.isDead() || !victim.isValid()) {
                    removeHemoplagueMark(victim.getUniqueId());
                    return;
                }

                spawnHemoplagueMarkPulse(victim, ticks);
                if (ticks >= HEMOPLAGUE_MARK_TICKS) {
                    removeHemoplagueMark(victim.getUniqueId());
                    detonateHemoplague(victim);
                    return;
                }

                if (ticks % 20 == 0) {
                    victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.28F, 1.7F);
                }
                ticks += 2;
            }
        };
        hemoplagueMarks.put(victim.getUniqueId(), markTask);
        ownedTasks.add(markTask);
        markTask.runTaskTimer(plugin, 0L, 2L);
    }

    private void spawnHemoplagueMarkPulse(LivingEntity victim, int animationTick) {
        Location center = victim.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        double pulse = 0.28D + (Math.sin(animationTick * 0.14D) * 0.08D);
        for (int step = 0; step < 6; step++) {
            double angle = (Math.PI * 2.0D / 6.0D) * step + (animationTick * 0.06D);
            Location point = center.clone().add(Math.cos(angle) * pulse, (step % 3) * 0.28D - 0.35D, Math.sin(angle) * pulse);
            world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.03D, 0.03D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.SMOKE, point, 1, 0.01D, 0.01D, 0.01D, 0.01D);
        }
    }

    private void detonateHemoplague(LivingEntity victim) {
        LivingEntity vampire = getLivingEntity();
        if (vampire == null || vampire.isDead()) {
            return;
        }

        Location center = victim.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        world.spawnParticle(Particle.DUST, center, 36, 0.55D, 0.75D, 0.55D, 0.0D, BRIGHT_BLOOD_DUST);
        world.spawnParticle(Particle.DUST, center, 22, 0.45D, 0.65D, 0.45D, 0.0D, DARK_BLOOD_DUST);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, center, 18, 0.45D, 0.55D, 0.45D, 0.02D);
        world.playSound(center, Sound.BLOCK_SLIME_BLOCK_BREAK, 0.95F, 0.7F);
        world.playSound(center, Sound.ENTITY_PLAYER_HURT, 0.75F, 0.5F);

        double beforeHealth = victim.getHealth();
        victim.damage(HEMOPLAGUE_EXPLOSION_DAMAGE, vampire);
        double actualDamage = Math.max(0.0D, beforeHealth - Math.max(0.0D, victim.getHealth()));
        if (actualDamage <= 0.0D) {
            return;
        }
        launchHemoplagueHealStream(victim, actualDamage);
    }

    private void launchHemoplagueHealStream(LivingEntity source, double healAmount) {
        BukkitRunnable streamTask = new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                LivingEntity currentVampire = getLivingEntity();
                if (currentVampire == null || currentVampire.isDead() || source.isDead() || !source.isValid()) {
                    cancel();
                    return;
                }

                drawBloodStream(source.getLocation().add(0.0D, 1.0D, 0.0D), currentVampire.getEyeLocation().subtract(0.0D, 0.2D, 0.0D), tick, 3, 0.42D, 0.22D, true);
                if (tick == 0) {
                    source.getWorld().playSound(source.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 0.5F, 1.45F);
                }
                if (tick >= 10) {
                    healVampire(healAmount * HEMOPLAGUE_HEAL_MULTIPLIER);
                    Location healPoint = currentVampire.getLocation().add(0.0D, 1.1D, 0.0D);
                    currentVampire.getWorld().spawnParticle(Particle.DUST, healPoint, 12, 0.25D, 0.25D, 0.25D, 0.0D, BRIGHT_BLOOD_DUST);
                    currentVampire.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, healPoint, 6, 0.2D, 0.25D, 0.2D, 0.02D);
                    cancel();
                    return;
                }
                tick++;
            }
        };
        ownedTasks.add(streamTask);
        streamTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void removeHemoplagueMark(UUID victimId) {
        BukkitRunnable existing = hemoplagueMarks.remove(victimId);
        if (existing != null) {
            ownedTasks.remove(existing);
            try {
                existing.cancel();
            } catch (IllegalStateException ignored) {
                // Defensive cleanup for tasks that may already be completed.
            }
        }
    }

    private void clearHemoplagueMarks() {
        for (UUID victimId : List.copyOf(hemoplagueMarks.keySet())) {
            removeHemoplagueMark(victimId);
        }
    }

    public boolean isHemoplagueMarked(LivingEntity entity) {
        return entity != null && hemoplagueMarks.containsKey(entity.getUniqueId());
    }

    public double getHemoplagueDamageMultiplier(LivingEntity entity) {
        return isHemoplagueMarked(entity) ? HEMOPLAGUE_DAMAGE_BONUS : 1.0D;
    }

    private void castSummonBats() {
        int toSpawn = Math.min(3, MAX_SUMMONED_BATS - batSwarm.size());
        if (toSpawn <= 0) {
            state = VampireState.COMBAT;
            return;
        }
        setCooldown(VampireAbility.SUMMON_BATS, SUMMON_BATS_COOLDOWN);
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.PORTAL, location, 30, 0.9D, 0.8D, 0.9D, 0.18D);
            world.spawnParticle(Particle.SMOKE, location, 30, 0.7D, 0.7D, 0.7D, 0.04D);
            world.spawnParticle(Particle.SOUL, location, 24, 0.75D, 0.65D, 0.75D, 0.01D);
            world.playSound(location, Sound.ENTITY_BAT_LOOP, 0.8F, 0.7F);
            world.playSound(location, Sound.ENTITY_BAT_LOOP, 0.8F, 0.9F);
            world.playSound(location, Sound.ENTITY_BAT_LOOP, 0.8F, 1.1F);
            world.playSound(location, Sound.ENTITY_PHANTOM_FLAP, 0.45F, 1.2F);
        }
        batSwarm.spawn(location, toSpawn);
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void castShadowDash() {
        Player player = ensureTarget(32.0D);
        if (player == null) {
            state = VampireState.COMBAT;
            return;
        }

        setCooldown(VampireAbility.SHADOW_DASH, SHADOW_DASH_COOLDOWN);
        Location origin = getCurrentLocation();
        Location destination = findBehindPlayerLocation(player);
        World world = origin.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.SMOKE, origin, 35, 0.5D, 0.6D, 0.5D, 0.08D);
            world.spawnParticle(Particle.PORTAL, origin, 22, 0.45D, 0.55D, 0.45D, 0.18D);
            world.spawnParticle(Particle.DUST, origin, 18, 0.32D, 0.38D, 0.32D, 0.0D, DARK_BLOOD_DUST);
            world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9F, 0.55F);
        }
        ensureNpcSpawned(destination);
        npc.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        setNpcVisible();
        if (destination.getWorld() != null) {
            destination.getWorld().spawnParticle(Particle.SMOKE, destination, 35, 0.5D, 0.6D, 0.5D, 0.08D);
            destination.getWorld().spawnParticle(Particle.PORTAL, destination, 22, 0.45D, 0.55D, 0.45D, 0.18D);
            destination.getWorld().spawnParticle(Particle.DUST, destination, 18, 0.32D, 0.38D, 0.32D, 0.0D, DARK_BLOOD_DUST);
            destination.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9F, 0.85F);
        }

        BukkitRunnable delayedAttack = new BukkitRunnable() {
            @Override
            public void run() {
                LivingEntity vampire = getLivingEntity();
                if (vampire == null || player.isDead() || !player.isOnline()) {
                    return;
                }
                if (vampire.getLocation().distanceSquared(player.getLocation()) <= 9.0D) {
                    player.damage(5.0D, vampire);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, true, true, true));
                    onMeleeHit(player);
                }
            }
        };
        ownedTasks.add(delayedAttack);
        delayedAttack.runTaskLater(plugin, 2L);
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void castExecutionDash() {
        Player player = ensureTarget(26.0D);
        LivingEntity vampire = getLivingEntity();
        if (player == null || vampire == null || player.getWorld() != vampire.getWorld()) {
            state = VampireState.COMBAT;
            return;
        }

        setCooldown(VampireAbility.EXECUTION_DASH, EXECUTION_DASH_COOLDOWN);
        Location playerBase = player.getLocation().clone().add(0.0D, 1.1D, 0.0D);
        Vector forward = player.getLocation().getDirection().setY(0.0D).normalize();
        if (forward.lengthSquared() < 0.01D) {
            forward = new Vector(0.0D, 0.0D, 1.0D);
        }
        Location start = playerBase.clone().add(forward.clone().multiply(2.2D));
        Location end = playerBase.clone().add(forward.clone().multiply(-3.2D));
        start.setYaw(getYawTowards(start, playerBase));
        start.setPitch(-5.0F);
        end.setYaw(getYawTowards(end, playerBase));
        end.setPitch(0.0F);

        ensureNpcSpawned(start);
        npc.teleport(start, PlayerTeleportEvent.TeleportCause.PLUGIN);
        setNpcVisible();

        World world = start.getWorld();
        if (world != null) {
            world.playSound(start, Sound.ENTITY_PHANTOM_FLAP, 0.9F, 0.75F);
            world.playSound(start, Sound.ENTITY_ENDERMAN_STARE, 0.55F, 0.6F);
            world.spawnParticle(Particle.DUST, start, 16, 0.22D, 0.22D, 0.22D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.SMOKE, start, 14, 0.24D, 0.24D, 0.24D, 0.01D);
        }

        BukkitRunnable dashTask = new BukkitRunnable() {
            int ticks;
            boolean hitRegistered;

            @Override
            public void run() {
                ticks++;
                LivingEntity currentVampire = getLivingEntity();
                if (currentVampire == null || isDead() || ticks > 8) {
                    finish(currentVampire);
                    return;
                }

                double progress = ticks / 8.0D;
                Location point = start.clone().add(end.toVector().subtract(start.toVector()).multiply(progress));
                point.setYaw(getYawTowards(point, end));
                point.setPitch(-4.0F);
                npc.teleport(point, PlayerTeleportEvent.TeleportCause.PLUGIN);

                World pointWorld = point.getWorld();
                if (pointWorld != null) {
                    pointWorld.spawnParticle(Particle.DUST, point.clone().add(0, 1.0D, 0), 7, 0.12D, 0.12D, 0.12D, 0.0D, BRIGHT_BLOOD_DUST);
                    pointWorld.spawnParticle(Particle.DUST, point.clone().add(0, 1.0D, 0), 6, 0.14D, 0.14D, 0.14D, 0.0D, DARK_BLOOD_DUST);
                    pointWorld.spawnParticle(Particle.SMOKE, point.clone().add(0, 1.0D, 0), 4, 0.1D, 0.1D, 0.1D, 0.008D);
                }

                if (!hitRegistered && player.isOnline() && !player.isDead() && player.getLocation().distanceSquared(point) <= 1.96D) {
                    hitRegistered = true;
                    boolean executed = false;
                    double maxHealth = getMaxHealth(player);
                    if (maxHealth > 0.0D && player.getHealth() <= maxHealth * EXECUTION_DASH_EXECUTE_THRESHOLD) {
                        player.damage(1000.0D, currentVampire);
                        executed = true;
                    } else {
                        player.damage(EXECUTION_DASH_DAMAGE, currentVampire);
                    }

                    if (executed || player.isDead() || player.getHealth() <= 0.0D) {
                        healVampire(Math.max(1.0D, getMaxHealth(currentVampire) * 0.50D));
                    } else {
                        healVampire(Math.max(0.5D, getMaxHealth(currentVampire) * 0.10D));
                    }
                    player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 30, 0, true, true, true));
                }

                if (ticks >= 8) {
                    finish(currentVampire);
                }
            }

            private void finish(LivingEntity currentVampire) {
                if (currentVampire != null) {
                    currentVampire.setFallDistance(0.0F);
                }
                state = VampireState.COMBAT;
                stateTicks = 0;
                cancel();
            }
        };
        ownedTasks.add(dashTask);
        dashTask.runTaskTimer(plugin, 0L, 1L);
    }

    private Location findBehindPlayerLocation(Player player) {
        Location base = player.getLocation().clone();
        Vector backward = base.getDirection().normalize().multiply(-2.0D);
        Location destination = base.add(backward);
        destination.setY(findSafeY(destination));
        destination.setYaw(player.getLocation().getYaw());
        destination.setPitch(0.0F);
        return destination;
    }

    private double findSafeY(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return location.getY();
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        int y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 2, location.getBlockY()));
        for (int offset = 0; offset <= 5; offset++) {
            int up = y + offset;
            Location candidate = new Location(world, x + 0.5D, up, z + 0.5D);
            if (isSafeStandLocation(candidate)) {
                return up;
            }
            int down = y - offset;
            candidate = new Location(world, x + 0.5D, down, z + 0.5D);
            if (isSafeStandLocation(candidate)) {
                return down;
            }
        }
        Block highest = world.getHighestBlockAt(x, z);
        return highest.getY() + 1.0D;
    }

    private boolean isSafeStandLocation(Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        return ground.getType().isSolid() && feet.isPassable() && head.isPassable();
    }

    private void castTidesOfBlood() {
        setCooldown(VampireAbility.TIDES_OF_BLOOD, TIDES_OF_BLOOD_COOLDOWN);
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world == null) {
            state = VampireState.COMBAT;
            return;
        }

        LivingEntity vampire = getLivingEntity();
        if (vampire == null) {
            state = VampireState.COMBAT;
            return;
        }

        Location orb = location.clone().add(0.0D, 2.15D, 0.0D);
        world.playSound(location, Sound.BLOCK_BREWING_STAND_BREW, 0.8F, 0.5F);
        world.playSound(location, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.7F, 0.65F);
        world.spawnParticle(Particle.DUST, orb, 34, 0.35D, 0.28D, 0.35D, 0.0D, BRIGHT_BLOOD_DUST);
        world.spawnParticle(Particle.DUST, orb, 18, 0.28D, 0.22D, 0.28D, 0.0D, DARK_BLOOD_DUST);

        BukkitRunnable tideTask = new BukkitRunnable() {
            private int wave;
            private final java.util.Set<UUID> hitTargets = new java.util.HashSet<>();

            @Override
            public void run() {
                LivingEntity currentVampire = getLivingEntity();
                if (currentVampire == null || currentVampire.isDead() || !npc.isSpawned() || wave >= 3) {
                    cancel();
                    return;
                }
                releaseTidesWave(currentVampire, hitTargets, wave);
                wave++;
            }
        };
        ownedTasks.add(tideTask);
        tideTask.runTaskTimer(plugin, 4L, 4L);

        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void releaseTidesWave(LivingEntity vampire, java.util.Set<UUID> hitTargets, int wave) {
        Location center = vampire.getLocation().clone().add(0.0D, 1.25D, 0.0D);
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        int bolts = 12 + (wave * 4);
        for (int index = 0; index < bolts; index++) {
            double angle = (Math.PI * 2.0D * index) / bolts + (wave * 0.25D);
            Vector direction = new Vector(Math.cos(angle), 0.02D + wave * 0.01D, Math.sin(angle)).normalize();
            traceTidesBolt(vampire, center, direction, hitTargets);
        }

        world.playSound(center, Sound.ENTITY_BLAZE_SHOOT, 0.45F, 0.55F + wave * 0.12F);
        world.playSound(center, Sound.BLOCK_LAVA_POP, 0.35F, 0.6F + wave * 0.08F);
    }

    private void traceTidesBolt(LivingEntity vampire, Location start, Vector direction, java.util.Set<UUID> hitTargets) {
        World world = start.getWorld();
        if (world == null) {
            return;
        }

        for (double distance = 0.4D; distance <= TIDES_OF_BLOOD_RANGE; distance += 0.4D) {
            Location point = start.clone().add(direction.clone().multiply(distance));
            world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.03D, 0.03D, 0.0D, BRIGHT_BLOOD_DUST);
            world.spawnParticle(Particle.SMOKE, point, 1, 0.015D, 0.015D, 0.015D, 0.005D);

            if (!point.getBlock().isPassable()) {
                break;
            }

            for (Entity nearby : world.getNearbyEntities(point, 0.45D, 0.65D, 0.45D)) {
                if (!(nearby instanceof LivingEntity victim)
                    || victim.equals(vampire)
                    || !isValidHemoplagueTarget(victim)
                    || !hitTargets.add(victim.getUniqueId())) {
                    continue;
                }
                double before = victim.getHealth();
                victim.damage(TIDES_OF_BLOOD_BOLT_DAMAGE, vampire);
                double actualDamage = Math.max(0.0D, before - Math.max(0.0D, victim.getHealth()));
                if (actualDamage > 0.0D) {
                    healVampire(actualDamage * 0.30D);
                }
                world.spawnParticle(Particle.DAMAGE_INDICATOR, victim.getLocation().add(0.0D, 1.0D, 0.0D), 6, 0.25D, 0.35D, 0.25D, 0.02D);
                world.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.45F, 0.55F);
                break;
            }
        }
    }

    private void castBloodShield() {
        if (shieldUsed || shielded) {
            state = VampireState.COMBAT;
            return;
        }
        shieldUsed = true;
        shielded = true;
        setCooldown(VampireAbility.BLOOD_SHIELD, BLOOD_SHIELD_COOLDOWN);
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0F, 0.65F);
            world.playSound(location, Sound.ITEM_TOTEM_USE, 0.55F, 0.65F);
            world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 1.0D, 0.0D), 32, 0.75D, 0.95D, 0.75D, 0.0D, BLOOD_DUST);
        }

        BukkitRunnable shieldTask = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (ticks >= BLOOD_SHIELD_DURATION || cleanedUp || deathSequenceStarted) {
                    shielded = false;
                    cancel();
                    return;
                }
                spawnShieldParticles(ticks);
                ticks++;
            }
        };
        ownedTasks.add(shieldTask);
        shieldTask.runTaskTimer(plugin, 0L, 1L);
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void spawnShieldParticles(int ticks) {
        Location center = getCurrentLocation();
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        for (int shell = 0; shell < 2; shell++) {
            double baseRadius = 0.95D + shell * 0.28D;
            for (int index = 0; index < 7; index++) {
                double angle = (ticks * (0.24D + shell * 0.08D)) + (index * ((Math.PI * 2.0D) / 7.0D));
                double height = 0.45D + (index % 3) * 0.42D + Math.sin((ticks * 0.11D) + index) * 0.08D;
                Location point = center.clone().add(Math.cos(angle) * baseRadius, height, Math.sin(angle) * baseRadius);
                world.spawnParticle(Particle.DUST, point, 1, 0.03D, 0.03D, 0.03D, 0.0D, BLOOD_DUST);
                world.spawnParticle(Particle.CRIT, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            }
        }
        if (ticks % 8 == 0) {
            world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.18F, 0.5F);
        }
    }

    private void castBatFormEscape() {
        Player player = ensureTarget(40.0D);
        if (player == null) {
            state = VampireState.COMBAT;
            return;
        }
        setCooldown(VampireAbility.BAT_FORM_ESCAPE, BAT_ESCAPE_COOLDOWN);
        Location start = getCurrentLocation();
        World world = start.getWorld();
        if (world == null) {
            state = VampireState.COMBAT;
            return;
        }

        setNpcInvisible(200);
        npc.getNavigator().cancelNavigation();
        escapeBat = (Bat) world.spawnEntity(start, EntityType.BAT);
        escapeBat.setCustomName(null);
        escapeBat.setCustomNameVisible(false);
        escapeBat.setAwake(true);
        escapeBat.setRemoveWhenFarAway(false);
        plugin.getNPCManager().registerBat(escapeBat);
        world.spawnParticle(Particle.PORTAL, start, 25, 0.5D, 0.5D, 0.5D, 0.2D);
        batEscapeTicks = 0;
        batEscapeSwoopTick = random.nextInt(41) + 80;
        batEscapeDescending = false;
        state = VampireState.BAT_FORM_ESCAPE;
        stateTicks = 0;
    }

    private void tickBatFormEscape() {
        if (escapeBat == null || !escapeBat.isValid()) {
            finishBatEscape(false);
            return;
        }

        batEscapeTicks++;
        Location batLocation = escapeBat.getLocation();
        World world = batLocation.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.PORTAL, batLocation, 4, 0.12D, 0.12D, 0.12D, 0.04D);
            if (batEscapeTicks % 10 == 0) {
                world.playSound(batLocation, Sound.ENTITY_PHANTOM_FLAP, 0.7F, 1.25F);
            }
        }

        if (batEscapeTicks <= 60) {
            ascendEscapeBat();
            return;
        }

        if (batEscapeTicks < batEscapeSwoopTick) {
            circleEscapeBat();
            return;
        }

        batEscapeDescending = true;
        Player player = ensureTarget(56.0D);
        if (player == null) {
            player = findNearestPlayer(batLocation, 56.0D);
            target = player;
        }
        if (player == null) {
            circleEscapeBat();
            if (batEscapeTicks > batEscapeSwoopTick + 80) {
                finishBatEscape(false);
            }
            return;
        }

        swoopEscapeBat(player);
        if (escapeBat.getLocation().distanceSquared(player.getLocation()) <= 25.0D) {
            LivingEntity vampire = getLivingEntity();
            player.damage(4.0D, vampire == null ? escapeBat : vampire);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, true, true));
            finishBatEscape(true);
        } else if (batEscapeTicks > batEscapeSwoopTick + 120) {
            finishBatEscape(false);
        }
    }

    private void ascendEscapeBat() {
        Location location = escapeBat.getLocation();
        Vector velocity = new Vector(
            Math.sin(batEscapeTicks * 0.22D) * 0.20D,
            0.34D,
            Math.cos(batEscapeTicks * 0.22D) * 0.20D
        );
        if (location.getY() >= spawnLocation.getY() + 20.0D) {
            velocity.setY(0.05D);
        }
        escapeBat.setVelocity(velocity);
    }

    private void circleEscapeBat() {
        Location center = target != null && target.isOnline() ? target.getLocation().clone().add(0.0D, 14.0D, 0.0D) : spawnLocation.clone().add(0.0D, 18.0D, 0.0D);
        Vector direction = center.toVector().subtract(escapeBat.getLocation().toVector());
        if (direction.lengthSquared() > 0.25D) {
            direction.normalize().multiply(0.22D);
        }
        Vector orbit = new Vector(Math.cos(batEscapeTicks * 0.18D) * 0.18D, 0.0D, Math.sin(batEscapeTicks * 0.18D) * 0.18D);
        escapeBat.setVelocity(direction.add(orbit));
    }

    private void swoopEscapeBat(Player player) {
        Vector direction = player.getEyeLocation().toVector().subtract(escapeBat.getLocation().toVector());
        if (direction.lengthSquared() < 0.01D) {
            return;
        }
        direction.normalize().multiply(0.72D);
        direction.setY(Math.max(-0.75D, direction.getY()));
        escapeBat.setVelocity(direction);
    }

    private void finishBatEscape(boolean hitPlayer) {
        Location returnLocation = escapeBat != null && escapeBat.isValid()
            ? escapeBat.getLocation().clone()
            : getCurrentLocation();
        removeEscapeBat();
        ensureNpcSpawned(returnLocation);
        npc.teleport(returnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        setNpcVisible();
        World world = returnLocation.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.LARGE_SMOKE, returnLocation, 35, 0.5D, 0.7D, 0.5D, 0.06D);
            world.spawnParticle(Particle.PORTAL, returnLocation, 25, 0.5D, 0.7D, 0.5D, 0.08D);
            world.playSound(returnLocation, hitPlayer ? Sound.ENTITY_WITHER_AMBIENT : Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, hitPlayer ? 0.7F : 1.0F);
        }
        state = VampireState.COMBAT;
        stateTicks = 0;
        batEscapeDescending = false;
    }

    private void tryApplyBleed(Player player) {
        long lastTick = bleedApplicationTicks.getOrDefault(player.getUniqueId(), -100L);
        if (lifeTicks - lastTick <= MELEE_BLEED_GRACE_TICKS) {
            return;
        }
        bleedApplicationTicks.put(player.getUniqueId(), lifeTicks);
        if (random.nextDouble() <= plugin.getConfigManager().getBleedChance()) {
            plugin.getBleedEffect().applyBleed(player);
        }
    }

    private void healVampire(double amount) {
        LivingEntity entity = getLivingEntity();
        if (entity == null || amount <= 0.0D) {
            return;
        }
        double max = getMaxHealth(entity);
        entity.setHealth(Math.min(max, entity.getHealth() + amount));
        Location location = entity.getLocation().add(0.0D, 1.5D, 0.0D);
        entity.getWorld().spawnParticle(Particle.HEART, location, 1, 0.2D, 0.2D, 0.2D, 0.0D);
    }

    private void tickMissingHealthRegeneration() {
        if (stateTicks % MISSING_HEALTH_REGEN_INTERVAL != 0) {
            return;
        }
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        double max = getMaxHealth(entity);
        if (max <= 0.0D) {
            return;
        }
        double current = Math.max(0.0D, entity.getHealth());
        double missingRatio = Math.max(0.0D, Math.min(1.0D, (max - current) / max));
        if (missingRatio <= 0.0D) {
            return;
        }
        healVampire(missingRatio * MAX_MISSING_HEALTH_REGEN_PER_PULSE);
    }

    private boolean isBelowHealthPercent(double percent) {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return false;
        }
        double max = getMaxHealth(entity);
        return max > 0.0D && entity.getHealth() <= max * percent;
    }

    private double getMaxHealth(LivingEntity entity) {
        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            return Objects.requireNonNull(entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue();
        }
        return plugin.getConfigManager().getVampireHealth();
    }

    private float getYawTowards(Location from, Location to) {
        Vector delta = to.toVector().subtract(from.toVector());
        return (float) Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
    }

    private void applyConfiguredHealth(LivingEntity entity) {
        if (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            Objects.requireNonNull(entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)).setBaseValue(plugin.getConfigManager().getVampireHealth());
        }
        entity.setHealth(Math.min(plugin.getConfigManager().getVampireHealth(), getMaxHealth(entity)));
    }

    private void setNavigationSpeed(float speed) {
        if (!npc.isSpawned()) {
            return;
        }
        npc.getNavigator().getDefaultParameters().speedModifier(speed);
    }

    private Player ensureTarget(double radius) {
        if (target == null || !target.isOnline() || target.isDead()) {
            target = findNearestPlayer(getCurrentLocation(), radius);
            return target;
        }
        Location current = getCurrentLocation();
        if (current.getWorld() != target.getWorld()) {
            target = findNearestPlayer(current, radius);
            return target;
        }
        if (target.getLocation().distanceSquared(current) > radius * radius) {
            target = findNearestPlayer(current, radius);
        }
        return target;
    }

    private LivingEntity getLivingEntity() {
        if (!npc.isSpawned()) {
            return null;
        }
        Entity entity = npc.getEntity();
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity;
        }
        return null;
    }

    private void ensureNpcSpawned(Location location) {
        if (!npc.isSpawned()) {
            npc.spawn(location);
        }
    }

    private void setNpcInvisible(int ticks) {
        visible = false;
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 1, true, false, false));
        entity.setSilent(true);
        entity.setCollidable(false);
    }

    private void setNpcVisible() {
        visible = true;
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        entity.removePotionEffect(PotionEffectType.INVISIBILITY);
        entity.setSilent(false);
        entity.setCollidable(true);
        entity.setRemoveWhenFarAway(false);
    }

    private void applyHiddenStateIfNeeded(LivingEntity entity) {
        if (visible) {
            setNpcVisible();
            return;
        }
        entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, true, false, false));
        entity.setSilent(true);
        entity.setCollidable(false);
        entity.setRemoveWhenFarAway(false);
    }

    private void removeDisguiseBat() {
        if (disguiseBat != null) {
            plugin.getNPCManager().unregisterBat(disguiseBat);
            if (disguiseBat.isValid()) {
                disguiseBat.remove();
            }
            disguiseBat = null;
        }
    }

    private void removeEscapeBat() {
        if (escapeBat != null) {
            plugin.getNPCManager().unregisterBat(escapeBat);
            if (escapeBat.isValid()) {
                escapeBat.remove();
            }
            escapeBat = null;
        }
    }

    private void updateLastKnownLocation() {
        Location current = null;
        if (state == VampireState.DISGUISED_BAT && disguiseBat != null && disguiseBat.isValid()) {
            current = disguiseBat.getLocation();
        } else if (state == VampireState.BAT_FORM_ESCAPE && escapeBat != null && escapeBat.isValid()) {
            current = escapeBat.getLocation();
        } else {
            LivingEntity entity = getLivingEntity();
            if (entity != null) {
                current = entity.getLocation();
            }
        }
        if (current != null) {
            lastKnownLocation = current.clone();
        }
    }

    private int randomStalkDuration() {
        int min = plugin.getConfigManager().getStalkTicksMin();
        int max = plugin.getConfigManager().getStalkTicksMax();
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private void spawnDeathParticles(World world, Location location) {
        for (int index = 0; index < 50; index++) {
            Location point = location.clone().add(randomDouble(-0.65D, 0.65D), randomDouble(0.2D, 1.9D), randomDouble(-0.65D, 0.65D));
            world.spawnParticle(Particle.DUST, point, 1, 0.08D, 0.08D, 0.08D, 0.0D, BRIGHT_BLOOD_DUST);
        }
        world.spawnParticle(Particle.DAMAGE_INDICATOR, location.clone().add(0.0D, 1.0D, 0.0D), 20, 0.7D, 0.7D, 0.7D, 0.02D);
    }

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.75D) {
            world.dropItemNaturally(location, new ItemStack(Material.REDSTONE, random.nextInt(4) + 2));
        }
        if (random.nextDouble() <= 0.55D) {
            world.dropItemNaturally(location, new ItemStack(Material.BONE, random.nextInt(4) + 1));
        }
        if (random.nextDouble() <= 0.60D) {
            world.dropItemNaturally(location, new ItemStack(Material.ROTTEN_FLESH, random.nextInt(3) + 1));
        }
        if (random.nextDouble() <= 0.45D) {
            world.dropItemNaturally(location, new ItemStack(Material.SPIDER_EYE, random.nextInt(2) + 1));
        }
        if (random.nextDouble() <= 0.30D) {
            world.dropItemNaturally(location, new ItemStack(Material.FERMENTED_SPIDER_EYE, 1));
        }
        if (random.nextDouble() <= 0.18D) {
            world.dropItemNaturally(location, new ItemStack(Material.GLASS_BOTTLE, random.nextInt(2) + 1));
        }
        if (random.nextDouble() <= 0.10D) {
            world.dropItemNaturally(location, new ItemStack(Material.PHANTOM_MEMBRANE, 1));
        }
        if (random.nextDouble() <= 0.14D) {
            world.dropItemNaturally(location, createTippedArrow(PotionType.HEALING, random.nextInt(3) + 2));
        }
        if (random.nextDouble() <= 0.12D) {
            world.dropItemNaturally(location, createTippedArrow(PotionType.HARMING, random.nextInt(2) + 1));
        }
        if (random.nextDouble() <= 0.18D) {
            world.dropItemNaturally(location, new ItemStack(Material.NETHER_WART, 1 + random.nextInt(2)));
        }

        if (random.nextDouble() <= 0.08D) {
            world.dropItemNaturally(location, createPotionReward(Material.SPLASH_POTION, PotionType.INVISIBILITY));
        }
        if (random.nextDouble() <= 0.08D) {
            world.dropItemNaturally(location, createPotionReward(Material.SPLASH_POTION, PotionType.REGENERATION));
        }
        if (random.nextDouble() <= 0.08D) {
            world.dropItemNaturally(location, createEnchantedBookReward());
        }
        if (random.nextDouble() <= 0.05D) {
            world.dropItemNaturally(location, createEnchantedWeaponReward());
        }
        if (random.nextDouble() <= 0.06D) {
            world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));
        }
        if (random.nextDouble() <= 0.06D) {
            world.dropItemNaturally(location, new ItemStack(Material.EXPERIENCE_BOTTLE, 1 + random.nextInt(2)));
        }
        if (random.nextDouble() <= 0.07D) {
            world.dropItemNaturally(location, new ItemStack(Material.ENDER_PEARL, 1));
        }
        if (random.nextDouble() <= 0.06D) {
            world.dropItemNaturally(location, new ItemStack(Material.GHAST_TEAR, 1));
        }
        if (random.nextDouble() <= 0.05D) {
            world.dropItemNaturally(location, new ItemStack(Material.CLOCK, 1));
        }
        if (random.nextDouble() <= 0.05D) {
            world.dropItemNaturally(location, new ItemStack(Material.DRAGON_BREATH, 1));
        }
        ExperienceOrb orb = world.spawn(location, ExperienceOrb.class);
        orb.setExperience(random.nextInt(16) + 20);
    }

    private ItemStack createTippedArrow(PotionType potionType, int amount) {
        ItemStack item = new ItemStack(Material.TIPPED_ARROW, Math.max(1, amount));
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(potionType);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPotionReward(Material material, PotionType potionType) {
        ItemStack item = new ItemStack(material, 1);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(potionType);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createEnchantedBookReward() {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK, 1);
        if (item.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            switch (random.nextInt(5)) {
                case 0 -> meta.addStoredEnchant(Enchantment.MENDING, 1, true);
                case 1 -> meta.addStoredEnchant(Enchantment.UNBREAKING, 3, true);
                case 2 -> meta.addStoredEnchant(Enchantment.SHARPNESS, 4, true);
                case 3 -> meta.addStoredEnchant(Enchantment.FIRE_ASPECT, 2, true);
                default -> meta.addStoredEnchant(Enchantment.LOOTING, 2, true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createEnchantedWeaponReward() {
        ItemStack item = new ItemStack(random.nextDouble() < 0.35D ? Material.DIAMOND_SWORD : Material.IRON_SWORD, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            item.setItemMeta(meta);
        }
        item.addUnsafeEnchantment(Enchantment.SHARPNESS, random.nextBoolean() ? 4 : 5);
        if (random.nextDouble() <= 0.70D) {
            item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
        }
        if (random.nextDouble() <= 0.50D) {
            item.addUnsafeEnchantment(Enchantment.LOOTING, 2);
        }
        if (random.nextDouble() <= 0.40D) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        }
        return item;
    }

    private void cancelControllerOnly() {
        if (controllerTask != null) {
            controllerTask.cancel();
            controllerTask = null;
        }
    }

    private void cancelOwnedTasks() {
        for (BukkitRunnable task : List.copyOf(ownedTasks)) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Bukkit throws if a task was never scheduled; defensive cleanup keeps disable robust.
            }
        }
        ownedTasks.clear();
    }

    private record AbilityWeight(VampireAbility ability, int weight) {
    }

    /**
     * Snapshot of combat state useful to command/status integrations.
     *
     * @return immutable snapshot
     */
    public CombatSnapshot snapshot() {
        Location location = getCurrentLocation();
        return new CombatSnapshot(
            npc.getId(),
            state,
            target == null ? null : target.getUniqueId(),
            shielded,
            batSwarm.size(),
            location == null ? null : location.clone(),
            lifeTicks
        );
    }

    public record CombatSnapshot(
        int npcId,
        VampireState state,
        UUID target,
        boolean shielded,
        int summonedBats,
        Location location,
        long ageTicks
    ) {
    }

    /**
     * Returns the target as an optional for consumers that need nullable safety.
     *
     * @return optional target
     */
    public Optional<Player> target() {
        return Optional.ofNullable(target).filter(Player::isOnline).filter(player -> !player.isDead());
    }

    /**
     * Forces this vampire into combat with a player.
     *
     * @param player forced target
     */
    public void forceCombat(Player player) {
        if (player == null || player.isDead()) {
            return;
        }
        removeDisguiseBat();
        ensureNpcSpawned(player.getLocation());
        setNpcVisible();
        transitionToCombat(player);
    }

    /**
     * Returns whether this vampire currently owns a bat entity.
     *
     * @param uuid entity UUID
     * @return true if owned
     */
    public boolean ownsBat(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        if (disguiseBat != null && uuid.equals(disguiseBat.getUniqueId())) {
            return true;
        }
        return escapeBat != null && uuid.equals(escapeBat.getUniqueId());
    }

    /**
     * Returns a compact status line for admin diagnostics.
     *
     * @return status text
     */
    public String statusLine() {
        Location location = getCurrentLocation();
        String worldName = location != null && location.getWorld() != null ? location.getWorld().getName() : "unknown";
        String targetName = target != null && target.isOnline() ? target.getName() : "none";
        return "NPC " + npc.getId()
            + " state=" + state
            + " world=" + worldName
            + " target=" + targetName
            + " shield=" + shielded
            + " bats=" + batSwarm.size();
    }

    /**
     * Makes the vampire drop aggro without despawning.
     */
    public void calm() {
        target = null;
        if (npc.isSpawned()) {
            npc.getNavigator().cancelNavigation();
        }
        if (state == VampireState.COMBAT) {
            state = VampireState.STALKING;
            stateTicks = 0;
        }
    }

    /**
     * Returns whether this NPC is active in combat.
     *
     * @return true when combat-capable and visible
     */
    public boolean isCombatReady() {
        return visible && !isDead() && (state == VampireState.COMBAT || state == VampireState.CASTING);
    }

    /**
     * Cancels all nonessential transient effects.
     */
    public void cancelEffectsOnly() {
        shielded = false;
        removeEscapeBat();
        batSwarm.cleanup();
        cancelOwnedTasks();
    }

    /**
     * Returns the number of ticks left on an ability cooldown.
     *
     * @param ability ability
     * @return remaining ticks
     */
    public int getCooldownTicks(VampireAbility ability) {
        return cooldowns.getOrDefault(ability, 0);
    }

    /**
     * Returns whether the escape phase has begun descending.
     *
     * @return true if swooping down
     */
    public boolean isBatEscapeDescending() {
        return batEscapeDescending;
    }

    /**
     * Teleports the vampire safely to a target location.
     *
     * @param location destination
     */
    public void teleport(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        removeDisguiseBat();
        removeEscapeBat();
        ensureNpcSpawned(location);
        npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        setNpcVisible();
        lastKnownLocation = location.clone();
        if (state == VampireState.DISGUISED_BAT) {
            state = VampireState.STALKING;
            stateTicks = 0;
        }
    }

    /**
     * Returns the vampire's configured maximum summoned bat count.
     *
     * @return max bats
     */
    public int getMaxSummonedBats() {
        return MAX_SUMMONED_BATS;
    }

    /**
     * Returns current summoned bat count.
     *
     * @return count
     */
    public int getSummonedBatCount() {
        return batSwarm.size();
    }

    /**
     * Returns whether the NPC is still disguised as a bat.
     *
     * @return true if disguised
     */
    public boolean isDisguised() {
        return state == VampireState.DISGUISED_BAT;
    }

    /**
     * Returns whether the Citizens entity is currently visible.
     *
     * @return visibility state
     */
    public boolean isVisible() {
        return visible;
    }

    /**
     * Gets the age of this vampire in ticks.
     *
     * @return age in ticks
     */
    public long getLifeTicks() {
        return lifeTicks;
    }

    /**
     * Gets the current state's age in ticks.
     *
     * @return state ticks
     */
    public int getStateTicks() {
        return stateTicks;
    }

    /**
     * Marks the NPC visible without playing transform effects.
     */
    public void revealSilently() {
        removeDisguiseBat();
        setNpcVisible();
        state = VampireState.STALKING;
        stateTicks = 0;
    }

    /**
     * Returns the spawn location.
     *
     * @return spawn location
     */
    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    /**
     * Returns whether the vampire is controlled by this plugin and still registered.
     *
     * @return true if registered
     */
    public boolean isRegistered() {
        return plugin.getNPCManager().getVampire(npc.getId()) == this;
    }

    /**
     * Nudges the vampire toward its current target when direct navigation stalls.
     */
    public void nudgeTowardTarget() {
        LivingEntity entity = getLivingEntity();
        Player player = target;
        if (entity == null || player == null || !player.isOnline()) {
            return;
        }
        Vector direction = player.getLocation().toVector().subtract(entity.getLocation().toVector());
        if (direction.lengthSquared() < 0.01D) {
            return;
        }
        entity.setVelocity(direction.normalize().multiply(0.18D));
    }

    /**
     * Emits an idle blood particle pulse.
     */
    public void pulse() {
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.DUST, location.clone().add(0.0D, 1.0D, 0.0D), 6, 0.35D, 0.45D, 0.35D, 0.0D, BLOOD_DUST);
        }
    }

    /**
     * Returns a readable target name for command output.
     *
     * @return target name
     */
    public String getTargetName() {
        return target == null || !target.isOnline() ? "none" : target.getName();
    }

    /**
     * Returns a readable world name for command output.
     *
     * @return world name
     */
    public String getWorldName() {
        Location location = getCurrentLocation();
        return location.getWorld() == null ? "unknown" : location.getWorld().getName();
    }

    /**
     * Damages the current target if they are inside melee range.
     *
     * @return true if damage landed
     */
    public boolean attemptManualMelee() {
        Player player = ensureTarget(4.0D);
        LivingEntity entity = getLivingEntity();
        if (player == null || entity == null) {
            return false;
        }
        if (player.getLocation().distanceSquared(entity.getLocation()) > 9.0D) {
            return false;
        }
        player.damage(4.0D, entity);
        onMeleeHit(player);
        return true;
    }

    /**
     * Returns whether this vampire can currently cast abilities.
     *
     * @return true if casting is allowed
     */
    public boolean canCast() {
        return state == VampireState.COMBAT && !cleanedUp && !deathSequenceStarted;
    }

    /**
     * Attempts to cast a specific ability immediately.
     *
     * @param ability ability to cast
     * @return true if casting started
     */
    public boolean castNow(VampireAbility ability) {
        if (!canCast() || !canUseAbility(ability)) {
            return false;
        }
        startCasting(ability);
        return true;
    }

    /**
     * Clears combat cooldowns, intended for controlled admin testing.
     */
    public void clearCooldowns() {
        cooldowns.clear();
    }

    /**
     * Returns the current target distance or -1 when no target exists.
     *
     * @return distance in blocks
     */
    public double getTargetDistance() {
        Player player = target;
        Location location = getCurrentLocation();
        if (player == null || !player.isOnline() || location.getWorld() != player.getWorld()) {
            return -1.0D;
        }
        return Math.sqrt(player.getLocation().distanceSquared(location));
    }

    /**
     * Recalculates and applies Sentinel combat properties from config.
     */
    public void refreshSentinelSettings() {
        configureSentinel();
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
        }
    }

    /**
     * Gets the active blood shield remaining estimate.
     *
     * @return remaining ticks, or zero when not shielded
     */
    public int getShieldRemainingEstimate() {
        if (!shielded) {
            return 0;
        }
        return Math.max(0, BLOOD_SHIELD_DURATION - stateTicks);
    }

    /**
     * Returns the pending ability during casting.
     *
     * @return pending ability or null
     */
    public VampireAbility getPendingAbility() {
        return pendingAbility;
    }
}







