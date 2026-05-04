package com.cobbleworks.bloodmoon.mobs;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.traits.ZombieTrait;
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
import org.bukkit.attribute.AttributeInstance;
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
        CHARGE_LEAP(16),
        SKULL_BARRAGE(20),
        ZOMBIE_HORDE(14),
        NECROTIC_GRASP(18),
        TOXIC_BURST(16);

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
    private static final int RAGE_TICKS               = 20;
    private static final int DEATH_SHAKE_TICKS        = 30;
    private static final int DEATH_REMOVE_DELAY       = 220;

    private static final int ACID_SPIT_COOLDOWN    = 180;
    private static final int ROT_ZONE_COOLDOWN     = 320;
    private static final int POWER_LEAP_COOLDOWN   = 220;
    private static final int CHARGE_LEAP_COOLDOWN  = 260;
    private static final int SKULL_BARRAGE_COOLDOWN = 200;
    private static final int ZOMBIE_HORDE_COOLDOWN  = 360;
    private static final int NECROTIC_GRASP_COOLDOWN = 240;
    private static final int TOXIC_BURST_COOLDOWN   = 280;

    // BERSERKER phase threshold (35% HP)
    private static final double BERSERKER_HP_FRACTION = 0.35D;

    private static final int MELEE_COOLDOWN = 20;

    // Passive timings (in global ticks)
    private static final int TRAIL_SAMPLE_INTERVAL  = 2;
    private static final int TRAIL_PARTICLE_INTERVAL = 3;
    private static final int TRAIL_DAMAGE_INTERVAL  = 10;
    private static final int TRAIL_DURATION_TICKS   = 100; // 5 s

    private static final int HUNGER_DRAIN_INTERVAL = 30;
    private static final double HUNGER_DRAIN_RADIUS = 12.0D;
    private static final int ROT_AURA_INTERVAL = 20;
    private static final double ROT_AURA_RADIUS_SQUARED = 2.25D;

    private static final int CROP_CHECK_INTERVAL = 6;
    private static final int CROP_CHECK_RADIUS   = 1;

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
    // Particle dust colour palettes
    // -------------------------------------------------------------------------

    private static final Particle.DustOptions ACID_DUST        = new Particle.DustOptions(Color.fromRGB( 50, 220,  50), 1.1F);
    private static final Particle.DustOptions ROT_DUST         = new Particle.DustOptions(Color.fromRGB( 36, 170,  36), 1.2F);
    private static final Particle.DustOptions BRIGHT_GREEN_DUST = new Particle.DustOptions(Color.fromRGB( 90, 210,  90), 1.15F);
    private static final Particle.DustOptions CHARGE_DUST      = new Particle.DustOptions(Color.fromRGB( 35, 190,  35), 1.25F);
    private static final Particle.DustOptions DARK_ROT_DUST    = new Particle.DustOptions(Color.fromRGB( 30, 130,  30), 1.0F);
    private static final Particle.DustOptions WITHER_DUST      = new Particle.DustOptions(Color.fromRGB( 20,  20,  20), 1.1F);
    private static final Particle.DustOptions BERSERKER_DUST   = new Particle.DustOptions(Color.fromRGB(200,  40,  10), 1.3F);
    private static final Particle.DustOptions TOXIC_DUST       = new Particle.DustOptions(Color.fromRGB( 60, 230,  60), 1.2F);

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
    private int cropHuntTicks;

    private boolean cleanedUp;
    private boolean deathSequenceStarted;
    private boolean combatInitialized;
    private boolean leaping;
    private boolean berserkerActive;

    private final List<org.bukkit.entity.Zombie> hordeZombies = new ArrayList<>();

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
        event.setCancelled(true);
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        if (state == ZombieState.DEAD) {
            return;
        }
        target = player;
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
        hitPlayer.damage(4.0D);

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

    /**
     * Called from PlayerListener when a skull-barrage Snowball hits a player.
     * Deals damage and applies Wither I briefly.
     */
    public void handleSkullHit(Player hitPlayer) {
        if (hitPlayer == null || hitPlayer.isDead()) {
            return;
        }
        hitPlayer.damage(3.5D);
        hitPlayer.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 40, 0, false, true, true));
        World world = hitPlayer.getWorld();
        Location loc = hitPlayer.getLocation().add(0, 1, 0);
        world.spawnParticle(Particle.DUST, loc, 10, 0.3D, 0.4D, 0.3D, 0D, WITHER_DUST);
        world.playSound(hitPlayer.getLocation(), Sound.ENTITY_SKELETON_HURT, 0.8F, 0.8F);
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
        // Remove horde zombies spawned by ZOMBIE_HORDE ability
        for (org.bukkit.entity.Zombie hz : hordeZombies) {
            if (hz != null && hz.isValid()) {
                hz.remove();
            }
        }
        hordeZombies.clear();
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            plugin.getOverheadHealthBarManager().removeBar(entity.getUniqueId());
        }
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
        sentinel.damage      = 0.0D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange  = 60.0D;
        sentinel.armor       = 0.15D;
        sentinel.protectFromIgnores = false;
        sentinel.allTargets  = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
        sentinel.addIgnore("npcs");
        npc.setProtected(false);
    }

    private void spawnNpc() {
        if (!npc.isSpawned()) {
            npc.spawn(spawnLocation.clone());
        }
        npc.getNavigator().getDefaultParameters().speedModifier(1.1F).stationaryTicks(-1).avoidWater(false);
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
        if (globalTick % ROT_AURA_INTERVAL == 0) {
            tickRotAura();
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
        World world = entity.getWorld();
        Location loc = entity.getLocation();

        if (stateTicks == 1) {
            world.playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 0.6F);
            world.playSound(loc, Sound.ENTITY_WARDEN_ROAR,          0.45F, 1.7F);
            // Opening eruption of infected green energy
            world.spawnParticle(Particle.DUST,
                loc.clone().add(0D, 1.0D, 0D), 30, 0.65D, 0.65D, 0.65D, 0D, ROT_DUST);
            world.spawnParticle(Particle.SMOKE,
                loc.clone().add(0D, 0.5D, 0D), 12, 0.4D, 0.3D, 0.4D, 0.02D);
        }

        // Escalating spiral ring – grows denser and taller as rage peaks
        if (stateTicks % 4 == 0) {
            double progress = (double) stateTicks / RAGE_TICKS;
            int count = 4 + (int) (progress * 8);
            double angle = stateTicks * 0.38D;
            for (int i = 0; i < count; i++) {
                double offset = angle + (i * (Math.PI * 2.0D / count));
                double radius = 0.55D + progress * 0.35D;
                Location ring = loc.clone().add(
                    Math.cos(offset) * radius,
                    0.4D + progress * 0.8D,
                    Math.sin(offset) * radius);
                world.spawnParticle(Particle.DUST, ring, 1, 0.03D, 0.03D, 0.03D, 0D,
                    new Particle.DustOptions(Color.fromRGB(
                        (int) (60 + progress * 30),
                        (int) (160 - progress * 30),
                        60), 1.0F + (float) (progress * 0.25F)));
            }
            world.spawnParticle(Particle.SMOKE,
                loc.clone().add(0D, 0.3D, 0D), 2, 0.35D, 0.15D, 0.35D, 0.015D);
        }

        // Periodic ambient growls that quicken as rage nears its peak
        if (stateTicks % 10 == 0) {
            world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,
                0.8F, 0.50F + (stateTicks / (float) RAGE_TICKS) * 0.25F);
        }

        if (stateTicks >= RAGE_TICKS) {
            state = ZombieState.COMBAT;
            stateTicks = 0;
            setNavigationSpeed(0.92F);
            // Combat-entry eruption: the rage snaps and the zombie surges forward
            world.playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_HURT, 1.0F, 0.55F);
            world.playSound(loc, Sound.ENTITY_WARDEN_ROAR,          0.35F, 1.85F);
            world.spawnParticle(Particle.DUST,
                loc.clone().add(0D, 1.0D, 0D), 32, 0.6D, 0.65D, 0.6D, 0D, CHARGE_DUST);
            world.spawnParticle(Particle.BLOCK,
                loc.clone().add(0D, 0.1D, 0D), 14, 0.45D, 0.1D, 0.45D, 0.06D,
                Material.GRASS_BLOCK.createBlockData());
            return;
        }

        Player rageTarget = ensureTarget(40.0D);
        if (rageTarget != null) {
            setNavigationSpeed(0.90F);
            npc.getNavigator().setTarget(rageTarget, true);
            if (stateTicks % 12 == 0) {
                lockSentinelChase(rageTarget, 42.0D);
            }
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
            setNavigationSpeed(0.82F);
            return;
        }

        target = player;
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }

        setNavigationSpeed(1.08F);
        if (leaping) {
            return; // paused during charge leap arc
        }
        npc.getNavigator().setTarget(player, true);
        if (stateTicks % 8 == 0) {
            lockSentinelChase(player, 48.0D);
        }
        npc.faceLocation(player.getEyeLocation());

        if (stateTicks % COMBAT_AMBIENT_INTERVAL == 0) {
            getCurrentLocation().getWorld().playSound(
                getCurrentLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.9F, 0.65F);
        }
        if (stateTicks % 80 == 0) {
            getCurrentLocation().getWorld().playSound(
                getCurrentLocation(), Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.8F, 0.55F);
        }

        // BERSERKER phase — triggers once at ≤35% health
        if (!berserkerActive) {
            LivingEntity checkEntity = getLivingEntity();
            if (checkEntity != null) {
                AttributeInstance maxHpAttr = checkEntity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                double maxHp = maxHpAttr != null ? maxHpAttr.getValue() : plugin.getConfigManager().getZombieHealth();
                if (checkEntity.getHealth() / maxHp <= BERSERKER_HP_FRACTION) {
                    berserkerActive = true;
                    triggerBerserkerPhase(checkEntity);
                }
            }
        }

        // Berserker visual pulse
        if (berserkerActive && stateTicks % 3 == 0) {
            LivingEntity ent = getLivingEntity();
            if (ent != null) {
                ent.getWorld().spawnParticle(Particle.DUST, ent.getLocation().clone().add(0D, 1.0D, 0D),
                    3, 0.35D, 0.4D, 0.35D, 0D, BERSERKER_DUST);
            }
        }

        double distanceSquared = entity.getLocation().distanceSquared(player.getLocation());
        if (distanceSquared <= 4.0D
            && state != ZombieState.CASTING
            && stateTicks % 10 == 0
            && canUseAbility(ZombieAbility.POWER_LEAP)) {
            startCasting(ZombieAbility.POWER_LEAP);
            return;
        }

        int abilityInterval = Math.max(12, (int) Math.round((COMBAT_ABILITY_INTERVAL - 8) * plugin.getBloodMoonManager().getAbilityCadenceMultiplier()));
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
        // Stand still during the cast — navigator drifting mid-cast causes floating
        npc.getNavigator().cancelNavigation();
        if (target != null && target.isOnline() && !target.isDead()) {
            npc.faceLocation(target.getEyeLocation());
        }

        runCastingParticles();
        updateCastingAnimation();

        if (stateTicks < castingTicks) {
            return;
        }
        ZombieAbility ability = pendingAbility;
        pendingAbility = null;
        resetCastingAnimation();
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
        castingTicks = switch (ability) {
            case CHARGE_LEAP  -> 25;
            case ROT_ZONE     -> 16;
            case ZOMBIE_HORDE -> 20;
            case TOXIC_BURST  -> 18;
            default           -> 10;
        };
        npc.getNavigator().cancelNavigation();

        Location loc = getCurrentLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }

        switch (ability) {
            case ACID_SPIT -> {
                // Gargling wet build-up: the zombie rears its head and gurgles acid
                world.playSound(loc, Sound.ENTITY_DROWNED_HURT_WATER, 0.85F, 0.55F);
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,      0.65F, 0.50F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.05D, 0D), 12, 0.28D, 0.22D, 0.28D, 0D, ACID_DUST);
            }
            case ROT_ZONE -> {
                // Slow, diseased exhale before the rot erupts underfoot
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,        0.75F, 0.38F);
                world.playSound(loc, Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.55F, 0.42F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 16, 0.38D, 0.28D, 0.38D, 0D, ROT_DUST);
                world.spawnParticle(Particle.SNEEZE,
                    loc.clone().add(0D, 1.0D, 0D), 4, 0.3D, 0.2D, 0.3D, 0D);
            }
            case POWER_LEAP -> {
                // Aggression burst: zombie tenses its legs and slams the ground
                world.playSound(loc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.90F, 0.65F);
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,           0.70F, 0.75F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 0.5D, 0D), 14, 0.5D, 0.2D, 0.5D, 0D, BRIGHT_GREEN_DUST);
                world.spawnParticle(Particle.BLOCK,
                    loc.clone().add(0D, 0.1D, 0D), 8, 0.4D, 0.1D, 0.4D, 0.05D,
                    Material.GRASS_BLOCK.createBlockData());
            }
            case CHARGE_LEAP -> {
                // Winding charge: ominous clicking as kinetic energy builds
                world.playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CURE,  0.85F, 0.45F);
                world.playSound(loc, Sound.ENTITY_WARDEN_TENDRIL_CLICKS, 0.45F, 0.60F);
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,        0.60F, 0.50F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 20, 0.5D, 0.3D, 0.5D, 0D, CHARGE_DUST);
                world.spawnParticle(Particle.SMOKE,
                    loc.clone().add(0D, 0.5D, 0D), 10, 0.4D, 0.3D, 0.4D, 0.02D);
            }
            case SKULL_BARRAGE -> {
                world.playSound(loc, Sound.ENTITY_SKELETON_HURT,   0.80F, 0.55F);
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,  0.70F, 0.80F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.1D, 0D), 14, 0.3D, 0.25D, 0.3D, 0D, WITHER_DUST);
                world.spawnParticle(Particle.BLOCK,
                    loc.clone().add(0D, 0.2D, 0D),  8, 0.3D, 0.1D, 0.3D, 0.04D,
                    Material.BONE_BLOCK.createBlockData());
            }
            case ZOMBIE_HORDE -> {
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,         0.90F, 0.35F);
                world.playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 0.70F, 0.50F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 0.8D, 0D), 22, 0.6D, 0.4D, 0.6D, 0D, ROT_DUST);
                world.spawnParticle(Particle.SMOKE,
                    loc.clone().add(0D, 0.3D, 0D), 12, 0.5D, 0.2D, 0.5D, 0.02D);
            }
            case NECROTIC_GRASP -> {
                world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,   0.65F, 1.4F);
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,   0.70F, 0.55F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 16, 0.3D, 0.35D, 0.3D, 0D, WITHER_DUST);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D),  8, 0.25D, 0.3D, 0.25D, 0D, DARK_ROT_DUST);
            }
            case TOXIC_BURST -> {
                world.playSound(loc, Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.75F, 0.42F);
                world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,        0.70F, 0.40F);
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(0D, 1.0D, 0D), 20, 0.5D, 0.3D, 0.5D, 0D, TOXIC_DUST);
                world.spawnParticle(Particle.SNEEZE,
                    loc.clone().add(0D, 0.8D, 0D),  8, 0.4D, 0.2D, 0.4D, 0D);
            }
        }
    }

    private void executeAbility(ZombieAbility ability) {
        abilityUseCounts.merge(ability, 1, Integer::sum);
        switch (ability) {
            case ACID_SPIT     -> castAcidSpit();
            case ROT_ZONE      -> castRotZone();
            case POWER_LEAP    -> castPowerLeap();
            case CHARGE_LEAP   -> castChargeLeap();
            case SKULL_BARRAGE -> castSkullBarrage();
            case ZOMBIE_HORDE  -> castZombieHorde();
            case NECROTIC_GRASP -> castNecroticGrasp();
            case TOXIC_BURST   -> castToxicBurst();
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

    private void tickRotAura() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        World world = entity.getWorld();
        Location center = entity.getLocation();
        double auraSq = 16.0D;
        for (Player p : world.getPlayers()) {
            if (p.isDead()) {
                continue;
            }
            if (p.getLocation().distanceSquared(center) <= ROT_AURA_RADIUS_SQUARED) {
                p.damage(0.35D, entity);
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 30, 0, true, true, true));
                world.spawnParticle(Particle.SNEEZE, p.getLocation().add(0D, 1D, 0D), 2, 0.18D, 0.2D, 0.18D, 0D);
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
                    // Infect only where the zombie steps so it feels intentional.
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

    private boolean tickCropHunt(Player player) {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return false;
        }

        if (cropHuntTicks > 0) {
            cropHuntTicks--;
        }

        return false;
    }

    private Location findNearestCrop(Location center, int radius) {
        if (center == null || center.getWorld() == null) {
            return null;
        }
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        Location best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!CROPS.contains(block.getType())) {
                        continue;
                    }
                    Location loc = block.getLocation();
                    double distSq = loc.distanceSquared(center);
                    if (distSq < bestSq) {
                        bestSq = distSq;
                        best = loc;
                    }
                }
            }
        }
        return best;
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
        cloud.setRadius(3.0F);
        cloud.setRadiusPerTick(0F);
        cloud.setDuration(200);
        cloud.setWaitTime(0);
        cloud.setReapplicationDelay(Integer.MAX_VALUE);
        cloud.setParticle(Particle.DUST,
            new Particle.DustOptions(Color.fromRGB(30, 160, 30), 0.8F));

        BukkitRunnable zone = new BukkitRunnable() {
            int iteration = 0;
            float currentRadius = 3.0F;

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
                currentRadius = Math.min(7.0F, 3.0F + iteration * 0.45F);
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
        zone.runTaskTimer(plugin, 10L, 20L);
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
                    // Ground-snap to prevent the zombie floating after landing
                    Location snap = entity.getLocation().clone();
                    double groundY = world.getHighestBlockYAt(snap.getBlockX(), snap.getBlockZ()) + 0.1D;
                    if (snap.getY() > groundY + 0.8D) {
                        snap.setY(groundY);
                        entity.teleport(snap);
                    }
                    triggerChargeLeapShockwave(entity.getLocation(), player);
                    cancel();
                    return;
                }

                // Parabolic arc: horizontal lerp + sine-based vertical
                double progress = (double) tick / TOTAL_TICKS;
                double arcY = Math.sin(Math.PI * progress) * JUMP_HEIGHT;
                Location next = start.clone().add(dx * tick, arcY, dz * tick);
                double surfaceY = start.getWorld().getHighestBlockYAt(next.getBlockX(), next.getBlockZ()) + 0.5D;
                next.setY(Math.max(next.getY(), surfaceY));
                if (tick >= TOTAL_TICKS) {
                    next.setY(end.getBlockY() + 0.5D);
                }
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

        // Close the gap toward the player — horizontal only to avoid leaving the zombie airborne
        Location entityLoc = entity.getLocation();
        Vector toPlayer = player.getLocation().toVector().subtract(entityLoc.toVector());
        toPlayer.setY(0);
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
    // Active ability: SKULL_BARRAGE  — pelts the target with bone projectiles
    // -------------------------------------------------------------------------

    private void castSkullBarrage() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(30.0D);
        if (entity == null || player == null) {
            return;
        }
        World world = entity.getWorld();
        Location eye = entity.getEyeLocation();

        world.playSound(eye, Sound.ENTITY_SKELETON_SHOOT, 0.9F, 0.55F);
        world.playSound(eye, Sound.ENTITY_ZOMBIE_HURT,    0.6F, 0.70F);

        int projectiles = 3 + random.nextInt(3); // 3-5
        for (int i = 0; i < projectiles; i++) {
            final int idx = i;
            BukkitRunnable shot = new BukkitRunnable() {
                @Override
                public void run() {
                    if (entity == null || !entity.isValid()) {
                        cancel();
                        return;
                    }
                    Location origin = entity.getEyeLocation();
                    Vector dir = player.getEyeLocation().toVector().subtract(origin.toVector());
                    if (dir.lengthSquared() < 0.001D) {
                        return;
                    }
                    dir.normalize();
                    // Spread each projectile slightly
                    double spreadAngle = (idx - (projectiles / 2.0D)) * 0.08D;
                    dir.rotateAroundY(spreadAngle + (random.nextDouble() - 0.5D) * 0.05D);
                    dir.setY(dir.getY() + (random.nextDouble() - 0.5D) * 0.06D);

                    Snowball skull = world.spawn(origin, Snowball.class);
                    skull.setVelocity(dir.multiply(1.7D));
                    skull.setMetadata("bloodmoon-zombie-skull",
                        new FixedMetadataValue(plugin, npc.getId()));
                }
            };
            ownedTasks.add(shot);
            shot.runTaskLater(plugin, i * 4L);
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: ZOMBIE_HORDE  — summons a pack of weaker undead minions
    // -------------------------------------------------------------------------

    private void castZombieHorde() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location base = entity.getLocation();
        World world = entity.getWorld();

        world.playSound(base, Sound.ENTITY_ZOMBIE_AMBIENT,          1.0F, 0.38F);
        world.playSound(base, Sound.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 0.8F, 0.55F);
        world.spawnParticle(Particle.SMOKE, base.clone().add(0D, 0.5D, 0D), 20, 1.2D, 0.4D, 1.2D, 0.04D);

        // Clean up any previous horde zombies that are dead
        hordeZombies.removeIf(z -> z == null || !z.isValid() || z.isDead());

        int toSpawn = 2 + random.nextInt(2); // 2-3
        for (int i = 0; i < toSpawn; i++) {
            double angle = (Math.PI * 2.0D / toSpawn) * i;
            double ox = Math.cos(angle) * 2.5D;
            double oz = Math.sin(angle) * 2.5D;
            Location spawnLoc = base.clone().add(ox, 0.1D, oz);
            spawnLoc.setY(world.getHighestBlockYAt(spawnLoc.getBlockX(), spawnLoc.getBlockZ()) + 1.0D);

            org.bukkit.entity.Zombie hordeZombie = (org.bukkit.entity.Zombie) world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
            AttributeInstance maxHp = hordeZombie.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHp != null) { maxHp.setBaseValue(12.0D); }
            hordeZombie.setHealth(12.0D);
            AttributeInstance speed = hordeZombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
            if (speed != null) { speed.setBaseValue(0.27D); }
            AttributeInstance dmg = hordeZombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
            if (dmg != null) { dmg.setBaseValue(2.5D); }
            hordeZombie.setMetadata("bloodmoon-zombie-horde", new FixedMetadataValue(plugin, true));
            hordeZombies.add(hordeZombie);

            world.spawnParticle(Particle.DUST, spawnLoc.clone().add(0D, 0.5D, 0D),
                8, 0.3D, 0.4D, 0.3D, 0D, ROT_DUST);
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: NECROTIC_GRASP — wither + slow aura on nearby players
    // -------------------------------------------------------------------------

    private void castNecroticGrasp() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(16.0D);
        if (entity == null || player == null) {
            return;
        }
        World world = entity.getWorld();
        Location loc = entity.getLocation();

        world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,      0.7F, 1.5F);
        world.playSound(loc, Sound.ENTITY_ZOMBIE_HURT,         0.6F, 0.45F);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.0D, 0D), 28, 0.5D, 0.6D, 0.5D, 0D, WITHER_DUST);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.0D, 0D), 14, 0.4D, 0.5D, 0.4D, 0D, DARK_ROT_DUST);
        world.spawnParticle(Particle.SMOKE, loc.clone().add(0D, 0.5D, 0D), 10, 0.5D, 0.3D, 0.5D, 0.02D);

        double radius = 3.5D;
        double radiusSq = radius * radius;
        for (Player p : world.getPlayers()) {
            if (p.isDead() || p.getLocation().distanceSquared(loc) > radiusSq) {
                continue;
            }
            p.damage(3.0D);
            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,    80,  1, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,  60,  1, false, true));
            world.spawnParticle(Particle.DUST, p.getLocation().clone().add(0D, 1D, 0D),
                10, 0.3D, 0.4D, 0.3D, 0D, WITHER_DUST);
        }
    }

    // -------------------------------------------------------------------------
    // Active ability: TOXIC_BURST — spawns a lingering poison cloud
    // -------------------------------------------------------------------------

    private void castToxicBurst() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        World world = entity.getWorld();
        Location loc = entity.getLocation();

        world.playSound(loc, Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.8F, 0.44F);
        world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,        0.7F, 0.42F);
        world.spawnParticle(Particle.SNEEZE, loc.clone().add(0D, 0.8D, 0D), 25, 0.8D, 0.5D, 0.8D, 0.04D);
        world.spawnParticle(Particle.DUST,   loc.clone().add(0D, 0.8D, 0D), 20, 0.7D, 0.4D, 0.7D, 0D, TOXIC_DUST);

        // Immediate AoE damage (4-block radius)
        double immediateSq = 16.0D;
        for (Player p : world.getPlayers()) {
            if (!p.isDead() && p.getLocation().distanceSquared(loc) <= immediateSq) {
                p.damage(2.0D);
            }
        }

        // Linger cloud
        AreaEffectCloud cloud = world.spawn(loc.clone().add(0D, 0.3D, 0D), AreaEffectCloud.class);
        cloud.setRadius(4.0F);
        cloud.setDuration(140);
        cloud.setReapplicationDelay(30);
        cloud.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 60, 1, false, false), true);
        cloud.setParticle(Particle.SNEEZE);
        cloud.setMetadata("bloodmoon-zombie-toxic", new FixedMetadataValue(plugin, true));
    }

    // -------------------------------------------------------------------------
    // Phase transition: BERSERKER — activates at ≤35% health
    // -------------------------------------------------------------------------

    private void triggerBerserkerPhase(LivingEntity entity) {
        World world = entity.getWorld();
        Location loc = entity.getLocation();

        // One-time dramatic announcement
        world.playSound(loc, Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.1F, 0.30F);
        world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM,    0.7F, 0.40F);
        world.playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT,       1.0F, 0.40F);
        world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0D, 0.5D, 0D), 2, 0.4D, 0.2D, 0.4D, 0D);
        world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1.0D, 0D), 45, 1.0D, 0.8D, 1.0D, 0D, BERSERKER_DUST);
        world.spawnParticle(Particle.LAVA, loc.clone().add(0D, 0.5D, 0D), 15, 0.6D, 0.3D, 0.6D, 0D);

        // Speed boost
        AttributeInstance speed = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(0.38D);
        }
        setNavigationSpeed(1.28F);

        // Glowing red visual for nearby players
        entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false));

        // Announce to nearby players
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= 900.0D && plugin.hasBossMessages(p.getUniqueId())) { // 30 block range
                p.sendMessage("§4§l☠ The Zombie enters a savage BERSERKER rage! ☠");
            }
        }
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

        // --- Common undead drops ---
        if (random.nextDouble() <= 0.75D) {
            world.dropItemNaturally(location, new ItemStack(Material.ROTTEN_FLESH, randomRange(2, 5)));
        }

        // Infected Bone — custom name
        if (random.nextDouble() <= 0.60D) {
            ItemStack bone = new ItemStack(Material.BONE, randomRange(1, 3));
            ItemMeta bm = bone.getItemMeta();
            if (bm != null) {
                bm.setDisplayName("§aInfected Bone");
                bm.setLore(java.util.List.of("§7Coated in festering rot."));
                bone.setItemMeta(bm);
            }
            world.dropItemNaturally(location, bone);
        }

        if (random.nextDouble() <= 0.55D) world.dropItemNaturally(location, new ItemStack(Material.POISONOUS_POTATO,    1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.40D) world.dropItemNaturally(location, new ItemStack(Material.SLIME_BALL,          2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.35D) world.dropItemNaturally(location, new ItemStack(Material.FERMENTED_SPIDER_EYE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.25D) world.dropItemNaturally(location, new ItemStack(Material.GREEN_DYE,           2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.20D) world.dropItemNaturally(location, new ItemStack(Material.IRON_NUGGET,         2 + random.nextInt(4)));

        // Suspicious Stew (Poison variant)
        if (random.nextDouble() <= 0.30D) {
            world.dropItemNaturally(location, new ItemStack(Material.SUSPICIOUS_STEW, 1));
        }

        // Zombie Brain — carved pumpkin with custom name
        if (random.nextDouble() <= 0.15D) {
            ItemStack brain = new ItemStack(Material.CARVED_PUMPKIN);
            ItemMeta m = brain.getItemMeta();
            if (m != null) {
                m.setDisplayName("§2Zombie Brain");
                m.setLore(java.util.List.of("§7Still twitching with undead impulse."));
                brain.setItemMeta(m);
            }
            world.dropItemNaturally(location, brain);
        }

        // Acid Vial — splash potion of poison
        if (random.nextDouble() <= 0.12D) {
            world.dropItemNaturally(location, new ItemStack(Material.SPLASH_POTION, 1));
        }

        // Rare drops
        if (random.nextDouble() <= 0.10D) {
            world.dropItemNaturally(location, new ItemStack(Material.EXPERIENCE_BOTTLE, 1 + random.nextInt(2)));
        }
        if (random.nextDouble() <= 0.10D) {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
            if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                meta.addStoredEnchant(Enchantment.PROTECTION, 3, true);
                book.setItemMeta(meta);
            }
            world.dropItemNaturally(location, book);
        }
        if (random.nextDouble() <= 0.08D) {
            ItemStack sword = new ItemStack(Material.IRON_SWORD, 1);
            ItemMeta smeta = sword.getItemMeta();
            if (smeta != null) {
                smeta.addEnchant(Enchantment.SHARPNESS, 2, true);
                sword.setItemMeta(smeta);
            }
            world.dropItemNaturally(location, sword);
        }
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.ZOMBIE_HEAD,  1));
        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, new ItemStack(Material.IRON_INGOT,   1 + random.nextInt(2)));

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
        if (ability == null || state == ZombieState.DEAD) {
            return false;
        }
        if (cooldowns.getOrDefault(ability, 0) > 0) {
            return false;
        }
        // Ranged/area abilities require a valid target
        return switch (ability) {
            case ACID_SPIT, ROT_ZONE, POWER_LEAP, CHARGE_LEAP,
                 SKULL_BARRAGE, ZOMBIE_HORDE, NECROTIC_GRASP, TOXIC_BURST ->
                target != null && target.isOnline() && !target.isDead();
        };
    }

    private void setCooldown(ZombieAbility ability) {
        switch (ability) {
            case ACID_SPIT     -> cooldowns.put(ability, ACID_SPIT_COOLDOWN);
            case ROT_ZONE      -> cooldowns.put(ability, ROT_ZONE_COOLDOWN);
            case POWER_LEAP    -> cooldowns.put(ability, POWER_LEAP_COOLDOWN);
            case CHARGE_LEAP   -> cooldowns.put(ability, CHARGE_LEAP_COOLDOWN);
            case SKULL_BARRAGE -> cooldowns.put(ability, SKULL_BARRAGE_COOLDOWN);
            case ZOMBIE_HORDE  -> cooldowns.put(ability, ZOMBIE_HORDE_COOLDOWN);
            case NECROTIC_GRASP -> cooldowns.put(ability, NECROTIC_GRASP_COOLDOWN);
            case TOXIC_BURST   -> cooldowns.put(ability, TOXIC_BURST_COOLDOWN);
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
        sentinel.allIgnores = new SentinelTargetList();
        sentinel.addIgnore("npcs");
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

    // =========================================================================
    // Casting particle system
    // =========================================================================

    /** Called every tick during CASTING; dispatches to per-ability particle methods. */
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
            case ACID_SPIT    -> spawnAcidSpitCastingParticles(world, base);
            case ROT_ZONE     -> spawnRotZoneCastingParticles(world, base);
            case POWER_LEAP   -> spawnPowerLeapCastingParticles(world, base);
            case CHARGE_LEAP  -> spawnChargeLeapCastingParticles(world, base);
            case SKULL_BARRAGE, NECROTIC_GRASP -> {
                spawnGenericCastingRing(world, base);
                world.spawnParticle(Particle.DUST, base.clone().add(0D, 1.1D, 0D), 4, 0.2D, 0.2D, 0.2D, 0D, WITHER_DUST);
            }
            case ZOMBIE_HORDE -> {
                spawnGenericCastingRing(world, base);
                world.spawnParticle(Particle.SMOKE, base.clone().add(0D, 0.5D, 0D), 2, 0.3D, 0.2D, 0.3D, 0.01D);
            }
            case TOXIC_BURST -> {
                spawnGenericCastingRing(world, base);
                world.spawnParticle(Particle.DUST, base.clone().add(0D, 0.8D, 0D), 5, 0.3D, 0.2D, 0.3D, 0D, TOXIC_DUST);
            }
        }
    }

    private void spawnGenericCastingRing(World world, Location base) {
        double angle = stateTicks * 0.45D;
        for (int step = 0; step < 3; step++) {
            double offset = angle + (step * (Math.PI * 2.0D / 3.0D));
            Location point = base.clone().add(
                Math.cos(offset) * 0.75D,
                0.35D + (stateTicks % 20) * 0.045D,
                Math.sin(offset) * 0.75D);
            world.spawnParticle(Particle.DUST, point, 1, 0.02D, 0.02D, 0.02D, 0.0D, ROT_DUST);
        }
    }

    private void spawnAcidSpitCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0.0D, 1.05D, 0.0D);
        world.spawnParticle(Particle.DUST, focus, 7, 0.18D, 0.20D, 0.18D, 0.0D, ACID_DUST);

        // Faint particle stream reaching toward the target as aim is taken
        if (target != null && target.isOnline() && !target.isDead()) {
            Location eye  = base.clone().add(0D, 1.3D, 0D);
            Location dest = target.getEyeLocation();
            for (int s = 0; s < 4; s++) {
                double progress = ((stateTicks * 0.12D) + (s * 0.2D)) % 1.0D;
                Location point  = eye.clone().add(
                    dest.clone().subtract(eye).toVector().multiply(progress));
                world.spawnParticle(Particle.DUST, point, 1, 0.04D, 0.04D, 0.04D, 0.0D, ACID_DUST);
            }
        }
        if (stateTicks % 12 == 0) {
            world.playSound(focus, Sound.ENTITY_DROWNED_HURT_WATER, 0.35F, 0.55F);
        }
    }

    private void spawnRotZoneCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.38D;
        for (int step = 0; step < 8; step++) {
            double offset = angle + (step * (Math.PI * 2.0D / 8.0D));
            double radius = 0.7D + Math.sin(stateTicks * 0.18D + step) * 0.18D;
            double height = 0.4D + (step % 3) * 0.3D;
            Location point = base.clone().add(
                Math.cos(offset) * radius, height, Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST,  point, 2, 0.04D, 0.06D, 0.04D, 0.0D, ROT_DUST);
            if (step % 2 == 0) {
                world.spawnParticle(Particle.SNEEZE, point, 1, 0.02D, 0.03D, 0.02D, 0.0D);
            }
        }
        if (stateTicks % 8 == 0) {
            world.playSound(base, Sound.ENTITY_ZOMBIE_AMBIENT,
                0.3F, 0.35F + random.nextFloat() * 0.1F);
        }
    }

    private void spawnPowerLeapCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        Location focus = base.clone().add(0.0D, 1.05D, 0.0D);
        world.spawnParticle(Particle.DUST, focus, 7, 0.24D, 0.16D, 0.24D, 0.0D, BRIGHT_GREEN_DUST);
        world.spawnParticle(Particle.CRIT, base.clone().add(0D, 0.5D, 0D), 3, 0.3D, 0.2D, 0.3D, 0.04D);
        if (stateTicks % 14 == 0) {
            world.playSound(focus, Sound.ENTITY_DROWNED_SHOOT, 0.35F, 0.6F);
        }
    }

    private void spawnChargeLeapCastingParticles(World world, Location base) {
        double angle = stateTicks * 1.05D;
        for (int step = 0; step < 12; step++) {
            double offset = angle + (step * (Math.PI * 2.0D / 12.0D));
            double radius = 0.35D + (step * 0.08D);
            Location point = base.clone().add(
                Math.cos(offset) * radius,
                0.05D + (step % 3) * 0.18D,
                Math.sin(offset) * radius);
            world.spawnParticle(Particle.DUST,  point, 2, 0.03D, 0.03D, 0.03D, 0.0D, CHARGE_DUST);
            if (step % 3 == 0) {
                world.spawnParticle(Particle.SMOKE, point, 1, 0.02D, 0.02D, 0.02D, 0.02D);
            }
        }
        if (stateTicks % 5 == 0) {
            world.playSound(base, Sound.ENTITY_WARDEN_TENDRIL_CLICKS,
                0.28F, 0.9F + random.nextFloat() * 0.3F);
        }
    }

    // =========================================================================
    // Casting animation system  (Citizens PlayerAnimation via reflection)
    // =========================================================================

    /** Called every tick during CASTING; faces target and dispatches arm animations. */
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
            case ACID_SPIT   -> animateAcidSpitCasting(npcPlayer);
            case ROT_ZONE    -> animateRotZoneCasting(npcPlayer);
            case POWER_LEAP  -> animatePowerLeapCasting(npcPlayer);
            case CHARGE_LEAP -> animateChargeLeapCasting(npcPlayer);
        }
    }

    /** Acid-spit cast: NPC holds item up to aim, then releases with a main-hand swing. */
    private void animateAcidSpitCasting(Player p) {
        if (stateTicks % 6 == 0) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
        }
        if (stateTicks == castingTicks - 2) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
    }

    /** Rot-zone cast: two-handed incantation – slow, deliberate arm swings. */
    private void animateRotZoneCasting(Player p) {
        if (stateTicks % 5 == 0) {
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
            playCitizensPlayerAnimation(p, "START_USE_OFFHAND_ITEM");
        }
        if (stateTicks % 10 == 0) {
            p.swingMainHand();
            p.swingOffHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
    }

    /** Power-leap cast: aggressive rapid arm swings as the zombie winds up. */
    private void animatePowerLeapCasting(Player p) {
        if (stateTicks % 3 == 0) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
        if (stateTicks % 5 == 0) {
            p.swingOffHand();
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
        }
    }

    /** Charge-leap cast: rapid alternating swings with item hold – kinetic wind-up. */
    private void animateChargeLeapCasting(Player p) {
        if (stateTicks % 2 == 0) {
            p.swingMainHand();
            playCitizensPlayerAnimation(p, "ARM_SWING");
        }
        if (stateTicks % 4 == 0) {
            p.swingOffHand();
            playCitizensPlayerAnimation(p, "ARM_SWING_OFFHAND");
            playCitizensPlayerAnimation(p, "START_USE_MAINHAND_ITEM");
        }
    }

    /** Clears any held-item animation on the NPC after a cast completes. */
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
    // Hit-reaction hooks  (called from NPCListener / PlayerListener)
    // =========================================================================

    /**
     * Called by NPCListener whenever the zombie NPC takes damage.
     * Snaps the zombie to COMBAT if it is not already engaged, and emits a
     * short burst of infected particles as a visual hurt-reaction.
     */
    public void onTakeDamage() {
        if (state == ZombieState.DEAD || state == ZombieState.INFECTED_RAGE) {
            return;
        }
        if (state != ZombieState.COMBAT && state != ZombieState.CASTING) {
            state = ZombieState.COMBAT;
            stateTicks = 0;
            combatInitialized = false;
        }
        // Infected splatter: brief particle burst on hit
        Location loc = getCurrentLocation();
        World world = loc.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.DUST,
                loc.clone().add(0D, 1.0D, 0D), 10, 0.4D, 0.5D, 0.4D, 0D, ACID_DUST);
            world.spawnParticle(Particle.SNEEZE,
                loc.clone().add(0D, 1.0D, 0D),  3, 0.25D, 0.25D, 0.25D, 0D);
        }
    }

    /**
     * Called by PlayerListener when the zombie entity itself is struck.
     * Forces COMBAT engagement and acquires the nearest player as target
     * if the zombie is still in its pre-combat INFECTED_RAGE wind-up.
     */
    public void triggerSnapFromDamage() {
        if (state == ZombieState.DEAD) {
            return;
        }
        if (state == ZombieState.INFECTED_RAGE
                || (state != ZombieState.COMBAT && state != ZombieState.CASTING)) {
            state = ZombieState.COMBAT;
            stateTicks = 0;
            combatInitialized = false;
            if (target == null || !target.isOnline() || target.isDead()) {
                target = findNearestPlayer(getCurrentLocation(), 52.0D);
            }
        }
    }

    // =========================================================================
    // Visibility utility
    // =========================================================================

    /** Ensures the NPC is spawned and visible in the world. */
    private void setNpcVisible() {
        if (!npc.isSpawned()) {
            npc.spawn(lastKnownLocation == null
                ? spawnLocation.clone()
                : lastKnownLocation.clone());
        }
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            entity.setRemoveWhenFarAway(false);
        }
    }
}







