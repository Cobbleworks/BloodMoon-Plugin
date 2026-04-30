package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.traits.ClownTrait;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Firework;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
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

public final class ClownNPC {

    public enum ClownState { WANDERING, COMBAT, CASTING, TAUNTING, MANIC, DEAD }

    public enum ClownAbility {
        FIREWORK_VOLLEY(25), BUNNY_SWARM(20), HOOK_PULL(18), WIND_BURST(15), CHAOS_DASH(12), PARROT_BARRAGE(14), DUCK_INFERNO(13);
        private final int weight;
        ClownAbility(int w) { this.weight = w; }
        public int getWeight() { return weight; }
    }

    private static final int COMBAT_ABILITY_INTERVAL    = 45;
    private static final int COMBAT_AMBIENT_INTERVAL    = 60;
    private static final int WANDER_DIRECTION_INTERVAL  = 90;
    private static final int WANDER_SNAP_CHECK_INTERVAL = 20;
    private static final int DEATH_REMOVE_DELAY         = 60;
    private static final int FIREWORK_VOLLEY_COOLDOWN   = 100;
    private static final int BUNNY_SWARM_COOLDOWN       = 240;
    private static final int HOOK_PULL_COOLDOWN         = 160;
    private static final int WIND_BURST_COOLDOWN        = 130;
    private static final int CHAOS_DASH_COOLDOWN        = 180;
    private static final int PARROT_BARRAGE_COOLDOWN    = 260;
    private static final int DUCK_INFERNO_COOLDOWN      = 220;
    private static final int    FIREWORK_COUNT          = 3;
    private static final double FIREWORK_DAMAGE         = 7.0D;
    private static final double FIREWORK_HIT_RANGE      = 1.6D;
    private static final int    FIREWORK_LIFESPAN_TICKS = 70;
    private static final int    BUNNY_SWARM_COUNT       = 2;
    private static final double HOOK_HIT_RANGE          = 1.5D;
    private static final int    HOOK_LIFESPAN_TICKS     = 60;
    private static final double HOOK_PUSH_STRENGTH      = 1.8D;
    private static final int    WIND_CHARGE_COUNT       = 5;
    private static final double WIND_BURST_DAMAGE       = 3.0D;
    private static final double CHAOS_DASH_DAMAGE       = 5.0D;
    private static final int    MAX_BUNNIES             = 4;
    private static final int    BAIT_TRAP_COOLDOWN_MAX  = 600;

    private static final Particle.DustOptions JESTER_PINK  = new Particle.DustOptions(Color.fromRGB(255, 80, 160), 1.2F);
    private static final Particle.DustOptions JESTER_CYAN  = new Particle.DustOptions(Color.fromRGB(0, 210, 255), 1.1F);
    private static final Particle.DustOptions JESTER_GOLD  = new Particle.DustOptions(Color.fromRGB(255, 210, 0), 1.0F);
    private static final Particle.DustOptions JESTER_WHITE = new Particle.DustOptions(Color.fromRGB(255, 255, 255), 1.0F);

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random;
    private final Map<ClownAbility, Integer> cooldowns;
    private final List<BukkitRunnable> ownedTasks;
    private final ClownBunnySwarm bunnySwarm;

    private ClownState state;
    private ClownState stateBeforeCasting;
    private ClownAbility pendingAbility;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private long lifeTicks;
    private int stateTicks;
    private int castingDurationTicks;
    private int damageTeleportCooldown;
    private int tauntDuration;
    private int baitTrapCooldown;
    private boolean cleanedUp;
    private boolean deathSequenceStarted;
    private boolean combatInitialized;

    public ClownNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation) {
        this.plugin        = plugin;
        this.npc           = npc;
        this.spawnLocation = spawnLocation.clone();
        this.random        = new Random();
        this.cooldowns     = new EnumMap<>(ClownAbility.class);
        this.ownedTasks    = new ArrayList<>();
        this.bunnySwarm    = new ClownBunnySwarm(plugin, this);
        this.state         = ClownState.WANDERING;
        this.stateBeforeCasting = ClownState.COMBAT;
        this.lastKnownLocation  = spawnLocation.clone();
        configureNpc();
        startController();
    }

    public NPC getNpc()          { return npc; }
    public ClownState getState() { return state; }
    public boolean isDead()      { return state == ClownState.DEAD || cleanedUp || deathSequenceStarted; }

    public Location getCurrentLocation() {
        LivingEntity entity = getLivingEntity();
        if (entity != null) { lastKnownLocation = entity.getLocation().clone(); return lastKnownLocation; }
        return lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone();
    }

    public void triggerSnapFromDamage() {
        if (state == ClownState.WANDERING) transitionToCombat(findNearestPlayer(getCurrentLocation(), 48.0D));
    }

    public void onTakeDamage() {
        if (state == ClownState.DEAD || state == ClownState.WANDERING || state == ClownState.TAUNTING) return;
        if (damageTeleportCooldown > 0) return;
        if (random.nextDouble() < 0.28D) {
            teleportEscape();
            damageTeleportCooldown = 80;
        }
    }

    private void teleportEscape() {
        Player nearest = findNearestPlayer(getCurrentLocation(), 20.0D);
        Location origin = getCurrentLocation();
        Vector awayDir;
        if (nearest != null) {
            awayDir = origin.toVector().subtract(nearest.getLocation().toVector());
            if (awayDir.lengthSquared() < 0.01D) awayDir = new Vector(randomDouble(-1,1), 0, randomDouble(-1,1));
        } else {
            awayDir = new Vector(randomDouble(-1,1), 0, randomDouble(-1,1));
        }
        awayDir.normalize().multiply(6.0D + randomDouble(2.0D, 4.0D));
        Location escapeDest = findSafeGroundLocation(origin.clone().add(awayDir));
        World w = origin.getWorld();
        if (w != null) {
            w.spawnParticle(Particle.DUST, origin.clone().add(0,1,0), 15, 0.4, 0.5, 0.4, 0, JESTER_CYAN);
            w.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 1.8F);
        }
        npc.teleport(escapeDest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        World dw = escapeDest.getWorld();
        if (dw != null) {
            dw.playSound(escapeDest, Sound.ENTITY_WITCH_CELEBRATE, 0.7F, 1.5F);
            dw.spawnParticle(Particle.FIREWORK, escapeDest, 15, 0.3, 0.5, 0.3, 0.07);
        }
    }

    public void onTraitTick()  { updateLastKnownLocation(); }

    public void onNpcSpawn() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) return;
        entity.setRemoveWhenFarAway(false);
        applyConfiguredHealth(entity);
        hideNameplate(entity);
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        if (!(event.getTarget() instanceof Player player)) return;
        if (state == ClownState.WANDERING || state == ClownState.CASTING || state == ClownState.TAUNTING || state == ClownState.DEAD) { event.setCancelled(true); return; }
        target = player;
        if (random.nextDouble() < 0.10D) { ClownAbility a = chooseAbility(); if (a != null && canUseAbility(a)) startCasting(a); }
    }

    public void startDeathSequence() {
        if (deathSequenceStarted) return;
        deathSequenceStarted = true;
        state = ClownState.DEAD; stateTicks = 0;
        Location loc = getCurrentLocation();
        cancelControllerOnly(); cancelOwnedTasks(); bunnySwarm.cleanup();
        World world = loc.getWorld();
        if (world != null) {
            spawnDeathExplosion(world, loc);
            world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE, 1.0F, 0.3F);
            world.playSound(loc, Sound.ENTITY_GHAST_SCREAM, 0.5F, 0.6F);
            world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4F, 1.8F);
            dropLoot(world, loc);
        }
        BukkitRunnable rem = new BukkitRunnable() { public void run() { cleanup(); } };
        ownedTasks.add(rem); rem.runTaskLater(plugin, DEATH_REMOVE_DELAY);
    }

    public void cleanup() {
        if (cleanedUp) return;
        cleanedUp = true;
        cancelControllerOnly(); cancelOwnedTasks(); bunnySwarm.cleanup();
        if (npc.isSpawned()) npc.despawn();
        try { npc.destroy(); } catch (Exception ignored) {}
        plugin.getNPCManager().unregisterClown(npc.getId());
    }

    public LivingEntity getLivingEntityPublic() { return getLivingEntity(); }
    public double randomDouble(double min, double max) { return min + (random.nextDouble() * (max - min)); }

    public Player findNearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null) return null;
        double rs = radius * radius;
        return location.getWorld().getPlayers().stream()
            .filter(p -> !p.isDead())
            .filter(p -> p.getLocation().distanceSquared(location) <= rs)
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    private void configureNpc() {
        npc.data().set("bloodmoon-clown", true);
        npc.data().set("nameplate-visible", false);
        npc.data().set("always-use-name-hologram", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        configureClownTrait(); configureSkin(); configureSentinel(); spawnHiddenNpc();
    }

    private void configureClownTrait() {
        ClownTrait trait = npc.getOrAddTrait(ClownTrait.class); trait.bind(this);
    }

    private void configureSkin() {
        String skinName  = plugin.getConfigManager().getClownSkinName();
        String texture   = plugin.getConfigManager().getClownSkinTexture();
        String signature = plugin.getConfigManager().getClownSkinSignature();
        if ((skinName == null || skinName.isBlank()) && (texture == null || texture.isBlank())) return;
        try {
            Class<? extends Trait> sc = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait st = npc.getOrAddTrait(sc);
            sc.getMethod("setShouldUpdateSkins", boolean.class).invoke(st, false);
            sc.getMethod("setFetchDefaultSkin",  boolean.class).invoke(st, false);
            if (texture != null && !texture.isBlank() && signature != null && !signature.isBlank()) {
                String key = (skinName == null || skinName.isBlank()) ? "bloodmoon_clown" : skinName;
                sc.getMethod("setSkinPersistent", String.class, String.class, String.class).invoke(st, key, signature, texture);
                return;
            }
            if (skinName != null && !skinName.isBlank()) {
                sc.getMethod("setSkinName", String.class, boolean.class).invoke(st, skinName, true);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply SkinTrait to clown NPC " + npc.getId() + ": " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
        s.setInvincible(false);
        s.setHealth(plugin.getConfigManager().getClownHealth());
        s.health = plugin.getConfigManager().getClownHealth();
        s.damage = 5.0D; s.respawnTime = -1; s.chaseRange = 30.0D; s.armor = 0.0D;
        s.protectFromIgnores = false;
        s.allTargets = new SentinelTargetList(); s.addTarget("players");
        s.allIgnores = new SentinelTargetList();
        npc.setProtected(false);
    }

    private void spawnHiddenNpc() {
        if (!npc.isSpawned()) npc.spawn(spawnLocation.clone());
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            applyConfiguredHealth(entity);
            entity.setInvulnerable(false); entity.setMaximumNoDamageTicks(0); entity.setNoDamageTicks(0);
            hideNameplate(entity);
        }
    }

    private void hideNameplate(LivingEntity entity) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            if (board == null) return;
            Team team = board.getTeam("bm_hidden_npc");
            if (team == null) { team = board.registerNewTeam("bm_hidden_npc"); team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER); }
            if (entity instanceof Player player) team.addEntry(player.getName());
        } catch (Exception ex) { plugin.getLogger().warning("Could not hide clown nameplate: " + ex.getMessage()); }
    }

    private void applyConfiguredHealth(LivingEntity entity) {
        double hp = plugin.getConfigManager().getClownHealth();
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) { attr.setBaseValue(hp); entity.setHealth(Math.min(hp, entity.getHealth())); }
    }

    private void startController() {
        controllerTask = new BukkitRunnable() { public void run() { tick(); } };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick() {
        if (cleanedUp || deathSequenceStarted) return;
        lifeTicks++; stateTicks++; decrementCooldowns(); updateLastKnownLocation();
        if (damageTeleportCooldown > 0) damageTeleportCooldown--;
        if (baitTrapCooldown > 0) baitTrapCooldown--;
        switch (state) {
            case WANDERING -> tickWandering();
            case COMBAT    -> tickCombat();
            case MANIC     -> tickManic();
            case TAUNTING  -> tickTaunting();
            case CASTING   -> tickCasting();
            case DEAD      -> {}
        }
    }

    private void tickWandering() {
        LivingEntity entity = getLivingEntity(); if (entity == null) return;
        if (stateTicks % COMBAT_AMBIENT_INTERVAL == 0) { playCreepyLaugh(entity.getLocation(), false); spawnWanderAmbientParticles(entity); }
        if (stateTicks % WANDER_DIRECTION_INTERVAL == 0) {
            Location wt = spawnLocation.clone().add(randomDouble(-10,10), 0, randomDouble(-10,10));
            wt = findSafeGroundLocation(wt);
            setNavigationSpeed(0.55F); npc.getNavigator().setTarget(wt);
        }
        if (stateTicks % WANDER_SNAP_CHECK_INTERVAL == 0) {
            Player nearby = findNearestPlayer(entity.getLocation(), plugin.getConfigManager().getClownSnapRadius());
            if (nearby != null) transitionToCombat(nearby);
        }
    }

    private void spawnWanderAmbientParticles(LivingEntity entity) {
        Location loc = entity.getLocation().add(0, 1, 0);
        entity.getWorld().spawnParticle(Particle.DUST, loc, 8, 0.4, 0.4, 0.4, 0, JESTER_PINK);
        entity.getWorld().spawnParticle(Particle.DUST, loc, 6, 0.4, 0.4, 0.4, 0, JESTER_CYAN);
        entity.getWorld().spawnParticle(Particle.NOTE, loc, 3, 0.5, 0.3, 0.5, 0.5);
    }

    private void transitionToCombat(Player player) {
        if (state == ClownState.DEAD) return;
        state = ClownState.COMBAT; stateTicks = 0; target = player; combatInitialized = false;
        setNpcVisible(); initializeCombat();
        Location loc = getCurrentLocation(); World world = loc.getWorld();
        if (world != null) {
            playCreepyLaugh(loc, true);
            world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 30, 0.8, 0.8, 0.8, 0, JESTER_PINK);
            world.spawnParticle(Particle.FIREWORK, loc, 20, 0.6, 0.6, 0.6, 0.08);
        }
    }

    private void initializeCombat() {
        if (combatInitialized) return;
        combatInitialized = true; setNavigationSpeed(1.3F);
        SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
        s.allTargets = new SentinelTargetList(); s.addTarget("players");
        s.allIgnores = new SentinelTargetList(); s.chaseRange = 30.0D; s.respawnTime = -1;
    }

    private void tickCombat() {
        initializeCombat(); checkManicTransition();
        Player player = ensureTarget(48.0D);
        if (player == null) { player = findNearestPlayer(getCurrentLocation(), 48.0D); target = player; }
        if (player == null) { setNavigationSpeed(0.7F); return; }
        target = player; chaseCombatTarget(player);
        if (stateTicks % COMBAT_AMBIENT_INTERVAL == 0) {
            Location loc = getCurrentLocation(); World world = loc.getWorld();
            if (world != null) {
                playCreepyLaugh(loc, false);
                world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 6, 0.3, 0.3, 0.3, 0, JESTER_PINK);
                world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 4, 0.3, 0.3, 0.3, 0, JESTER_CYAN);
            }
        }
        if (stateTicks % 50 == 0 && player != null) playTrollTune(player.getLocation());
        if (stateTicks % COMBAT_ABILITY_INTERVAL == 0) { ClownAbility a = chooseAbility(); if (a != null && canUseAbility(a)) startCasting(a); }
    }

    private void chaseCombatTarget(Player player) {
        if (!npc.isSpawned()) return;
        setNavigationSpeed(1.3F); npc.getNavigator().setTarget(player, true); npc.faceLocation(player.getEyeLocation());
    }

    private void checkManicTransition() {
        if (state == ClownState.MANIC) return;
        LivingEntity entity = getLivingEntity(); if (entity == null) return;
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH); if (attr == null) return;
        if (entity.getHealth() / attr.getValue() <= plugin.getConfigManager().getClownManicHpThreshold()) {
            state = ClownState.MANIC; stateTicks = 0; combatInitialized = false;
            Location loc = getCurrentLocation(); World world = loc.getWorld();
            if (world != null) {
                world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE, 1.2F, 0.25F);
                world.playSound(loc, Sound.ENTITY_GHAST_SCREAM, 0.6F, 0.5F);
                world.playSound(loc, Sound.ENTITY_ENDERMAN_SCREAM, 0.5F, 0.8F);
                world.spawnParticle(Particle.FIREWORK, loc, 80, 1.2, 1.2, 1.2, 0.12);
                world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 40, 1.0, 1.0, 1.0, 0, JESTER_PINK);
                world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 30, 1.0, 1.0, 1.0, 0, JESTER_GOLD);
            }
            entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20*60, 1, true, true, true));
        }
    }

    private void tickManic() {
        initializeCombat();
        Player player = ensureTarget(48.0D);
        if (player == null) { player = findNearestPlayer(getCurrentLocation(), 48.0D); target = player; }
        if (player == null) return;
        target = player; setNavigationSpeed(1.6F);
        npc.getNavigator().setTarget(player, true); npc.faceLocation(player.getEyeLocation());
        if (stateTicks % 30 == 0) {
            Location loc = getCurrentLocation(); World world = loc.getWorld();
            if (world != null) {
                playCreepyLaugh(loc, true);
                world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 10, 0.5, 0.5, 0.5, 0, JESTER_GOLD);
                world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 8,  0.5, 0.5, 0.5, 0, JESTER_PINK);
                world.spawnParticle(Particle.FIREWORK, loc, 10, 0.4, 0.4, 0.4, 0.06);
            }
        }
        if (stateTicks % 36 == 0 && player != null) playTrollTune(player.getLocation());
        int interval = (int) Math.max(20, COMBAT_ABILITY_INTERVAL * plugin.getConfigManager().getClownManicCooldownMultiplier());
        if (stateTicks % interval == 0) { ClownAbility a = chooseAbility(); if (a != null && canUseAbility(a)) startCasting(a); }
    }

    private void startCasting(ClownAbility ability) {
        if (ability == null || state == ClownState.DEAD || state == ClownState.CASTING) return;
        if (!canUseAbility(ability)) return;
        pendingAbility = ability;
        stateBeforeCasting = (state == ClownState.MANIC) ? ClownState.MANIC : ClownState.COMBAT;
        state = ClownState.CASTING; stateTicks = 0;
        castingDurationTicks = switch (ability) {
            case FIREWORK_VOLLEY -> random.nextInt(8)  + 18;
            case BUNNY_SWARM     -> random.nextInt(12) + 28;
            case HOOK_PULL       -> random.nextInt(8)  + 20;
            case WIND_BURST      -> random.nextInt(6)  + 16;
            case CHAOS_DASH      -> random.nextInt(6)  + 14;
            case PARROT_BARRAGE  -> random.nextInt(8)  + 20;
            case DUCK_INFERNO    -> random.nextInt(8)  + 18;
        };
        npc.getNavigator().cancelNavigation();
        Location loc = getCurrentLocation(); World world = loc.getWorld();
        if (world != null) {
            switch (ability) {
                case BUNNY_SWARM -> {
                    world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9F, 1.9F);
                    world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE,  0.7F, 0.5F);
                    world.playSound(loc, Sound.ENTITY_RABBIT_ATTACK,    0.6F, 0.4F);
                }
                case DUCK_INFERNO -> {
                    world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_SNARE, 0.8F, 1.6F);
                    world.playSound(loc, Sound.ENTITY_CHICKEN_EGG,     0.6F, 0.5F);
                    world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_BASS,  0.5F, 0.3F);
                }
                case PARROT_BARRAGE -> {
                    world.playSound(loc, Sound.ENTITY_PARROT_IMITATE_BLAZE, 0.8F, 1.2F);
                    world.playSound(loc, Sound.ENTITY_PARROT_AMBIENT,       0.7F, 1.4F);
                }
                case FIREWORK_VOLLEY -> {
                    world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8F, 0.6F);
                    world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING,        0.7F, 1.6F);
                }
                case HOOK_PULL -> {
                    world.playSound(loc, Sound.ENTITY_FISHING_BOBBER_THROW,   0.9F, 0.7F);
                    world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME,    0.5F, 0.8F);
                }
                case WIND_BURST -> {
                    world.playSound(loc, Sound.ENTITY_BREEZE_WIND_BURST, 0.9F, 0.8F);
                    world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4F, 1.5F);
                }
                case CHAOS_DASH -> {
                    world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 1.4F);
                    world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE,   0.7F, 0.6F);
                }
            }
        }
    }

    private void tickCasting() {
        runCastingParticles(); updateCastingAnimation();
        if (stateTicks < castingDurationTicks) return;
        ClownAbility ability = pendingAbility; pendingAbility = null;
        executeAbility(ability); resetCastingAnimation();
        if (state == ClownState.CASTING) { state = stateBeforeCasting; stateTicks = 0; }
    }

    private void runCastingParticles() {
        Location base = getCurrentLocation(); World world = base.getWorld(); if (world == null) return;
        if (pendingAbility == null) { spawnGenericCastingRing(world, base); return; }
        switch (pendingAbility) {
            case FIREWORK_VOLLEY -> spawnFireworkCastingParticles(world, base);
            case BUNNY_SWARM     -> spawnBunnySwarmCastingParticles(world, base);
            case HOOK_PULL       -> spawnHookCastingParticles(world, base);
            case WIND_BURST      -> spawnWindBurstCastingParticles(world, base);
            case CHAOS_DASH      -> spawnChaosDashCastingParticles(world, base);
            case PARROT_BARRAGE, DUCK_INFERNO -> spawnParrotCastingParticles(world, base);
        }
    }

    private void updateCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (!(entity instanceof Player npcPlayer)) return;
        if (target != null && target.isOnline() && !target.isDead()) npc.faceLocation(target.getEyeLocation());
        if (pendingAbility == null) return;
        switch (pendingAbility) {
            case FIREWORK_VOLLEY -> animateFireworkVolley(npcPlayer);
            case BUNNY_SWARM     -> animateBunnySwarm(npcPlayer);
            case HOOK_PULL       -> animateHookPull(npcPlayer);
            case WIND_BURST      -> animateWindBurst(npcPlayer);
            case CHAOS_DASH      -> animateChaosDash(npcPlayer);
            case PARROT_BARRAGE, DUCK_INFERNO -> animateParrotBarrage(npcPlayer);
        }
    }

    private void resetCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (entity instanceof Player p) playCitizensPlayerAnimation(p, "STOP_USE_ITEM");
    }

    private void spawnGenericCastingRing(World world, Location base) {
        double angle = stateTicks * 0.45D;
        for (int i = 0; i < 4; i++) {
            double offset = angle + (i * (Math.PI * 2.0D / 4.0D));
            world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(offset)*0.75, 0.4+(stateTicks%20)*0.04, Math.sin(offset)*0.75), 1, 0.02, 0.02, 0.02, 0, JESTER_PINK);
        }
    }

    private void spawnFireworkCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        double angle = stateTicks * 0.55D;
        for (int i = 0; i < 5; i++) {
            double offset = angle + (i*(Math.PI*2.0/5.0));
            double radius = 0.55 + Math.sin(stateTicks*0.22+i)*0.1;
            Location point = base.clone().add(Math.cos(offset)*radius, 1.0+(i%3)*0.15, Math.sin(offset)*radius);
            world.spawnParticle(Particle.DUST,    point, 2, 0.04, 0.06, 0.04, 0, JESTER_GOLD);
            world.spawnParticle(Particle.FIREWORK, point, 1, 0.02, 0.02, 0.02, 0.02);
        }
        if (stateTicks % 5 == 0) world.playSound(base, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.3F, 1.8F);
    }

    private void spawnBunnySwarmCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.40D;
        for (int i = 0; i < 8; i++) {
            double offset = angle + (i*(Math.PI*2.0/8.0));
            double radius = 0.6 + Math.sin(stateTicks*0.15+i)*0.15;
            Location point = base.clone().add(Math.cos(offset)*radius, 0.3+(i%4)*0.2, Math.sin(offset)*radius);
            world.spawnParticle(Particle.DUST,          point, 2, 0.03, 0.05, 0.03, 0, JESTER_PINK);
            world.spawnParticle(Particle.HAPPY_VILLAGER, point, 1, 0.05, 0.05, 0.05, 0.01);
        }
        if (stateTicks % 8 == 0) world.playSound(base, Sound.ENTITY_RABBIT_ATTACK, 0.4F, 0.5F+random.nextFloat()*0.4F);
    }

    private void spawnHookCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        if (target != null) {
            Location eye = base.clone().add(0, 1.2, 0); Location dest = target.getEyeLocation();
            for (int s = 0; s < 5; s++) {
                double progress = ((stateTicks*0.10)+(s*0.18))%1.0;
                world.spawnParticle(Particle.DUST, eye.clone().add(dest.clone().subtract(eye).toVector().multiply(progress)), 1, 0.03, 0.03, 0.03, 0, JESTER_CYAN);
            }
        }
        if (stateTicks % 10 == 0) world.playSound(base, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.35F, 1.4F);
    }

    private void spawnWindBurstCastingParticles(World world, Location base) {
        double angle = stateTicks * 1.15D;
        for (int i = 0; i < 10; i++) {
            double offset = angle+(i*(Math.PI*2.0/10.0));
            double radius = 0.4+(i*0.07);
            Location point = base.clone().add(Math.cos(offset)*radius, 0.1+(i%3)*0.18, Math.sin(offset)*radius);
            world.spawnParticle(Particle.CLOUD, point, 1, 0.02, 0.02, 0.02, 0.01);
            world.spawnParticle(Particle.DUST,  point, 1, 0.02, 0.02, 0.02, 0, JESTER_WHITE);
        }
        if (stateTicks % 5 == 0) world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.3F, 1.9F);
    }

    private void spawnChaosDashCastingParticles(World world, Location base) {
        double angle = stateTicks * 0.85D;
        for (int i = 0; i < 12; i++) {
            double offset = angle+(i*(Math.PI*2.0/12.0));
            Particle.DustOptions color = (i%3==0)?JESTER_PINK:(i%3==1)?JESTER_CYAN:JESTER_GOLD;
            world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(offset)*(0.3+i*0.06), 0.05+(i%4)*0.16, Math.sin(offset)*(0.3+i*0.06)), 2, 0.03, 0.03, 0.03, 0, color);
        }
        if (stateTicks % 4 == 0) world.playSound(base, Sound.ENTITY_ILLUSIONER_PREPARE_BLINDNESS, 0.25F, 1.5F);
    }

    private void animateFireworkVolley(Player p) {
        if (stateTicks%4==0) { p.swingMainHand(); playCitizensPlayerAnimation(p,"ARM_SWING"); }
        if (stateTicks%8==0) { p.swingOffHand();  playCitizensPlayerAnimation(p,"ARM_SWING_OFFHAND"); }
    }
    private void animateBunnySwarm(Player p) {
        if (stateTicks%5==0) { p.swingMainHand(); playCitizensPlayerAnimation(p,"ARM_SWING"); }
        if (stateTicks%3==0) { p.swingOffHand();  playCitizensPlayerAnimation(p,"ARM_SWING_OFFHAND"); }
    }
    private void animateHookPull(Player p) {
        if (stateTicks%8==0) playCitizensPlayerAnimation(p,"START_USE_MAINHAND_ITEM");
        if (stateTicks==castingDurationTicks-4) { p.swingMainHand(); playCitizensPlayerAnimation(p,"ARM_SWING"); }
    }
    private void animateWindBurst(Player p) {
        if (stateTicks%2==0) { p.swingMainHand(); playCitizensPlayerAnimation(p,"ARM_SWING"); }
        if (stateTicks%3==0) { p.swingOffHand();  playCitizensPlayerAnimation(p,"ARM_SWING_OFFHAND"); }
    }
    private void animateChaosDash(Player p) {
        if (stateTicks%3==0) { p.swingMainHand(); playCitizensPlayerAnimation(p,"ARM_SWING"); }
        if (stateTicks%5==0) { p.swingOffHand();  playCitizensPlayerAnimation(p,"ARM_SWING_OFFHAND"); }
    }


    private void spawnParrotCastingParticles(World world, Location base) {
        spawnGenericCastingRing(world, base);
        world.spawnParticle(Particle.DUST, base.clone().add(0, 1.3, 0), 14, 0.4, 0.4, 0.4, 0, JESTER_CYAN);
        world.spawnParticle(Particle.DUST, base.clone().add(0, 1.1, 0), 10, 0.35, 0.35, 0.35, 0, JESTER_GOLD);
        if (stateTicks % 6 == 0) world.playSound(base, Sound.ENTITY_PARROT_AMBIENT, 0.4F, 1.3F);
    }

    private void animateParrotBarrage(Player p) {
        if (stateTicks%4==0) { p.swingMainHand(); playCitizensPlayerAnimation(p,"ARM_SWING"); }
        if (stateTicks%7==0) { p.swingOffHand();  playCitizensPlayerAnimation(p,"ARM_SWING_OFFHAND"); }
    }
    private void executeAbility(ClownAbility ability) {
        if (ability == null) return;
        switch (ability) {
            case FIREWORK_VOLLEY -> castFireworkVolley();
            case BUNNY_SWARM     -> castBunnySwarm();
            case HOOK_PULL       -> castHookPull();
            case WIND_BURST      -> castWindBurst();
            case CHAOS_DASH      -> castChaosDash();
            case PARROT_BARRAGE  -> castParrotBarrage();
            case DUCK_INFERNO    -> castDuckInferno();
        }
    }

    private ClownAbility chooseAbility() {
        Player player = ensureTarget(48.0D); if (player == null) return null;
        List<AbilityWeight> weights = new ArrayList<>();
        for (ClownAbility ability : ClownAbility.values()) {
            if (!canUseAbility(ability)) continue;
            int weight = ability.getWeight();
            if (ability == ClownAbility.CHAOS_DASH && state == ClownState.MANIC) weight *= 2;
            if (ability == ClownAbility.BUNNY_SWARM && bunnySwarm.size() == 0) weight += 8;
            weights.add(new AbilityWeight(ability, weight));
        }
        int total = weights.stream().mapToInt(AbilityWeight::weight).sum();
        if (total <= 0) return null;
        int roll = random.nextInt(total), cursor = 0;
        for (AbilityWeight aw : weights) { cursor += aw.weight(); if (roll < cursor) return aw.ability(); }
        return weights.get(weights.size()-1).ability();
    }

    private boolean canUseAbility(ClownAbility ability) {
        if (ability == null || state == ClownState.DEAD) return false;
        if (cooldowns.getOrDefault(ability, 0) > 0) return false;
        return switch (ability) {
            case FIREWORK_VOLLEY, HOOK_PULL, WIND_BURST, CHAOS_DASH, PARROT_BARRAGE, DUCK_INFERNO -> target != null && target.isOnline() && !target.isDead();
            case BUNNY_SWARM -> bunnySwarm.size() < MAX_BUNNIES;
        };
    }

    private void setCooldown(ClownAbility ability, int ticks) { cooldowns.put(ability, Math.max(1, ticks)); }

    private void decrementCooldowns() {
        for (ClownAbility ability : ClownAbility.values()) {
            int cd = cooldowns.getOrDefault(ability, 0);
            if (cd > 0) cooldowns.put(ability, cd-1);
        }
    }

    private void castFireworkVolley() {
        Player player = ensureTarget(40.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) { returnToCombat(); return; }
        setCooldown(ClownAbility.FIREWORK_VOLLEY, FIREWORK_VOLLEY_COOLDOWN);
        Location launch = caster.getEyeLocation().add(caster.getLocation().getDirection().normalize().multiply(0.6));
        World world = launch.getWorld();
        if (world == null) { returnToCombat(); return; }
        world.playSound(launch, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0F, 0.8F);
        world.spawnParticle(Particle.FIREWORK, launch, 20, 0.3, 0.3, 0.3, 0.08);
        for (int i = 0; i < FIREWORK_COUNT; i++) {
            final int idx = i;
            BukkitRunnable lt = new BukkitRunnable() { public void run() { launchTrackedFirework(caster, player, launch.clone(), idx); } };
            ownedTasks.add(lt); lt.runTaskLater(plugin, idx * 6L);
        }
        returnToCombat();
    }

    private void launchTrackedFirework(LivingEntity caster, Player player, Location from, int spreadIndex) {
        if (player.isDead() || !player.isOnline()) return;
        World world = from.getWorld(); if (world == null) return;
        Vector dir = player.getEyeLocation().toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 0.0001) dir = new Vector(0,1,0);
        dir.normalize().add(new Vector((spreadIndex-1)*0.12+randomDouble(-0.06,0.06), randomDouble(-0.04,0.04), (spreadIndex-1)*0.05+randomDouble(-0.04,0.04))).normalize().multiply(1.4);
        Firework fw = world.spawn(from, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta(); meta.setPower(0);
        Color[] palette = {
            Color.fromRGB(255, 80, 160), Color.fromRGB(0, 210, 255),   Color.fromRGB(255, 210, 0),
            Color.fromRGB(255, 80, 0),   Color.fromRGB(120, 0, 255),    Color.fromRGB(0, 220, 100),
            Color.fromRGB(255, 255, 255), Color.fromRGB(255, 40, 40)
        };
        Color c1   = palette[random.nextInt(palette.length)];
        Color c2   = palette[random.nextInt(palette.length)];
        Color fade = palette[random.nextInt(palette.length)];
        FireworkEffect.Type fwType = random.nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.BURST;
        meta.addEffect(FireworkEffect.builder().withColor(c1).withColor(c2).withFade(fade).withFlicker().trail(true).with(fwType).build());
        fw.setFireworkMeta(meta); fw.setShotAtAngle(true); fw.setVelocity(dir);
        final int[] age = {0};
        BukkitRunnable tracker = new BukkitRunnable() {
            public void run() {
                age[0]++;
                if (!fw.isValid() || fw.isDead()) { cancel(); return; }
                fw.getWorld().spawnParticle(Particle.FIREWORK, fw.getLocation(), 3, 0.1, 0.1, 0.1, 0.01);
                if (player.isOnline() && !player.isDead() && player.getLocation().distanceSquared(fw.getLocation()) <= FIREWORK_HIT_RANGE*FIREWORK_HIT_RANGE) { detonateFirework(fw, player, caster); cancel(); return; }
                if (age[0] >= FIREWORK_LIFESPAN_TICKS) { fw.getWorld().spawnParticle(Particle.FIREWORK, fw.getLocation(), 30, 0.5, 0.5, 0.5, 0.08); fw.getWorld().playSound(fw.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.7F, 1.2F); fw.remove(); cancel(); }
            }
        };
        ownedTasks.add(tracker); tracker.runTaskTimer(plugin, 1L, 1L);
    }

    private void detonateFirework(Firework fw, Player player, LivingEntity caster) {
        Location loc = fw.getLocation(); World world = fw.getWorld(); fw.remove(); if (world == null) return;
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        world.spawnParticle(Particle.FIREWORK, loc, 50, 0.7, 0.7, 0.7, 0.10);
        world.spawnParticle(Particle.DUST, loc, 20, 0.5, 0.5, 0.5, 0, JESTER_GOLD);
        world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0F, 0.9F);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.5F, 1.5F);
        player.damage(FIREWORK_DAMAGE, caster);
    }

    private void castBunnySwarm() {
        LivingEntity caster = getLivingEntity(); if (caster == null) { returnToCombat(); return; }
        setCooldown(ClownAbility.BUNNY_SWARM, BUNNY_SWARM_COOLDOWN);
        Location loc = caster.getLocation(); World world = loc.getWorld();
        if (world != null) {
            world.playSound(loc, Sound.ENTITY_RABBIT_ATTACK, 1.0F, 0.4F);
            world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE, 0.9F, 0.6F);
            world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 30, 0.7, 0.6, 0.7, 0, JESTER_PINK);
        }
        bunnySwarm.spawn(loc, BUNNY_SWARM_COUNT); returnToCombat();
    }

    private void castHookPull() {
        Player player = ensureTarget(30.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) { returnToCombat(); return; }
        setCooldown(ClownAbility.HOOK_PULL, HOOK_PULL_COOLDOWN);
        Location eye = caster.getEyeLocation(); World world = eye.getWorld();
        if (world == null) { returnToCombat(); return; }
        world.playSound(eye, Sound.ENTITY_FISHING_BOBBER_THROW, 1.0F, 0.9F);
        world.spawnParticle(Particle.DUST, eye, 10, 0.3, 0.3, 0.3, 0, JESTER_CYAN);
        Vector dir = player.getEyeLocation().toVector().subtract(eye.toVector());
        if (dir.lengthSquared() < 0.0001) { returnToCombat(); return; }
        dir.normalize().multiply(1.6);
        FishHook hook = caster.launchProjectile(FishHook.class, dir);
        hook.setCustomNameVisible(false); hook.setMaxWaitTime(0); hook.setMinWaitTime(0);
        final int[] age = {0};
        BukkitRunnable tracker = new BukkitRunnable() {
            public void run() {
                age[0]++;
                if (!hook.isValid() || hook.isDead()) { cancel(); return; }
                hook.getWorld().spawnParticle(Particle.DUST, hook.getLocation(), 2, 0.05, 0.05, 0.05, 0, JESTER_CYAN);
                if (player.isOnline() && !player.isDead() && player.getLocation().distanceSquared(hook.getLocation()) <= HOOK_HIT_RANGE*HOOK_HIT_RANGE) { hookHit(hook, player, caster); cancel(); return; }
                if (age[0] >= HOOK_LIFESPAN_TICKS) { hook.remove(); cancel(); }
            }
        };
        ownedTasks.add(tracker); tracker.runTaskTimer(plugin, 1L, 1L); returnToCombat();
    }

    private void hookHit(FishHook hook, Player player, LivingEntity caster) {
        Location hookLoc = hook.getLocation(); World world = hook.getWorld(); hook.remove(); if (world == null) return;
        world.playSound(hookLoc, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0F, 0.8F);
        world.spawnParticle(Particle.DUST, hookLoc, 15, 0.4, 0.4, 0.4, 0, JESTER_CYAN);
        Vector push = player.getLocation().toVector().subtract(caster.getLocation().toVector());
        if (push.lengthSquared() < 0.01) push = new Vector(randomDouble(-1,1), 0, randomDouble(-1,1));
        push.normalize().multiply(HOOK_PUSH_STRENGTH); push.setY(0.12);
        player.setVelocity(push); world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 0.6F, 0.8F);
    }

    private void castWindBurst() {
        Player player = ensureTarget(28.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) { returnToCombat(); return; }
        setCooldown(ClownAbility.WIND_BURST, WIND_BURST_COOLDOWN);
        Location from = caster.getEyeLocation(); World world = from.getWorld();
        if (world == null) { returnToCombat(); return; }
        world.playSound(from, Sound.ENTITY_BREEZE_WIND_BURST, 1.0F, 1.0F);
        world.spawnParticle(Particle.CLOUD, from, 20, 0.5, 0.5, 0.5, 0.03);
        Vector baseDir = player.getLocation().toVector().subtract(from.toVector());
        if (baseDir.lengthSquared() < 0.0001) { returnToCombat(); return; }
        baseDir.normalize();
        for (int i = 0; i < WIND_CHARGE_COUNT; i++) {
            Vector dir = baseDir.clone().add(new Vector(randomDouble(-0.3,0.3), randomDouble(-0.1,0.25), randomDouble(-0.3,0.3))).normalize().multiply(1.3+randomDouble(-0.2,0.2));
            try { WindCharge wc = world.spawn(from.clone().add(randomDouble(-0.3,0.3), 0, randomDouble(-0.3,0.3)), WindCharge.class); wc.setVelocity(dir); }
            catch (Exception ex) { plugin.getLogger().fine("WindCharge skipped: "+ex.getMessage()); }
            world.spawnParticle(Particle.CLOUD, from, 5, 0.2, 0.2, 0.2, 0.02);
        }
        player.damage(WIND_BURST_DAMAGE, caster); returnToCombat();
    }

    private void castChaosDash() {
        Player player = ensureTarget(40.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) { returnToCombat(); return; }
        setCooldown(ClownAbility.CHAOS_DASH, CHAOS_DASH_COOLDOWN);
        Location dest = player.getLocation().clone().add(randomDouble(-1.5,1.5), 0, randomDouble(-1.5,1.5));
        dest = findSafeGroundLocation(dest);
        dest.setYaw(player.getLocation().getYaw()+180f); dest.setPitch(0f);
        Location origin = getCurrentLocation(); World world = origin.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.FIREWORK, origin, 40, 0.6, 0.8, 0.6, 0.10);
            world.spawnParticle(Particle.DUST, origin.clone().add(0,1,0), 25, 0.5, 0.7, 0.5, 0, JESTER_PINK);
            world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 1.5F);
        }
        npc.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        World dw = dest.getWorld();
        if (dw != null) {
            dw.spawnParticle(Particle.FIREWORK, dest, 60, 0.8, 0.8, 0.8, 0.12);
            dw.spawnParticle(Particle.DUST, dest.clone().add(0,1,0), 30, 0.6, 0.8, 0.6, 0, JESTER_GOLD);
            dw.spawnParticle(Particle.DUST, dest.clone().add(0,1,0), 20, 0.5, 0.7, 0.5, 0, JESTER_CYAN);
            dw.playSound(dest, Sound.ENTITY_WITCH_CELEBRATE, 1.0F, 0.4F);
            dw.playSound(dest, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8F, 0.6F);
        }
        player.damage(CHAOS_DASH_DAMAGE, caster); returnToCombat();
    }


    private void castParrotBarrage() {
        Player player = ensureTarget(40.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) { returnToCombat(); return; }
        setCooldown(ClownAbility.PARROT_BARRAGE, PARROT_BARRAGE_COOLDOWN);
        Location origin = caster.getLocation(); World world = origin.getWorld();
        if (world == null) { returnToCombat(); return; }
        world.playSound(origin, Sound.ENTITY_PARROT_IMITATE_BLAZE, 0.9F, 1.1F);
        world.playSound(origin, Sound.ENTITY_PARROT_AMBIENT, 0.7F, 1.5F);
        world.spawnParticle(Particle.DUST, origin.clone().add(0,1.1,0), 25, 0.7, 0.7, 0.7, 0, JESTER_CYAN);

        // Single parrot summoned
        Location spawn = origin.clone().add(randomDouble(-1.2, 1.2), 1.2 + randomDouble(0.3, 0.8), randomDouble(-1.2, 1.2));
        Parrot parrot = world.spawn(spawn, Parrot.class);
        parrot.setSilent(true);
        parrot.setInvulnerable(true);
        parrot.setCollidable(false);
        parrot.setAI(false);
        parrot.setCustomName(null);
        parrot.setCustomNameVisible(false);
        world.playSound(spawn, Sound.ENTITY_PARROT_AMBIENT, 0.8F, 1.3F);

        BukkitRunnable attackTask = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks++;
                if (!parrot.isValid() || parrot.isDead() || isDead() || ticks > 120) {
                    if (parrot.isValid()) parrot.remove();
                    cancel();
                    return;
                }
                if (player.isOnline() && !player.isDead()) {
                    Location eye = player.getEyeLocation();
                    Vector flight = eye.toVector().subtract(parrot.getLocation().toVector());
                    if (flight.lengthSquared() > 0.0001) {
                        Location newLoc = parrot.getLocation().add(flight.normalize().multiply(0.30));
                        newLoc.setYaw(getYawTowards(newLoc, eye));
                        newLoc.setPitch(getPitchTowards(newLoc, eye));
                        parrot.teleport(newLoc);
                    }
                    if (ticks % 20 == 0) {
                        parrot.getWorld().playSound(parrot.getLocation(), Sound.ENTITY_PARROT_AMBIENT, 0.35F, 1.6F);
                    }
                    if (ticks % 18 == 0) {
                        SmallFireball fb = parrot.launchProjectile(SmallFireball.class);
                        Vector dir = eye.toVector().subtract(parrot.getEyeLocation().toVector());
                        if (dir.lengthSquared() < 0.0001) dir = new Vector(0, 0, 1);
                        fb.setVelocity(dir.normalize().multiply(0.9));
                        fb.setYield(0.0F);
                        fb.setIsIncendiary(false);
                        fb.setShooter(caster);
                        parrot.getWorld().playSound(parrot.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.45F, 1.5F);
                    }
                }
            }
        };
        ownedTasks.add(attackTask);
        attackTask.runTaskTimer(plugin, 1L, 1L);
        returnToCombat();
    }

    private void castDuckInferno() {
        Player player = ensureTarget(40.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) { returnToCombat(); return; }
        setCooldown(ClownAbility.DUCK_INFERNO, DUCK_INFERNO_COOLDOWN);
        Location spawn = findSafeGroundLocation(caster.getLocation().clone().add(randomDouble(-1.0, 1.0), 0, randomDouble(-1.0, 1.0)));
        World world = spawn.getWorld();
        if (world == null) { returnToCombat(); return; }
        Chicken duck = world.spawn(spawn, Chicken.class);
        duck.setCustomName(null);
        duck.setCustomNameVisible(false);
        duck.setSilent(true);
        duck.setCollidable(false);
        // Jack-in-the-box style spawn sound
        world.playSound(spawn, Sound.BLOCK_NOTE_BLOCK_PLING, 0.9F, 1.8F);
        world.playSound(spawn, Sound.ENTITY_CHICKEN_EGG,     0.7F, 0.6F);

        BukkitRunnable duckTask = new BukkitRunnable() {
            int ticks = 0;
            @Override
            public void run() {
                ticks++;
                if (!duck.isValid() || duck.isDead() || isDead() || ticks > 90) {
                    explodeDuck(duck.getLocation(), caster);
                    if (duck.isValid()) duck.remove();
                    cancel();
                    return;
                }
                if (player.isOnline() && !player.isDead()) {
                    Vector step = player.getLocation().toVector().subtract(duck.getLocation().toVector());
                    if (step.lengthSquared() > 0.01) {
                        Location newDuckLoc = duck.getLocation().add(step.normalize().multiply(0.25));
                        newDuckLoc.setYaw(getYawTowards(newDuckLoc, player.getLocation()));
                        duck.teleport(newDuckLoc);
                    }
                }
                Location trail = duck.getLocation().clone().add(0, 0.05, 0);
                duck.getWorld().spawnParticle(Particle.FLAME,           trail,                       14, 0.20, 0.12, 0.20, 0.025);
                duck.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, trail.clone().add(0,0.15,0),  4, 0.12, 0.10, 0.12, 0.015);
                duck.getWorld().spawnParticle(Particle.SMOKE,           trail,                        3, 0.15, 0.08, 0.15, 0.020);
                if (ticks % 10 == 0) {
                    for (Player p : duck.getWorld().getPlayers()) {
                        if (!p.isDead() && p.getLocation().distanceSquared(trail) <= 1.0D) {
                            p.setFireTicks(Math.max(p.getFireTicks(), 20));
                            p.damage(0.5D, caster);
                        }
                    }
                }
                if (ticks < 50 && ticks % 8 == 0) {
                    duck.getWorld().playSound(duck.getLocation(), Sound.BLOCK_NOTE_BLOCK_SNARE, 0.4F, 1.5F + ticks * 0.01F);
                }
            }
        };
        ownedTasks.add(duckTask);
        duckTask.runTaskTimer(plugin, 1L, 1L);
        returnToCombat();
    }

    private void explodeDuck(Location location, LivingEntity caster) {
        World world = location.getWorld();
        if (world == null) return;
        world.spawnParticle(Particle.FLAME, location.clone().add(0, 0.3, 0), 45, 0.8, 0.4, 0.8, 0.02);
        world.spawnParticle(Particle.SMOKE, location.clone().add(0, 0.2, 0), 35, 0.8, 0.3, 0.8, 0.04);
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 0.8F, 1.4F);
        for (org.bukkit.entity.Entity nearby : world.getNearbyEntities(location, 3.5, 3.5, 3.5)) {
            if (!(nearby instanceof Player p)) continue;
            p.damage(4.5D, caster);
            p.setFireTicks(Math.max(p.getFireTicks(), 60));
        }
    }

    private Location findSafeGroundLocation(Location desired) {
        World world = desired.getWorld();
        if (world == null) return desired;
        int x = desired.getBlockX();
        int z = desired.getBlockZ();
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location base = new Location(world, x + 0.5, y, z + 0.5, desired.getYaw(), desired.getPitch());
        for (int i = 0; i < 4; i++) {
            Location feet = base.clone().add(0, i, 0);
            Location head = feet.clone().add(0, 1, 0);
            if (feet.getBlock().isPassable() && head.getBlock().isPassable()) return feet;
        }
        return base;
    }

    private void castAnvilPrank() {
        Player player = ensureTarget(35.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) { return; }
        Location spawn = player.getLocation().clone().add(0, 9.0, 0);
        World world = spawn.getWorld();
        if (world == null) { return; }
        FallingBlock anvil = world.spawnFallingBlock(spawn, Material.ANVIL.createBlockData());
        anvil.setDropItem(false);
        anvil.setHurtEntities(true);
        try { anvil.setCancelDrop(true); } catch (NoSuchMethodError ignored) {}
        world.playSound(spawn, Sound.BLOCK_ANVIL_PLACE, 0.65F, 0.8F);

        BukkitRunnable task = new BukkitRunnable() {
            int age = 0;
            @Override
            public void run() {
                age++;
                if (!anvil.isValid() || anvil.isDead() || age > 60 || anvil.isOnGround()) {
                    Location impact = anvil.getLocation();
                    if (anvil.isValid()) anvil.remove();
                    world.spawnParticle(Particle.BLOCK, impact, 20, 0.35, 0.2, 0.35, Material.ANVIL.createBlockData());
                    world.playSound(impact, Sound.BLOCK_ANVIL_LAND, 0.9F, 1.0F);
                    cancel();
                    return;
                }
            }
        };
        ownedTasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void playTrollTune(Location at) {
        World world = at.getWorld();
        if (world == null) return;
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 0.35F, 0.7F + random.nextFloat() * 1.3F);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT,  0.3F,  0.7F + random.nextFloat() * 1.3F);
        // Rotate through the full prank suite so all pranks actually fire in combat
        switch (random.nextInt(6)) {
            case 0 -> castAnvilPrank();
            case 1 -> castInventoryShufflePrank();
            case 2 -> castFakePanicPrank();
            case 3 -> castGiftPrank();
            case 4 -> castFreezeAudiencePrank();
            case 5 -> { if (baitTrapCooldown <= 0) { castBaitTrap(); baitTrapCooldown = BAIT_TRAP_COOLDOWN_MAX; } }
        }
    }

    private void castInventoryShufflePrank() {
        Player player = ensureTarget(30.0D);
        if (player == null) return;
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        ItemStack[] hotbar = new ItemStack[9];
        for (int i = 0; i < 9; i++) hotbar[i] = inv.getItem(i);

        if (random.nextBoolean()) {
            ItemStack first = hotbar[0];
            for (int i = 0; i < 8; i++) hotbar[i] = hotbar[i + 1];
            hotbar[8] = first;
        } else {
            int swaps = 2 + random.nextInt(3);
            for (int i = 0; i < swaps; i++) {
                int a = random.nextInt(9);
                int b = random.nextInt(9);
                if (a == b) continue;
                ItemStack tmp = hotbar[a];
                hotbar[a] = hotbar[b];
                hotbar[b] = tmp;
            }
        }

        for (int i = 0; i < 9; i++) inv.setItem(i, hotbar[i]);
        player.updateInventory();
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BANJO, 0.75F, 1.8F);
    }

    private void castFakePanicPrank() {
        Player player = ensureTarget(34.0D);
        if (player == null) return;
        Location center = player.getLocation().add(0, 1.0, 0);
        World world = player.getWorld();
        world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.1F, 0.8F);
        world.playSound(center, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.9F, 1.1F);
        world.spawnParticle(Particle.CRIT, center, 34, 0.45, 0.55, 0.45, 0.15);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, center, 22, 0.35, 0.45, 0.35, 0.05);
    }

    private void castGiftPrank() {
        Player player = ensureTarget(32.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        World world = caster.getWorld();
        ItemStack bait = new ItemStack(random.nextBoolean() ? Material.GOLDEN_APPLE : Material.EMERALD, 1);
        Item item = world.dropItem(caster.getEyeLocation(), bait);
        item.setPickupDelay(12);
        item.setUnlimitedLifetime(false);
        item.setVelocity(player.getLocation().toVector().subtract(caster.getLocation().toVector()).normalize().multiply(0.45).setY(0.25));
        item.setMetadata("bloodmoon-clown-gift", new FixedMetadataValue(plugin, npc.getId()));
        world.playSound(caster.getLocation(), Sound.ENTITY_CHICKEN_EGG, 0.7F, 1.4F);

        BukkitRunnable cleanup = new BukkitRunnable() {
            @Override
            public void run() {
                if (item.isValid()) item.remove();
            }
        };
        ownedTasks.add(cleanup);
        cleanup.runTaskLater(plugin, 20L * 12L);
    }

    private void castFreezeAudiencePrank() {
        Player player = ensureTarget(28.0D);
        if (player == null) return;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 255, true, true, true));
        World world = player.getWorld();
        world.spawnParticle(Particle.WITCH, player.getLocation().add(0, 1.1, 0), 18, 0.35, 0.45, 0.35, 0.04);

        BukkitRunnable laughTrack = new BukkitRunnable() {
            int burst = 0;
            @Override
            public void run() {
                burst++;
                if (burst > 4 || !player.isOnline() || player.isDead()) {
                    cancel();
                    return;
                }
                Location base = player.getLocation();
                world.playSound(base, Sound.ENTITY_WITCH_CELEBRATE, 0.85F, 0.65F + (burst * 0.05F));
                world.playSound(base, Sound.BLOCK_NOTE_BLOCK_SNARE, 0.6F, 1.1F + (burst * 0.06F));
                world.playSound(base, Sound.BLOCK_NOTE_BLOCK_HAT, 0.55F, 1.6F);
            }
        };
        ownedTasks.add(laughTrack);
        laughTrack.runTaskTimer(plugin, 0L, 5L);
    }
    private void returnToCombat() {
        if (state == ClownState.CASTING) {
            if (random.nextDouble() < 0.70D) {
                state = ClownState.TAUNTING;
                stateTicks = 0;
                tauntDuration = 60 + random.nextInt(40);
            } else {
                state = stateBeforeCasting;
                stateTicks = 0;
            }
        }
    }

    private void tickTaunting() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) { state = stateBeforeCasting; stateTicks = 0; return; }
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        if (stateTicks == 1) {
            npc.getNavigator().cancelNavigation();
            playVariedLaugh(loc, true);
            if (world != null) {
                world.spawnParticle(Particle.NOTE, loc.clone().add(0,1.5,0), 12, 0.6, 0.3, 0.6, 0.6);
                world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 18, 0.6, 0.6, 0.6, 0, JESTER_PINK);
            }
        }
        if (stateTicks % 20 == 0 && stateTicks < tauntDuration - 20 && world != null) {
            playVariedLaugh(loc, stateTicks < tauntDuration / 2);
            world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 6, 0.4, 0.4, 0.4, 0, JESTER_GOLD);
            world.spawnParticle(Particle.NOTE, loc.clone().add(0,1.5,0), 3, 0.5, 0.3, 0.5, 0.4);
        }
        if (stateTicks == tauntDuration - 12) doTauntWarp();
        if (target != null && target.isOnline() && !target.isDead()) npc.faceLocation(target.getEyeLocation());
        if (stateTicks >= tauntDuration) { state = stateBeforeCasting; stateTicks = 0; }
    }

    private void doTauntWarp() {
        Location origin = getCurrentLocation();
        Location dest = findSafeGroundLocation(origin.clone().add(randomDouble(-7,7), 0, randomDouble(-7,7)));
        World w = origin.getWorld();
        if (w != null) {
            w.spawnParticle(Particle.FIREWORK, origin, 30, 0.5, 0.8, 0.5, 0.1);
            w.spawnParticle(Particle.DUST, origin.clone().add(0,1,0), 20, 0.5, 0.7, 0.5, 0, JESTER_CYAN);
            w.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 1.6F);
        }
        npc.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        World dw = dest.getWorld();
        if (dw != null) {
            dw.spawnParticle(Particle.FIREWORK, dest, 25, 0.4, 0.7, 0.4, 0.08);
            dw.spawnParticle(Particle.DUST, dest.clone().add(0,1,0), 15, 0.4, 0.6, 0.4, 0, JESTER_PINK);
            dw.playSound(dest, Sound.ENTITY_WITCH_CELEBRATE, 0.9F, 0.5F);
        }
    }

    private Player ensureTarget(double maxDistance) {
        if (target != null && target.isOnline() && !target.isDead() && target.getLocation().distanceSquared(getCurrentLocation()) <= maxDistance*maxDistance) return target;
        target = findNearestPlayer(getCurrentLocation(), maxDistance); return target;
    }

    private void updateLastKnownLocation() { LivingEntity e = getLivingEntity(); if (e != null) lastKnownLocation = e.getLocation().clone(); }
    private void setNpcVisible() { if (!npc.isSpawned()) npc.spawn(lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone()); }
    private void setNavigationSpeed(float speed) { if (npc.isSpawned()) npc.getNavigator().getDefaultParameters().speedModifier(speed); }
    private LivingEntity getLivingEntity() { return npc.isSpawned() && npc.getEntity() instanceof LivingEntity living ? living : null; }

    private void cancelControllerOnly() { if (controllerTask != null) { controllerTask.cancel(); controllerTask = null; } }
    private void cancelOwnedTasks() {
        for (BukkitRunnable task : List.copyOf(ownedTasks)) { try { task.cancel(); } catch (IllegalStateException ignored) {} }
        ownedTasks.clear();
    }

    private void playVariedLaugh(Location location, boolean loud) {
        if (location.getWorld() == null) return;
        float vol = loud ? 1.1F : 0.7F;
        switch (random.nextInt(5)) {
            case 0 -> {
                location.getWorld().playSound(location, Sound.ENTITY_WITCH_CELEBRATE, vol,       0.3F);
                location.getWorld().playSound(location, Sound.ENTITY_GHAST_AMBIENT,   vol*0.4F,  0.45F);
                location.getWorld().playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS,  vol*0.6F,  0.25F);
            }
            case 1 -> {
                location.getWorld().playSound(location, Sound.ENTITY_WITCH_CELEBRATE, vol,       0.55F);
                location.getWorld().playSound(location, Sound.ENTITY_ENDERMAN_SCREAM, vol*0.4F,  0.8F);
                location.getWorld().playSound(location, Sound.BLOCK_NOTE_BLOCK_BELL,  vol*0.5F,  0.6F);
            }
            case 2 -> {
                location.getWorld().playSound(location, Sound.ENTITY_WITCH_AMBIENT,     vol*0.9F, 0.4F);
                location.getWorld().playSound(location, Sound.ENTITY_ENDER_DRAGON_GROWL, vol*0.3F, 1.8F);
            }
            case 3 -> {
                location.getWorld().playSound(location, Sound.ENTITY_WITCH_CELEBRATE, vol*0.8F, 0.7F);
                location.getWorld().playSound(location, Sound.BLOCK_NOTE_BLOCK_BANJO, vol*0.7F, 1.8F);
                location.getWorld().playSound(location, Sound.ENTITY_WITCH_CELEBRATE, vol*0.5F, 0.25F);
            }
            case 4 -> {
                location.getWorld().playSound(location, Sound.ENTITY_GHAST_SCREAM,    vol*0.35F, 0.6F);
                location.getWorld().playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, vol*0.8F,  1.4F);
                location.getWorld().playSound(location, Sound.ENTITY_WITCH_CELEBRATE, vol*0.7F,  0.4F);
            }
        }
    }

    private float getYawTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private float getPitchTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        return (float) (-Math.toDegrees(Math.atan2(dy, d)));
    }

    private void castBaitTrap() {
        Player player = ensureTarget(32.0D); LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        World world = caster.getWorld();
        Material[] baits = { Material.DIAMOND, Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE, Material.EMERALD, Material.DIAMOND_SWORD };
        ItemStack baitItem = new ItemStack(baits[random.nextInt(baits.length)], 1);
        Vector toward = player.getLocation().toVector().subtract(caster.getLocation().toVector()).normalize().multiply(2.5D);
        Location dropLoc = findSafeGroundLocation(caster.getLocation().clone().add(toward));
        Item item = world.dropItem(dropLoc, baitItem);
        item.setPickupDelay(20);
        item.setUnlimitedLifetime(true);
        item.setMetadata("bloodmoon-clown-bait", new FixedMetadataValue(plugin, npc.getId()));
        world.playSound(dropLoc, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9F, 1.6F);
        world.spawnParticle(Particle.DUST, dropLoc.clone().add(0,0.5,0), 20, 0.3, 0.3, 0.3, 0, JESTER_GOLD);
        BukkitRunnable baitTask = new BukkitRunnable() {
            int age = 0;
            @Override
            public void run() {
                age++;
                if (!item.isValid() || item.isDead() || age > 20*20 || isDead()) {
                    if (item.isValid()) item.remove();
                    cancel(); return;
                }
                if (age % 3 == 0) {
                    item.getWorld().spawnParticle(Particle.DUST, item.getLocation().clone().add(0,0.35,0), 2, 0.12, 0.12, 0.12, 0, JESTER_GOLD);
                    if (age % 9 == 0) item.getWorld().spawnParticle(Particle.DUST, item.getLocation().clone().add(0,0.35,0), 1, 0.10, 0.10, 0.10, 0, JESTER_PINK);
                }
                if (age % 20 == 0 && player.isOnline() && !player.isDead()) {
                    if (player.getLocation().distanceSquared(item.getLocation()) <= 2.25D && random.nextDouble() < 0.30D) {
                        triggerBaitExplosion(item.getLocation(), player, caster);
                        item.remove(); cancel();
                    }
                }
            }
        };
        ownedTasks.add(baitTask);
        baitTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void triggerBaitExplosion(Location loc, Player player, LivingEntity caster) {
        World world = loc.getWorld(); if (world == null) return;
        world.spawnParticle(Particle.FIREWORK, loc.clone().add(0,0.5,0), 60, 0.8, 0.8, 0.8, 0.1);
        world.spawnParticle(Particle.DUST,     loc.clone().add(0,0.5,0), 30, 0.5, 0.5, 0.5, 0, JESTER_GOLD);
        world.spawnParticle(Particle.DUST,     loc.clone().add(0,0.5,0), 25, 0.5, 0.5, 0.5, 0, JESTER_PINK);
        world.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0F, 0.7F);
        world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE, 0.9F, 0.4F);
        if (player.isOnline() && !player.isDead() && player.getLocation().distanceSquared(loc) <= 9.0D) {
            player.damage(4.5D, caster);
            Vector kb = player.getLocation().toVector().subtract(loc.toVector());
            if (kb.lengthSquared() > 0.01D) kb.normalize().multiply(1.0D);
            kb.setY(0.5D); player.setVelocity(kb);
        }
    }

    public void triggerBaitPickup(Player player) {
        if (isDead()) return;
        Vector behind = player.getLocation().getDirection().normalize().multiply(-2.0D);
        Location dest = findSafeGroundLocation(player.getLocation().clone().add(behind));
        dest.setYaw(player.getLocation().getYaw()); dest.setPitch(0f);
        npc.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        Location loc = dest; World w = loc.getWorld();
        if (w != null) {
            w.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE, 1.2F, 0.25F);
            w.playSound(loc, Sound.ENTITY_GHAST_SCREAM,    0.5F, 0.55F);
            w.playSound(loc, Sound.ENTITY_ENDERMAN_SCREAM, 0.4F, 0.7F);
            w.spawnParticle(Particle.FIREWORK, loc, 50, 0.8, 0.8, 0.8, 0.12);
            w.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 30, 0.6, 0.6, 0.6, 0, JESTER_GOLD);
        }
        BukkitRunnable hit = new BukkitRunnable() {
            @Override public void run() {
                if (isDead() || !player.isOnline() || player.isDead()) return;
                LivingEntity e = getLivingEntity(); if (e == null) return;
                npc.faceLocation(player.getEyeLocation());
                player.damage(8.0D, e);
                player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0,1,0), 20, 0.5, 0.5, 0.5, 0.1);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
            }
        };
        ownedTasks.add(hit); hit.runTaskLater(plugin, 5L);
    }

    private void playCreepyLaugh(Location location, boolean loud) {
        float volume = loud ? 1.1F : 0.65F;
        location.getWorld().playSound(location, Sound.ENTITY_WITCH_CELEBRATE, volume, 0.35F);
        location.getWorld().playSound(location, Sound.ENTITY_GHAST_AMBIENT, loud?0.4F:0.2F, 0.5F);
        location.getWorld().playSound(location, Sound.BLOCK_NOTE_BLOCK_BASS, loud?0.7F:0.4F, 0.25F);
        if (loud) location.getWorld().playSound(location, Sound.ENTITY_ENDERMAN_STARE, 0.35F, 0.7F);
    }

    @SuppressWarnings({"rawtypes","unchecked"})
    private void playCitizensPlayerAnimation(Player player, String animationName) {
        try {
            Class<? extends Enum> ac = Class.forName("net.citizensnpcs.util.PlayerAnimation").asSubclass(Enum.class);
            Enum animation = Enum.valueOf(ac, animationName);
            ac.getMethod("play", Player.class).invoke(animation, player);
        } catch (ReflectiveOperationException ignored) {}
    }

    private void spawnDeathExplosion(World world, Location loc) {
        world.spawnParticle(Particle.FIREWORK, loc, 120, 1.5, 1.5, 1.5, 0.14);
        world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 60, 1.0, 1.0, 1.0, 0, JESTER_PINK);
        world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 50, 0.9, 0.9, 0.9, 0, JESTER_GOLD);
        world.spawnParticle(Particle.DUST, loc.clone().add(0,1,0), 40, 0.8, 0.8, 0.8, 0, JESTER_CYAN);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2);
    }

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.70) world.dropItemNaturally(location, new ItemStack(Material.FIREWORK_ROCKET, random.nextInt(4)+2));
        if (random.nextDouble() <= 0.60) world.dropItemNaturally(location, new ItemStack(Material.SUGAR, random.nextInt(4)+1));
        if (random.nextDouble() <= 0.50) world.dropItemNaturally(location, new ItemStack(Material.RABBIT_FOOT, random.nextInt(2)+1));
        if (random.nextDouble() <= 0.40) world.dropItemNaturally(location, new ItemStack(Material.SLIME_BALL, random.nextInt(3)+1));
        if (random.nextDouble() <= 0.30) world.dropItemNaturally(location, new ItemStack(Material.FISHING_ROD, 1));
        if (random.nextDouble() <= 0.15) world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));
        if (random.nextDouble() <= 0.05) world.dropItemNaturally(location, new ItemStack(Material.NETHER_STAR, 1));
        ExperienceOrb orb = world.spawn(location, ExperienceOrb.class);
        orb.setExperience(random.nextInt(21)+25);
    }

    private record AbilityWeight(ClownAbility ability, int weight) {}
}
