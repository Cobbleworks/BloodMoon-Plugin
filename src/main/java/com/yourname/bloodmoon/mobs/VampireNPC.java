package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.effects.BloodMagicProjectile;
import com.yourname.bloodmoon.traits.VampireTrait;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Base64;
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
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Equipment.EquipmentSlot;
import org.bukkit.Color;
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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
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
        BAT_FORM_ESCAPE(8),
        SUMMON_BATS(14),
        SHADOW_DASH(16),
        FEAR_SHRIEK(8),
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
    private static final int FEAR_SHRIEK_COOLDOWN = 500;
    private static final int BAT_ESCAPE_COOLDOWN = 360;
    private static final int BLOOD_SHIELD_DURATION = 100;
    private static final int BLOOD_SHIELD_COOLDOWN = 1200;
    private static final int BLOOD_MAGIC_COOLDOWN = 80;
    private static final int DRAIN_LIFE_COOLDOWN = 120;
    private static final int SUMMON_BATS_COOLDOWN = 180;
    private static final int DEATH_REMOVE_DELAY = 60;
    private static final int MAX_SUMMONED_BATS = 6;
    private static final double MELEE_BLEED_GRACE_TICKS = 10.0D;
    private static final Particle.DustOptions BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(140, 0, 0), 1.2F);
    private static final Particle.DustOptions DARK_BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(80, 0, 0), 1.0F);
    private static final Particle.DustOptions BRIGHT_BLOOD_DUST = new Particle.DustOptions(Color.fromRGB(190, 0, 0), 1.45F);

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random;
    private final Map<VampireAbility, Integer> cooldowns;
    private final Map<UUID, Long> bleedApplicationTicks;
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
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        if (state == VampireState.DISGUISED_BAT || state == VampireState.STALKING || state == VampireState.CASTING || state == VampireState.BAT_FORM_ESCAPE || state == VampireState.DEAD) {
            event.setCancelled(true);
            if (state == VampireState.STALKING) {
                maintainStalkingTarget(player);
            }
            return;
        }
        target = player;
        tryApplyBleed(player);
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
        cancelOwnedTasks();
        removeDisguiseBat();
        removeEscapeBat();
        batSwarm.cleanup();
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
        npc.setProtected(false);
        configureVampireTrait();
        configureSkin();
        configureEquipment();
        configureSentinel();
        spawnHiddenNpc();
    }

    private void configureVampireTrait() {
        VampireTrait trait = npc.getOrAddTrait(VampireTrait.class);
        trait.bind(this);
    }

    private void configureSkin() {
        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = npc.getOrAddTrait(skinTraitClass);
            Method setFetchDefaultSkin = skinTraitClass.getMethod("setFetchDefaultSkin", boolean.class);
            Method setSkinPersistent = skinTraitClass.getMethod("setSkinPersistent", String.class, String.class, String.class);
            setFetchDefaultSkin.invoke(skinTrait, false);
            setSkinPersistent.invoke(skinTrait, "bloodmoon_vampire", "", createUnsignedTexture(plugin.getConfigManager().getVampireSkinUrl()));
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply Citizens SkinTrait to vampire NPC " + npc.getId() + ": " + ex.getMessage());
        }
    }

    private String createUnsignedTexture(String skinUrl) {
        String safeUrl = skinUrl == null || skinUrl.isBlank()
            ? "https://s.namemc.com/i/a15d2c945b798f66.png"
            : skinUrl;
        String payload = "{\"timestamp\":" + System.currentTimeMillis()
            + ",\"profileId\":\"00000000000000000000000000000000\","
            + "\"profileName\":\"BloodMoonVampire\","
            + "\"textures\":{\"SKIN\":{\"url\":\"" + safeUrl + "\"}}}";
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private void configureEquipment() {
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(EquipmentSlot.HAND, new ItemStack(Material.IRON_SWORD));
        equipment.set(EquipmentSlot.HELMET, new ItemStack(Material.LEATHER_HELMET));
        equipment.set(EquipmentSlot.CHESTPLATE, new ItemStack(Material.LEATHER_CHESTPLATE));
    }

    private void configureSentinel() {
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.setHealth(plugin.getConfigManager().getVampireHealth());
        sentinel.health = plugin.getConfigManager().getVampireHealth();
        sentinel.damage = 6.0D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange = 30.0D;
        sentinel.armor = 0.2D;
        sentinel.allTargets = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
    }

    private void spawnHiddenNpc() {
        Location hiddenLocation = spawnLocation.clone();
        if (!npc.isSpawned()) {
            npc.spawn(hiddenLocation);
        }
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
            applyHiddenStateIfNeeded(entity);
        }
    }

    private void spawnDisguiseBat() {
        World world = spawnLocation.getWorld();
        if (world == null) {
            return;
        }
        disguiseBat = (Bat) world.spawnEntity(spawnLocation, EntityType.BAT);
        disguiseBat.setCustomName("§4Vampire");
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
            applyStalkingGlow(player);
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

    private void applyStalkingGlow(Player player) {
        Location center = getCurrentLocation();
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        for (Player nearby : world.getPlayers()) {
            if (nearby.getLocation().distanceSquared(center) <= 14.0D * 14.0D) {
                nearby.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, true, false, true));
            }
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, true, false, true));
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
        castingDurationTicks = random.nextInt(11) + 20;
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
        if (stateTicks < castingDurationTicks) {
            return;
        }
        VampireAbility ability = pendingAbility;
        pendingAbility = null;
        executeAbility(ability);
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
        double angle = stateTicks * 0.45D;
        for (int step = 0; step < 3; step++) {
            double offset = angle + (step * ((Math.PI * 2.0D) / 3.0D));
            Location point = base.clone().add(Math.cos(offset) * 0.75D, 0.35D + (stateTicks % 20) * 0.045D, Math.sin(offset) * 0.75D);
            world.spawnParticle(Particle.WITCH, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }
    }

    private void executeAbility(VampireAbility ability) {
        if (ability == null) {
            return;
        }
        switch (ability) {
            case BLOOD_MAGIC -> castBloodMagic();
            case DRAIN_LIFE -> castDrainLife();
            case BAT_FORM_ESCAPE -> castBatFormEscape();
            case SUMMON_BATS -> castSummonBats();
            case SHADOW_DASH -> castShadowDash();
            case FEAR_SHRIEK -> castFearShriek();
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
            case SUMMON_BATS -> batSwarm.size() < MAX_SUMMONED_BATS;
            case SHADOW_DASH -> target != null && target.isOnline() && !target.isDead();
            case FEAR_SHRIEK -> true;
            case BAT_FORM_ESCAPE -> target != null && target.isOnline() && !target.isDead();
            case BLOOD_MAGIC, DRAIN_LIFE -> target != null && target.isOnline() && !target.isDead();
        };
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
        }
        for (int index = 0; index < 3; index++) {
            BloodMagicProjectile projectile = new BloodMagicProjectile(plugin, caster, player, start.clone().add(0.0D, index * 0.12D, 0.0D), index);
            projectile.launch();
        }
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void castDrainLife() {
        Player player = ensureTarget(8.0D);
        LivingEntity vampire = getLivingEntity();
        if (player == null || vampire == null || !vampire.hasLineOfSight(player)) {
            state = VampireState.COMBAT;
            return;
        }

        setCooldown(VampireAbility.DRAIN_LIFE, DRAIN_LIFE_COOLDOWN);
        Location location = vampire.getLocation();
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, Sound.ENTITY_GUARDIAN_ATTACK, 1.0F, 1.5F);
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 50, 0, true, true, true));

        BukkitRunnable drainTask = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                if (ticks > 40 || player.isDead() || !player.isOnline() || !npc.isSpawned()) {
                    cancel();
                    return;
                }

                LivingEntity currentVampire = getLivingEntity();
                if (currentVampire == null || currentVampire.isDead()) {
                    cancel();
                    return;
                }

                if (ticks % 2 == 0) {
                    drawDrainBeam(currentVampire, player);
                }

                if (ticks % 5 == 0) {
                    player.damage(0.5D, currentVampire);
                    healVampire(0.5D);
                    Location vLoc = currentVampire.getLocation().add(0.0D, 1.1D, 0.0D);
                    currentVampire.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, vLoc, 6, 0.35D, 0.35D, 0.35D, 0.02D);
                    currentVampire.getWorld().spawnParticle(Particle.DUST, vLoc, 8, 0.35D, 0.35D, 0.35D, 0.0D, BRIGHT_BLOOD_DUST);
                }
                ticks++;
            }
        };
        ownedTasks.add(drainTask);
        drainTask.runTaskTimer(plugin, 0L, 1L);
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void drawDrainBeam(LivingEntity vampire, Player player) {
        Location start = vampire.getEyeLocation();
        Location end = player.getEyeLocation();
        World world = start.getWorld();
        if (world == null) {
            return;
        }
        Vector vector = end.toVector().subtract(start.toVector());
        double length = vector.length();
        if (length <= 0.1D) {
            return;
        }
        Vector step = vector.normalize().multiply(0.35D);
        Location cursor = start.clone();
        for (double traveled = 0.0D; traveled < length; traveled += 0.35D) {
            world.spawnParticle(Particle.CRIT, cursor, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            cursor.add(step);
        }
    }

    private void castSummonBats() {
        if (batSwarm.size() >= MAX_SUMMONED_BATS) {
            state = VampireState.COMBAT;
            return;
        }

        setCooldown(VampireAbility.SUMMON_BATS, SUMMON_BATS_COOLDOWN);
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.PORTAL, location, 30, 0.9D, 0.8D, 0.9D, 0.18D);
            world.spawnParticle(Particle.SMOKE, location, 30, 0.7D, 0.7D, 0.7D, 0.04D);
            world.playSound(location, Sound.ENTITY_BAT_LOOP, 0.8F, 0.7F);
            world.playSound(location, Sound.ENTITY_BAT_LOOP, 0.8F, 0.9F);
            world.playSound(location, Sound.ENTITY_BAT_LOOP, 0.8F, 1.1F);
        }
        batSwarm.spawn(location, 3);
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
            world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9F, 0.55F);
        }
        ensureNpcSpawned(destination);
        npc.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        setNpcVisible();
        if (destination.getWorld() != null) {
            destination.getWorld().spawnParticle(Particle.SMOKE, destination, 35, 0.5D, 0.6D, 0.5D, 0.08D);
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

    private void castFearShriek() {
        setCooldown(VampireAbility.FEAR_SHRIEK, FEAR_SHRIEK_COOLDOWN);
        Location location = getCurrentLocation();
        World world = location.getWorld();
        if (world == null) {
            state = VampireState.COMBAT;
            return;
        }
        world.playSound(location, Sound.ENTITY_WITHER_AMBIENT, 1.0F, 0.4F);
        spawnShriekRing(world, location);
        double radiusSquared = 10.0D * 10.0D;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) > radiusSquared) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1, true, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, true, true));
            Vector knockback = player.getLocation().toVector().subtract(location.toVector());
            if (knockback.lengthSquared() < 0.01D) {
                knockback = new Vector(randomDouble(-1.0D, 1.0D), 0.0D, randomDouble(-1.0D, 1.0D));
            }
            knockback.normalize().multiply(0.8D);
            knockback.setY(0.35D);
            player.setVelocity(knockback);
        }
        state = VampireState.COMBAT;
        stateTicks = 0;
    }

    private void spawnShriekRing(World world, Location center) {
        for (int index = 0; index < 48; index++) {
            double angle = (Math.PI * 2.0D * index) / 48.0D;
            Location point = center.clone().add(Math.cos(angle) * 2.5D, 0.25D, Math.sin(angle) * 2.5D);
            world.spawnParticle(Particle.EXPLOSION, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
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
        for (int index = 0; index < 4; index++) {
            double angle = (ticks * 0.28D) + (index * Math.PI / 2.0D);
            Location point = center.clone().add(Math.cos(angle) * 1.0D, 0.8D + Math.sin(ticks * 0.10D) * 0.25D, Math.sin(angle) * 1.0D);
            world.spawnParticle(Particle.WITCH, point, 1, 0.02D, 0.02D, 0.02D, 0.0D);
            world.spawnParticle(Particle.DUST, point, 1, 0.02D, 0.02D, 0.02D, 0.0D, BRIGHT_BLOOD_DUST);
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
        escapeBat.setCustomName("§4Vampire");
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
        if (entity == null) {
            return;
        }
        double max = getMaxHealth(entity);
        entity.setHealth(Math.min(max, entity.getHealth() + amount));
        Location location = entity.getLocation().add(0.0D, 1.5D, 0.0D);
        entity.getWorld().spawnParticle(Particle.HEART, location, 1, 0.2D, 0.2D, 0.2D, 0.0D);
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
        if (random.nextDouble() <= 0.60D) {
            world.dropItemNaturally(location, new ItemStack(Material.REDSTONE, random.nextInt(4) + 2));
            world.dropItemNaturally(location, new ItemStack(Material.BONE, random.nextInt(3) + 1));
            world.dropItemNaturally(location, new ItemStack(Material.ROTTEN_FLESH, random.nextInt(2) + 1));
        }
        if (random.nextDouble() <= 0.35D) {
            ItemStack medium = switch (random.nextInt(3)) {
                case 0 -> new ItemStack(Material.FERMENTED_SPIDER_EYE, 1);
                case 1 -> new ItemStack(Material.PHANTOM_MEMBRANE, 1);
                default -> createVampireFang();
            };
            world.dropItemNaturally(location, medium);
        }
        if (random.nextDouble() <= 0.15D) {
            world.dropItemNaturally(location, createBloodVial());
        }
        if (random.nextDouble() <= 0.05D) {
            world.dropItemNaturally(location, createVampiricHeart());
        }
        ExperienceOrb orb = world.spawn(location, ExperienceOrb.class);
        orb.setExperience(random.nextInt(41) + 40);
    }

    private ItemStack createVampireFang() {
        ItemStack item = new ItemStack(Material.BONE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§cVampire Fang");
            item.setItemMeta(meta);
        }
        item.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);
        return item;
    }

    private ItemStack createBloodVial() {
        ItemStack item = new ItemStack(Material.GLASS_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§4Blood Vial");
            meta.setLore(List.of("§7A vial of ancient vampire blood", "§7Used in dark rituals"));
            item.setItemMeta(meta);
        }
        item.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 1);
        return item;
    }

    private ItemStack createVampiricHeart() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§5Vampiric Heart");
            meta.setLore(List.of("§5Pulses with dark energy", "§7The source of a vampire's power"));
            item.setItemMeta(meta);
        }
        item.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 1);
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
