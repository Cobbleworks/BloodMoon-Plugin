package com.cobbleworks.bloodmoon.mobs;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.traits.ScarecrowTrait;
import java.util.*;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Bat;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class ScarecrowNPC {

    // ─── Appearance constants ─────────────────────────────────────────────────
    private static final Particle.DustOptions DUST_CYAN   = new Particle.DustOptions(Color.fromRGB(  0, 200, 200), 1.1F);
    private static final Particle.DustOptions DUST_BLACK  = new Particle.DustOptions(Color.fromRGB( 20,   0,  20), 1.3F);
    private static final Particle.DustOptions DUST_DARK   = new Particle.DustOptions(Color.fromRGB( 20,  70,  70), 1.0F);
    private static final Particle.DustOptions DUST_RED    = new Particle.DustOptions(Color.fromRGB(200,  10,  10), 1.0F);
    private static final Particle.DustOptions DUST_GREEN  = new Particle.DustOptions(Color.fromRGB( 70, 185, 110), 1.15F);
    private static final Particle.DustOptions DUST_WHITE  = new Particle.DustOptions(Color.fromRGB(235, 245, 235), 1.05F);
    private static final Particle.DustOptions DUST_ORANGE = new Particle.DustOptions(Color.fromRGB(200, 100,  20), 1.2F);
    private static final Particle.DustOptions DUST_AMBER  = new Particle.DustOptions(Color.fromRGB(220, 140,  20), 1.2F);
    private static final Particle.DustOptions DUST_BONE   = new Particle.DustOptions(Color.fromRGB(215, 210, 190), 1.1F);

    // ─── Enums ────────────────────────────────────────────────────────────────
    private enum ScarecrowState { STALKING, COMBAT, CASTING, DEAD }
    private enum ScarecrowPhase { WATCHER, HARVESTER, JUDGEMENT }
    private static final int STALKING_TICKS = 40;
    private static final long FEAR_MARK_DURATION_MS = 7000L;
    private enum ScarecrowAbility {
        FEAR, DRAIN, BLOOM, REAP, FIREBALLS, PHANTOM, CROWSTORM, DARK_WIND, HIGH_JUMP
    }

    // ─── Fields ───────────────────────────────────────────────────────────────
    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<ScarecrowAbility, Integer> cooldowns = new EnumMap<>(ScarecrowAbility.class);
    private final Map<ScarecrowAbility, Integer> abilityUseCounts = new EnumMap<>(ScarecrowAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private final List<Bat> stormBats = new ArrayList<>();
    private final List<Location> witherRoseLocations = new ArrayList<>();
    private final Map<UUID, Long> fearMarks = new HashMap<>();
    private final Map<Location, Integer> cinderPatches = new HashMap<>();

    private ScarecrowState state = ScarecrowState.STALKING;
    private ScarecrowPhase phase = ScarecrowPhase.WATCHER;
    private ScarecrowState beforeCast = ScarecrowState.COMBAT;
    private ScarecrowAbility pending;
    private ScarecrowAbility forcedComboAbility;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private int stateTicks;
    private int globalTick;
    private int castTicks;
    private int postCastRecoveryTicks;
    private boolean cleaned;
    private boolean deathStarted;
    private boolean harvesterTriggered;
    private boolean judgementTriggered;

    // Fearmonger: each wheat crop consumed grants +10% damage
    private int fearmongerStacks = 0;

    // ─── Constructor ──────────────────────────────────────────────────────────
    public ScarecrowNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

    // ─── Public API ───────────────────────────────────────────────────────────
    public NPC getNpc() { return npc; }

    public boolean isDead() { return state == ScarecrowState.DEAD || cleaned || deathStarted; }

    public Location getCurrentLocation() {
        LivingEntity e = getLivingEntity();
        return e != null ? e.getLocation() : (lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone());
    }

    public double getCurrentHealth() {
        LivingEntity e = getLivingEntity();
        return e == null ? plugin.getConfigManager().getScarecrowHealth() : Math.max(0.0D, e.getHealth());
    }

    public double getMaximumHealth() {
        LivingEntity e = getLivingEntity();
        if (e == null) {
            return plugin.getConfigManager().getScarecrowHealth();
        }
        var attr = e.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? plugin.getConfigManager().getScarecrowHealth() : Math.max(1.0D, attr.getValue());
    }

    public void onTraitTick() {
        LivingEntity e = getLivingEntity();
        if (e != null) lastKnownLocation = e.getLocation();
    }

    public void onNpcSpawn() {
        LivingEntity e = getLivingEntity();
        if (e != null) applyConfiguredHealth(e);
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        event.setCancelled(true);
        if (!(event.getTarget() instanceof Player player) || state == ScarecrowState.DEAD) return;
        target = player;
    }

    public void onTakeDamage() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location loc   = entity.getLocation();
        World    world = entity.getWorld();
        world.playSound(loc, Sound.ENTITY_SKELETON_HURT, 0.9F, 0.8F + random.nextFloat() * 0.25F);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 10, 0.3D, 0.35D, 0.3D, 0D, DUST_BONE);
        world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.1D, 0),  4, 0.2D, 0.2D,  0.2D, 0D, DUST_CYAN);
        world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 1.0D, 0), 5, 0.2D, 0.15D, 0.2D, Material.HAY_BLOCK.createBlockData());
        if (state == ScarecrowState.STALKING) {
            state      = ScarecrowState.COMBAT;
            stateTicks = 0;
        }
    }

    public void triggerSnapFromDamage() {
        if (state == ScarecrowState.STALKING) {
            state      = ScarecrowState.COMBAT;
            stateTicks = 0;
        }
    }

    public void startDeathSequence() {
        if (deathStarted) return;
        deathStarted = true;
        state = ScarecrowState.DEAD;
        cancelControllerOnly();
        cancelTasks();
        stormBats.forEach(b -> { if (b.isValid()) b.remove(); });
        stormBats.clear();
        cleanupWitherRoses();
        Location death = getCurrentLocation();
        World world = death.getWorld();
        if (world != null) {
            world.playSound(death, Sound.ENTITY_SKELETON_DEATH, 1.0F, 0.8F);
            world.spawnParticle(Particle.DUST, death.clone().add(0, 1, 0), 30, 0.6, 0.7, 0.6, 0, DUST_CYAN);
            world.spawnParticle(Particle.SMOKE, death.clone().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.04);
            dropLoot(world, death);
            if (random.nextDouble() <= Math.max(0.0D, plugin.getBloodMoonManager().getRewardMultiplier() - 1.0D)) {
                dropLoot(world, death);
            }
            ExperienceOrb orb = world.spawn(death.clone().add(0, 0.25, 0), ExperienceOrb.class);
            orb.setExperience((int) Math.max(1.0D,
                (40 + random.nextInt(20)) * plugin.getBloodMoonManager().getExpMultiplier()));
        }
        BukkitRunnable cleanupTask = new BukkitRunnable() {
            @Override public void run() { cleanup(); }
        };
        tasks.add(cleanupTask);
        cleanupTask.runTaskLater(plugin, 60L);
    }

    public void cleanup() {
        if (cleaned) return;
        cleaned = true;
        cancelControllerOnly();
        cancelTasks();
        stormBats.forEach(b -> { if (b.isValid()) b.remove(); });
        stormBats.clear();
        cleanupWitherRoses();
        LivingEntity scarecrowEntity = getLivingEntity();
        if (scarecrowEntity != null) {
            plugin.getOverheadHealthBarManager().removeBar(scarecrowEntity.getUniqueId());
        }
        if (npc.isSpawned()) npc.despawn();
        npc.destroy();
        plugin.getNPCManager().unregisterScarecrow(npc.getId());
    }

    // ─── NPC Setup ────────────────────────────────────────────────────────────
    private void configureNpc() {
        npc.data().set("bloodmoon-scarecrow", true);
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        ScarecrowTrait trait = npc.getOrAddTrait(ScarecrowTrait.class);
        trait.bind(this);
        configureSkin();
        configureSentinel();
        if (!npc.isSpawned()) npc.spawn(spawnLocation.clone());
        LivingEntity e = getLivingEntity();
        if (e != null) { applyConfiguredHealth(e); hideNameplate(e); }
    }

    private void configureSkin() {
        String skinName  = plugin.getConfigManager().getScarecrowSkinName();
        String texture   = plugin.getConfigManager().getScarecrowSkinTexture();
        String signature = plugin.getConfigManager().getScarecrowSkinSignature();
        if ((skinName == null || skinName.isBlank()) && (texture == null || texture.isBlank())) return;
        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = npc.getOrAddTrait(skinTraitClass);
            skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setFetchDefaultSkin",  boolean.class).invoke(skinTrait, false);
            if (texture != null && !texture.isBlank() && signature != null && !signature.isBlank()) {
                skinTraitClass.getMethod("setSkinPersistent", String.class, String.class, String.class)
                              .invoke(skinTrait, skinName, signature, texture);
                return;
            }
            if (skinName != null && !skinName.isBlank()) {
                skinTraitClass.getMethod("setSkinName", String.class, boolean.class).invoke(skinTrait, skinName, true);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply scarecrow skin: " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
        s.setInvincible(false);
        s.setHealth(plugin.getConfigManager().getScarecrowHealth());
        s.health = plugin.getConfigManager().getScarecrowHealth();
        s.damage = 4.0D * getFearmongerMultiplier();
        s.respawnTime = -1;
        s.chaseRange = 32.0D;
        s.armor = 0.1D;
        s.protectFromIgnores = false;
        s.allTargets = new SentinelTargetList();
        s.addTarget("players");
        s.allIgnores = new SentinelTargetList();
        s.addIgnore("npcs");
    }

    private void hideNameplate(LivingEntity entity) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            if (board == null) return;
            Team team = board.getTeam("bm_hidden_npc");
            if (team == null) {
                team = board.registerNewTeam("bm_hidden_npc");
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            if (entity instanceof Player p) team.addEntry(p.getName());
        } catch (Exception ignored) {}
    }

    private void applyConfiguredHealth(LivingEntity entity) {
        double hp   = plugin.getConfigManager().getScarecrowHealth();
        var    attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) { attr.setBaseValue(hp); entity.setHealth(Math.min(hp, entity.getHealth())); }
    }

    // ─── Combat loop ──────────────────────────────────────────────────────────
    private void startController() {
        controllerTask = new BukkitRunnable() { @Override public void run() { tick(); } };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick() {
        if (cleaned || deathStarted) return;
        stateTicks++;
        globalTick++;
        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));
        if (postCastRecoveryTicks > 0) postCastRecoveryTicks--;
        onTraitTick();
        pruneFearMarks();
        checkPhaseTransition();

        if (globalTick % 5 == 0) {
            tickCinderWake();
        }

        if (state == ScarecrowState.STALKING) { tickStalking(); return; }
        if (state == ScarecrowState.CASTING) { tickCasting(); return; }
        if (state != ScarecrowState.COMBAT) return;

        Player player = ensureTarget(48.0D);
        if (player == null) { player = findNearestPlayer(getCurrentLocation(), 48.0D); target = player; }
        if (player == null) return;

        // Passive: Fearmonger – consume nearby mature wheat crops
        if (stateTicks % 40 == 0) tickFearmonger();

        runCombatMovementPattern(player);
        npc.faceLocation(player.getEyeLocation());

        // Ambient skeleton sound
        if (stateTicks % 60 == 0) {
            Location loc = getCurrentLocation();
            if (loc.getWorld() != null) loc.getWorld().playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.6F, 0.9F + random.nextFloat() * 0.2F);
        }

        if (postCastRecoveryTicks > 0) {
            return;
        }

        int baseInterval = switch (phase) {
            case WATCHER -> 30;
            case HARVESTER -> 24;
            case JUDGEMENT -> 20;
        };
        int abilityInterval = Math.max(14, (int) Math.round(baseInterval * plugin.getBloodMoonManager().getAbilityCadenceMultiplier()));
        if (stateTicks % abilityInterval == 0) {
            ScarecrowAbility ability = chooseAbility(player);
            if (ability != null) startCasting(ability);
        }
    }

    private void tickCasting() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) return;
        if (target != null) {
            Location eye = entity.getLocation();
            Location tgtLoc = target.getLocation();
            double dx = tgtLoc.getX() - eye.getX();
            double dz = tgtLoc.getZ() - eye.getZ();
            float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
            eye.setYaw(yaw);
            entity.teleport(eye);
        }
        runCastingParticles();
        updateCastingAnimation();
        if (stateTicks < castTicks) return;
        resetCastingAnimation();
        ScarecrowAbility ability = pending;
        pending = null;
        state = beforeCast;
        stateTicks = 0;
        castTicks = 0;
        postCastRecoveryTicks = 12;
        if (ability != null) executeAbility(ability);
    }

    // ─── Casting animation dispatch ───────────────────────────────────────────
    private void updateCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (!(entity instanceof Player player)) return;
        if (pending == null) return;
        switch (pending) {
            case FEAR       -> animateFear(player);
            case DRAIN      -> animateDrain(player);
            case BLOOM      -> animateBloom(player);
            case REAP       -> animateReap(player);
            case FIREBALLS  -> animateFireballs(player);
            case PHANTOM    -> animatePhantom(player);
            case CROWSTORM  -> animateCrowstorm(player);
            case DARK_WIND  -> animateDarkWind(player);
            case HIGH_JUMP  -> animateHighJump(player);
        }
    }

    private void animateFear(Player player) {
        if (stateTicks % 3 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animateDrain(Player player) {
        if (stateTicks % 2 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animateBloom(Player player) {
        if (stateTicks % 4 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animateReap(Player player) {
        if (stateTicks % 3 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animateFireballs(Player player) {
        if (stateTicks % 4 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animatePhantom(Player player) {
        if (stateTicks % 5 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animateCrowstorm(Player player) {
        if (stateTicks % 3 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animateDarkWind(Player player) {
        if (stateTicks % 4 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void animateHighJump(Player player) {
        if (stateTicks % 3 == 0) playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void resetCastingAnimation() {
        LivingEntity entity = getLivingEntity();
        if (!(entity instanceof Player player)) return;
        playCitizensPlayerAnimation(player, "ARM_SWING");
    }

    private void startCasting(ScarecrowAbility ability) {
        if (state == ScarecrowState.CASTING || state == ScarecrowState.DEAD) return;
        pending    = ability;
        beforeCast = state;
        state      = ScarecrowState.CASTING;
        stateTicks = 0;
        castTicks  = switch (ability) {
            case DRAIN, CROWSTORM -> 30;
            case HIGH_JUMP        -> 10;
            default               -> 20;
        };
        npc.getNavigator().cancelNavigation();
        Location loc   = getCurrentLocation();
        World    world = loc.getWorld();
        if (world == null) {
            return;
        }
        switch (ability) {
            case FEAR -> {
                world.playSound(loc, Sound.ENTITY_SKELETON_HURT,   0.8F, 0.5F);
                world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,  0.5F, 1.7F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 14, 0.4D, 0.4D, 0.4D, 0D, DUST_CYAN);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  6, 0.3D, 0.3D, 0.3D, 0D, DUST_BLACK);
            }
            case DRAIN -> {
                world.playSound(loc, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0F, 0.6F);
                world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,   0.5F, 1.45F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 16, 0.4D, 0.5D, 0.4D, 0D, DUST_GREEN);
                world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.2D, 0), 12, 0.4D, 0.1D, 0.4D, Material.MOSS_BLOCK.createBlockData());
            }
            case BLOOM -> {
                world.playSound(loc, Sound.BLOCK_GRASS_PLACE, 0.9F, 0.5F);
                world.playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.6F, 0.7F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 12, 0.35D, 0.4D, 0.35D, 0D, DUST_BLACK);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  6, 0.25D, 0.3D, 0.25D, 0D, DUST_GREEN);
            }
            case REAP -> {
                world.playSound(loc, Sound.ITEM_TRIDENT_THROW,    0.9F, 0.55F);
                world.playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.7F, 0.65F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 14, 0.4D, 0.3D, 0.4D, 0D, DUST_BONE);
                world.spawnParticle(Particle.CRIT, loc.clone().add(0, 1.1D, 0),  6, 0.3D, 0.2D, 0.3D, 0.03D);
            }
            case FIREBALLS -> {
                world.playSound(loc, Sound.ENTITY_BLAZE_SHOOT, 0.8F, 0.9F);
                world.playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.6F, 0.8F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 12, 0.35D, 0.4D, 0.35D, 0D, DUST_ORANGE);
                world.spawnParticle(Particle.FLAME, loc.clone().add(0, 1.0D, 0),  6, 0.2D,  0.3D, 0.2D,  0.04D);
            }
            case PHANTOM -> {
                world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 1.7F);
                world.playSound(loc, Sound.ENTITY_SKELETON_STEP,     0.7F, 0.8F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 14, 0.4D, 0.4D, 0.4D, 0D, DUST_DARK);
                world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 0.5D, 0),  8, 0.3D, 0.2D, 0.3D, 0.03D);
            }
            case CROWSTORM -> {
                world.playSound(loc, Sound.ENTITY_BAT_TAKEOFF,      1.0F, 0.8F);
                world.playSound(loc, Sound.ENTITY_SKELETON_AMBIENT,  0.6F, 0.6F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 16, 0.5D, 0.4D, 0.5D, 0D, DUST_BLACK);
                world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 0.5D, 0), 10, 0.4D, 0.2D, 0.4D, 0.03D);
            }
            case DARK_WIND -> {
                world.playSound(loc, Sound.ENTITY_WITHER_SHOOT,   0.7F, 1.5F);
                world.playSound(loc, Sound.ENTITY_SKELETON_HURT,  0.5F, 0.8F);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 14, 0.4D, 0.3D, 0.4D, 0D, DUST_CYAN);
                world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0),  8, 0.3D, 0.25D, 0.3D, 0D, DUST_BLACK);
            }
            case HIGH_JUMP -> {
                world.playSound(loc, Sound.ENTITY_SKELETON_STEP,    0.9F, 0.5F);
                world.playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.6F, 0.9F);
                world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 10, 0.3D, 0.4D, 0.3D, 0D, DUST_BONE);
                world.spawnParticle(Particle.BLOCK, loc.clone().add(0, 0.2D, 0), 12, 0.35D, 0.1D, 0.35D, Material.HAY_BLOCK.createBlockData());
            }
        }
    }

    // ─── Casting particles ────────────────────────────────────────────────────
    private void runCastingParticles() {
        if (pending == null) return;
        Location base  = getCurrentLocation().clone().add(0.0D, 1.0D, 0.0D);
        World    world = base.getWorld();
        if (world == null) return;
        spawnGenericCastingRing(base, world);
        switch (pending) {
            case FEAR      -> spawnFearCastingParticles(base, world);
            case DRAIN     -> spawnDrainCastingParticles(base, world);
            case BLOOM     -> spawnBloomCastingParticles(base, world);
            case REAP      -> spawnReapCastingParticles(base, world);
            case FIREBALLS -> spawnFireballsCastingParticles(base, world);
            case PHANTOM   -> spawnPhantomCastingParticles(base, world);
            case CROWSTORM -> spawnCrowstormCastingParticles(base, world);
            case DARK_WIND -> spawnDarkWindCastingParticles(base, world);
            case HIGH_JUMP -> spawnHighJumpCastingParticles(base, world);
        }
    }

    private void spawnGenericCastingRing(Location base, World world) {
        double angle  = stateTicks * 0.44D;
        double radius = 0.8D;
        for (int i = 0; i < 4; i++) {
            double a = angle + i * (Math.PI / 2.0D);
            double rise = 0.08D * (stateTicks % 8);
            world.spawnParticle(Particle.DUST,
                base.clone().add(Math.cos(a) * radius, rise, Math.sin(a) * radius),
                1, 0.02D, 0.02D, 0.02D, 0.0D, DUST_BONE);
        }
    }

    private void spawnFearCastingParticles(Location base, World world) {
        double a = stateTicks * 0.35D;
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.5D, 0.1D, Math.sin(a) * 0.5D),
            4, 0.15D, 0.2D, 0.15D, 0D, DUST_CYAN);
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a + Math.PI) * 0.5D, 0.1D, Math.sin(a + Math.PI) * 0.5D),
            2, 0.1D, 0.15D, 0.1D, 0D, DUST_BLACK);
    }

    private void spawnDrainCastingParticles(Location base, World world) {
        if (stateTicks % 2 == 0) {
            world.spawnParticle(Particle.DUST, base, 6, 0.22D, 0.35D, 0.22D, 0D, DUST_GREEN);
            world.spawnParticle(Particle.DUST, base.clone().add(0, 0.2D, 0), 3, 0.15D, 0.25D, 0.15D, 0D, DUST_WHITE);
        }
    }

    private void spawnBloomCastingParticles(Location base, World world) {
        double a = stateTicks * 0.28D;
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.55D, 0.05D, Math.sin(a) * 0.55D),
            3, 0.1D, 0.1D, 0.1D, 0D, DUST_BLACK);
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a + Math.PI) * 0.55D, 0.05D, Math.sin(a + Math.PI) * 0.55D),
            2, 0.08D, 0.08D, 0.08D, 0D, DUST_GREEN);
    }

    private void spawnReapCastingParticles(Location base, World world) {
        double a = stateTicks * 0.4D;
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.6D, 0.0D, Math.sin(a) * 0.6D),
            4, 0.12D, 0.1D, 0.12D, 0D, DUST_BONE);
        if (stateTicks % 3 == 0) {
            world.spawnParticle(Particle.CRIT, base, 2, 0.2D, 0.2D, 0.2D, 0.02D);
        }
    }

    private void spawnFireballsCastingParticles(Location base, World world) {
        double a = stateTicks * 0.5D;
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.5D, 0.1D, Math.sin(a) * 0.5D),
            3, 0.12D, 0.12D, 0.12D, 0D, DUST_ORANGE);
        if (stateTicks % 3 == 0) {
            world.spawnParticle(Particle.FLAME, base, 1, 0.15D, 0.2D, 0.15D, 0.04D);
        }
    }

    private void spawnPhantomCastingParticles(Location base, World world) {
        double a = stateTicks * 0.3D;
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.5D, 0.05D, Math.sin(a) * 0.5D),
            4, 0.15D, 0.2D, 0.15D, 0D, DUST_DARK);
        if (stateTicks % 4 == 0) {
            world.spawnParticle(Particle.SMOKE, base, 2, 0.2D, 0.15D, 0.2D, 0.02D);
        }
    }

    private void spawnCrowstormCastingParticles(Location base, World world) {
        double a = stateTicks * 0.55D;
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.6D, 0.0D, Math.sin(a) * 0.6D),
            3, 0.1D, 0.1D, 0.1D, 0D, DUST_BLACK);
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a + Math.PI) * 0.6D, 0.0D, Math.sin(a + Math.PI) * 0.6D),
            2, 0.1D, 0.1D, 0.1D, 0D, DUST_BLACK);
        if (stateTicks % 5 == 0) {
            world.spawnParticle(Particle.SMOKE, base, 2, 0.25D, 0.2D, 0.25D, 0.02D);
        }
    }

    private void spawnDarkWindCastingParticles(Location base, World world) {
        double a = stateTicks * 0.42D;
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 0.55D, 0.08D, Math.sin(a) * 0.55D),
            4, 0.12D, 0.18D, 0.12D, 0D, DUST_CYAN);
        world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a + Math.PI) * 0.55D, 0.08D, Math.sin(a + Math.PI) * 0.55D),
            2, 0.1D, 0.12D, 0.1D, 0D, DUST_BLACK);
    }

    private void spawnHighJumpCastingParticles(Location base, World world) {
        if (stateTicks % 2 == 0) {
            world.spawnParticle(Particle.DUST,  base.clone().add(0, -0.8D, 0), 6, 0.3D, 0.1D, 0.3D, 0D, DUST_BONE);
            world.spawnParticle(Particle.BLOCK, base.clone().add(0, -0.8D, 0), 5, 0.35D, 0.05D, 0.35D, Material.HAY_BLOCK.createBlockData());
        }
    }

    // ─── Intro / stalking state ───────────────────────────────────────────────
    private void tickStalking() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) return;
        Location loc   = entity.getLocation();
        World    world = entity.getWorld();

        // Build tension — straw wisps drift off, creaking bone sounds, pumpkin glow
        if (stateTicks % 6 == 0) {
            world.spawnParticle(Particle.DUST, loc.clone().add(
                (random.nextDouble() - 0.5D) * 0.6D, 1.2D + random.nextDouble() * 0.6D,
                (random.nextDouble() - 0.5D) * 0.6D),
                3, 0.08D, 0.05D, 0.08D, 0D, DUST_BONE);
        }
        if (stateTicks % 10 == 0) {
            world.spawnParticle(Particle.DUST, loc.clone().add(
                (random.nextDouble() - 0.5D) * 0.4D, 1.0D + random.nextDouble() * 0.5D,
                (random.nextDouble() - 0.5D) * 0.4D),
                2, 0.06D, 0.04D, 0.06D, 0D, DUST_DARK);
        }
        if (stateTicks == 5) {
            world.playSound(loc, Sound.ENTITY_SKELETON_STEP, 0.65F, 0.45F);
        }
        if (stateTicks == 15) {
            world.playSound(loc, Sound.BLOCK_BONE_BLOCK_STEP, 0.7F, 0.55F);
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 8, 0.3D, 0.3D, 0.3D, 0D, DUST_BONE);
        }
        if (stateTicks == 25) {
            world.playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.8F, 0.6F);
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1.0D, 0), 6, 0.25D, 0.3D, 0.25D, 0D, DUST_CYAN);
        }
        // Reveal burst at end of stalking
        if (stateTicks >= STALKING_TICKS) {
            world.playSound(loc, Sound.ENTITY_SKELETON_HURT,  0.9F, 0.55F);
            world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 0.5F, 1.4F);
            world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 22, 0.45D, 0.5D, 0.45D, 0D, DUST_BONE);
            world.spawnParticle(Particle.DUST,  loc.clone().add(0, 1.0D, 0), 12, 0.3D,  0.4D, 0.3D,  0D, DUST_CYAN);
            world.spawnParticle(Particle.SMOKE, loc.clone().add(0, 0.5D, 0),  8, 0.4D,  0.2D, 0.4D,  0.03D);
            state      = ScarecrowState.COMBAT;
            stateTicks = 0;
        }
    }

    // ─── Ambient tell particles ───────────────────────────────────────────────
    private void emitScarecrowTellParticles(LivingEntity entity) {
        Location loc   = entity.getLocation();
        World    world = entity.getWorld();
        if (world == null) return;

        double healthFraction = entity.getHealth() / (entity.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null
            ? entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() : 40.0D);

        // Ambient straw wisps — intensify with more fearmonger stacks
        int stackBonus = Math.min(fearmongerStacks / 3, 5);
        world.spawnParticle(Particle.DUST,
            loc.clone().add((random.nextDouble() - 0.5D) * 0.5D, 1.0D + random.nextDouble() * 0.5D,
                (random.nextDouble() - 0.5D) * 0.5D),
            2 + stackBonus, 0.1D, 0.06D, 0.1D, 0D, DUST_BONE);

        // Dark wisps at low health
        if (healthFraction < 0.4D) {
            world.spawnParticle(Particle.DUST,
                loc.clone().add((random.nextDouble() - 0.5D) * 0.4D, 0.8D + random.nextDouble() * 0.4D,
                    (random.nextDouble() - 0.5D) * 0.4D),
                2, 0.08D, 0.05D, 0.08D, 0D, DUST_DARK);
        }
        // Fearmonger aura — orange glow when stacks are high
        if (fearmongerStacks >= 5) {
            world.spawnParticle(Particle.DUST,
                loc.clone().add((random.nextDouble() - 0.5D) * 0.35D, 1.1D + random.nextDouble() * 0.3D,
                    (random.nextDouble() - 0.5D) * 0.35D),
                1, 0.05D, 0.04D, 0.05D, 0D, DUST_AMBER);
        }
    }

    private void runCombatMovementPattern(Player player) {
        // Grounded pursuit so the scarecrow behaves like a hunter, not a teleporter.
        npc.getNavigator().getDefaultParameters().speedModifier(1.18F);
        if (stateTicks % 20 == 0) {
            double strafe = random.nextBoolean() ? 2.4D : -2.4D;
            Vector right = player.getLocation().getDirection().setY(0).normalize();
            Vector lateral = new Vector(-right.getZ(), 0.0D, right.getX()).normalize().multiply(strafe);
            Location strafePoint = player.getLocation().clone().add(lateral).add(player.getLocation().getDirection().setY(0).normalize().multiply(-1.5D));
            strafePoint.setY(player.getWorld().getHighestBlockYAt(strafePoint) + 1.0D);
            npc.getNavigator().setTarget(strafePoint);
        } else {
            npc.getNavigator().setTarget(player, true);
        }

        if (stateTicks % 25 == 0) {
            LivingEntity entity = getLivingEntity();
            if (entity != null) emitScarecrowTellParticles(entity);
        }
    }

    private ScarecrowAbility chooseAbility(Player player) {
        if (player == null) {
            return null;
        }

        if (forcedComboAbility != null && cooldowns.getOrDefault(forcedComboAbility, 0) <= 0) {
            ScarecrowAbility chained = forcedComboAbility;
            forcedComboAbility = null;
            return chained;
        }

        double distSq = player.getLocation().distanceSquared(getCurrentLocation());
        if (isFearMarked(player) && cooldowns.getOrDefault(ScarecrowAbility.REAP, 0) <= 0 && distSq <= 64.0D) {
            return ScarecrowAbility.REAP;
        }
        if (player.getFireTicks() > 0 && cooldowns.getOrDefault(ScarecrowAbility.DRAIN, 0) <= 0) {
            return ScarecrowAbility.DRAIN;
        }
        if (distSq > 120.0D && cooldowns.getOrDefault(ScarecrowAbility.PHANTOM, 0) <= 0) {
            return ScarecrowAbility.PHANTOM;
        }

        List<ScarecrowAbility> available = new ArrayList<>();
        for (ScarecrowAbility ability : ScarecrowAbility.values()) {
            if (cooldowns.getOrDefault(ability, 0) <= 0) {
                available.add(ability);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        int minUses = available.stream().mapToInt(a -> abilityUseCounts.getOrDefault(a, 0)).min().orElse(0);
        List<ScarecrowAbility> underused = available.stream().filter(a -> abilityUseCounts.getOrDefault(a, 0) == minUses).toList();
        if (!underused.isEmpty() && random.nextDouble() <= 0.6D) {
            return underused.get(random.nextInt(underused.size()));
        }

        List<ScarecrowAbility> pool = new ArrayList<>();
        for (ScarecrowAbility a : available) {
            int weight = switch (a) {
                case FEAR, DRAIN -> 1;
                case BLOOM, REAP -> 2;
                case FIREBALLS, DARK_WIND, CROWSTORM -> 4;
                case PHANTOM, HIGH_JUMP -> 2;
            };
            for (int i = 0; i < weight; i++) pool.add(a);
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private void executeAbility(ScarecrowAbility ability) {
        abilityUseCounts.merge(ability, 1, Integer::sum);
        switch (ability) {
            case FEAR      -> castFear();
            case DRAIN     -> castDrain();
            case BLOOM     -> castBloom();
            case REAP      -> castReap();
            case FIREBALLS -> castFireballs();
            case PHANTOM   -> castPhantom();
            case CROWSTORM -> castCrowstorm();
            case DARK_WIND -> castDarkWind();
            case HIGH_JUMP -> castHighJump();
        }
        forcedComboAbility = switch (ability) {
            case FEAR -> ScarecrowAbility.REAP;
            case CROWSTORM -> ScarecrowAbility.FIREBALLS;
            case HIGH_JUMP -> ScarecrowAbility.DRAIN;
            default -> null;
        };
        cooldowns.put(ability, switch (ability) {
            case FEAR      -> 300;
            case DRAIN     -> 260;
            case BLOOM     -> 180;
            case REAP      -> 200;
            case FIREBALLS -> 100;
            case PHANTOM   -> 180;
            case CROWSTORM -> 260;
            case DARK_WIND -> 120;
            case HIGH_JUMP -> 180;
        });
    }

    private void checkPhaseTransition() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) return;
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null || attr.getValue() <= 0.0D) return;
        double hpRatio = entity.getHealth() / attr.getValue();

        if (!harvesterTriggered && hpRatio <= 0.70D) {
            harvesterTriggered = true;
            phase = ScarecrowPhase.HARVESTER;
            reduceCooldowns(0.72D);
            World world = entity.getWorld();
            Location loc = entity.getLocation();
            // Dramatic HARVESTER transition
            world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,       0.9F, 1.20F);
            world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL,   0.6F, 1.65F);
            world.playSound(loc, Sound.BLOCK_WOOD_BREAK,            1.0F, 0.45F);
            world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0D, 0.5D, 0D), 2, 0.4D, 0.2D, 0.4D, 0D);
            world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1D, 0D), 40, 0.8D, 0.7D, 0.8D, 0D, DUST_AMBER);
            world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1D, 0D), 20, 0.6D, 0.5D, 0.6D, 0D, DUST_ORANGE);
            world.spawnParticle(Particle.SMOKE,   loc.clone().add(0D, 0.3D, 0D), 18, 0.5D, 0.2D, 0.5D, 0.04D);
            world.spawnParticle(Particle.CRIT,    loc.clone().add(0D, 1.0D, 0D), 15, 0.4D, 0.4D, 0.4D, 0.06D);
            // Ring burst
            for (int i = 0; i < 24; i++) {
                double a = (Math.PI * 2.0D / 24.0D) * i;
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(Math.cos(a) * 1.8D, 0.5D, Math.sin(a) * 1.8D),
                    1, 0.03D, 0.03D, 0.03D, 0D, DUST_AMBER);
            }
            // Announce to nearby players
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(loc) <= 900.0D && plugin.hasBossMessages(p.getUniqueId())) {
                    p.sendMessage("§6§lThe Scarecrow enters the HARVESTER phase!");
                }
            }
        }

        if (!judgementTriggered && hpRatio <= 0.35D) {
            judgementTriggered = true;
            phase = ScarecrowPhase.JUDGEMENT;
            reduceCooldowns(0.58D);
            forcedComboAbility = ScarecrowAbility.CROWSTORM;
            World world = entity.getWorld();
            Location loc = entity.getLocation();
            // Dramatic JUDGEMENT transition
            world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL,   1.0F, 1.45F);
            world.playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM,    0.7F, 0.40F);
            world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT,       1.0F, 0.55F);
            world.spawnParticle(Particle.EXPLOSION, loc.clone().add(0D, 0.5D, 0D), 3, 0.5D, 0.3D, 0.5D, 0D);
            world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1D, 0D), 55, 1.0D, 0.9D, 1.0D, 0D, DUST_ORANGE);
            world.spawnParticle(Particle.DUST, loc.clone().add(0D, 1D, 0D), 25, 0.7D, 0.6D, 0.7D, 0D, DUST_RED);
            world.spawnParticle(Particle.LAVA,     loc.clone().add(0D, 0.5D, 0D), 20, 0.6D, 0.3D, 0.6D, 0D);
            world.spawnParticle(Particle.SMOKE,    loc.clone().add(0D, 0.5D, 0D), 24, 0.7D, 0.3D, 0.7D, 0.05D);
            // Darkness pulse on nearby players
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(loc) <= 900.0D) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, true, true));
                    p.sendMessage("§4§l☠ The Scarecrow enters JUDGEMENT — flee or perish! ☠");
                }
            }
            // Expanding ring of fire
            for (int i = 0; i < 32; i++) {
                double a = (Math.PI * 2.0D / 32.0D) * i;
                world.spawnParticle(Particle.DUST,
                    loc.clone().add(Math.cos(a) * 2.5D, 0.4D, Math.sin(a) * 2.5D),
                    1, 0.04D, 0.04D, 0.04D, 0D, DUST_RED);
            }
        }
    }

    private void reduceCooldowns(double factor) {
        cooldowns.replaceAll((k, v) -> Math.max(0, (int) Math.round(v * factor)));
    }

    private void pruneFearMarks() {
        long now = System.currentTimeMillis();
        fearMarks.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private boolean isFearMarked(Player player) {
        if (player == null) return false;
        Long expiry = fearMarks.get(player.getUniqueId());
        return expiry != null && expiry > System.currentTimeMillis();
    }

    private void markFear(Player player) {
        if (player == null) return;
        fearMarks.put(player.getUniqueId(), System.currentTimeMillis() + FEAR_MARK_DURATION_MS);
    }

    private void tickCinderWake() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) return;
        World world = entity.getWorld();
        Location foot = entity.getLocation().getBlock().getLocation().add(0.5D, 0.0D, 0.5D);
        cinderPatches.put(foot, globalTick + 80);

        Iterator<Map.Entry<Location, Integer>> iter = cinderPatches.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Location, Integer> entry = iter.next();
            Location patch = entry.getKey();
            int expiryTick = entry.getValue();
            if (globalTick > expiryTick || patch.getWorld() == null || patch.getWorld() != world) {
                iter.remove();
                continue;
            }

            if (globalTick % 4 == 0) {
                world.spawnParticle(Particle.SMOKE, patch.clone().add(0, 0.05D, 0), 1, 0.14D, 0.02D, 0.14D, 0.01D);
                world.spawnParticle(Particle.DUST, patch.clone().add(0, 0.06D, 0), 1, 0.12D, 0.02D, 0.12D, 0.0D, DUST_ORANGE);
            }

            if (globalTick % 10 == 0) {
                for (Player p : world.getPlayers()) {
                    if (p.isDead() || p.getLocation().distanceSquared(patch) > 1.35D) continue;
                    p.damage(0.7D, entity);
                    p.setFireTicks(Math.max(p.getFireTicks(), 25));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 0, false, true, true));
                }
            }
        }
    }

    // ─── ABILITIES ────────────────────────────────────────────────────────────

    /**
     * Fear: fires a slow cyan/black orb. On contact: Blindness, Darkness, Slowness V,
     * Mining Fatigue, and places a carved pumpkin on the target's head for 4 seconds.
     */
    private void castFear() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        Location start = caster.getEyeLocation();
        world.playSound(start, Sound.ENTITY_SKELETON_HURT, 0.6F, 0.5F);
        world.spawnParticle(Particle.DUST, start, 10, 0.2, 0.2, 0.2, 0, DUST_CYAN);

        Vector dir = tgt.getEyeLocation().toVector().subtract(start.toVector()).normalize().multiply(0.28D);
        final Location[] pos = { start.clone() };

        BukkitRunnable orb = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 110 || isDead()) { cancel(); return; }
                pos[0].add(dir);
                world.spawnParticle(Particle.DUST, pos[0], 5, 0.15, 0.15, 0.15, 0, DUST_CYAN);
                world.spawnParticle(Particle.DUST, pos[0], 3, 0.10, 0.10, 0.10, 0, DUST_BLACK);
                world.spawnParticle(Particle.SMOKE, pos[0], 2, 0.04, 0.04, 0.04, 0.01);
                for (Player p : world.getPlayers()) {
                    if (p.isDead() || p.getLocation().distanceSquared(pos[0]) > 2.4D * 2.4D) continue;
                    applyFear(p);
                    world.playSound(pos[0], Sound.ENTITY_WITHER_AMBIENT, 0.5F, 1.8F);
                    cancel();
                    return;
                }
            }
        };
        tasks.add(orb);
        orb.runTaskTimer(plugin, 0L, 1L);
    }

    private void applyFear(Player player) {
        World world = player.getWorld();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,    80, 1, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,     80, 1, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,     80, 4, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, 2, false, true, true));
        markFear(player);
        if (phase == ScarecrowPhase.JUDGEMENT) {
            player.setFireTicks(Math.max(player.getFireTicks(), 40));
        }

        // Pumpkin head – save old helmet and restore after 4 seconds
        ItemStack oldHelmet = player.getInventory().getHelmet();
        player.getInventory().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        world.playSound(player.getLocation(), Sound.BLOCK_PUMPKIN_CARVE, 0.8F, 1.0F);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 2, 0), 14, 0.3, 0.2, 0.3, 0, DUST_CYAN);

        BukkitRunnable restore = new BukkitRunnable() {
            @Override public void run() {
                if (player.isOnline()) player.getInventory().setHelmet(oldHelmet);
            }
        };
        tasks.add(restore);
        restore.runTaskLater(plugin, 80L);
    }

    /**
     * Harvest Drain: roots the scarecrow in place and channels compact vine streams.
     * All living entities caught in range are drained while the scarecrow heals for the same amount.
     */
    private void castDrain() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();

        beforeCast = ScarecrowState.COMBAT;
        state = ScarecrowState.CASTING;
        stateTicks = 0;
        castTicks = 120;
        Location root = caster.getLocation().clone();
        world.playSound(root, Sound.BLOCK_ROOTED_DIRT_BREAK, 1.0F, 0.6F);
        world.playSound(root, Sound.ENTITY_WITHER_AMBIENT, 0.5F, 1.45F);
        world.spawnParticle(Particle.BLOCK, root.clone().add(0, 0.1, 0), 24, 0.45, 0.0, 0.45, 0.02, Material.MOSS_BLOCK.createBlockData());
        world.spawnParticle(Particle.DUST, root.clone().add(0, 1.0, 0), 18, 0.35, 0.45, 0.35, 0, DUST_GREEN);

        BukkitRunnable drain = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 100 || isDead()) {
                    state = ScarecrowState.COMBAT;
                    stateTicks = 0;
                    castTicks = 0;
                    cancel();
                    return;
                }

                LivingEntity entity = getLivingEntity();
                if (entity == null) {
                    state = ScarecrowState.COMBAT;
                    stateTicks = 0;
                    castTicks = 0;
                    cancel();
                    return;
                }

                npc.getNavigator().cancelNavigation();
                entity.setVelocity(new Vector(0, 0, 0));
                Location base = root.clone();
                base.setYaw(entity.getLocation().getYaw());
                base.setPitch(-35.0F);
                if (entity instanceof Player npcPlayer) {
                    npcPlayer.teleport(base, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    if (t % 8 == 0) {
                        playCitizensPlayerAnimation(npcPlayer, "ARM_SWING");
                    }
                } else {
                    entity.teleport(base);
                }

                Location casterLoc = entity.getLocation().clone().add(0, 1.6, 0);
                Location leftShoulder = casterLoc.clone().add(-0.35D, -0.15D + Math.sin(t * 0.7D) * 0.04D, 0.0D);
                Location rightShoulder = casterLoc.clone().add(0.35D, -0.15D + Math.cos(t * 0.7D) * 0.04D, 0.0D);
                world.spawnParticle(Particle.DUST, casterLoc, 7, 0.18, 0.55, 0.18, 0, DUST_WHITE);
                world.spawnParticle(Particle.DUST, casterLoc, 8, 0.28, 0.60, 0.28, 0, DUST_GREEN);
                world.spawnParticle(Particle.BLOCK, entity.getLocation().clone().add(0, 0.08, 0), 5, 0.25, 0.0, 0.25, 0.01, Material.ROOTED_DIRT.createBlockData());

                List<LivingEntity> connected = getDrainTargets(entity, 8.0D);
                if (connected.isEmpty() && t > 25) {
                    state = ScarecrowState.COMBAT;
                    stateTicks = 0;
                    castTicks = 0;
                    cancel();
                    return;
                }

                double totalHeal = 0;
                int index = 0;
                for (LivingEntity victim : connected) {
                    Location victimLoc = victim.getLocation().clone().add(0, victim.getHeight() * 0.6D, 0);
                    for (int strand = 0; strand < 3; strand++) {
                        double progress = ((t * 0.10D) + (strand * 0.22D) + (index * 0.11D)) % 1.0D;
                        Location stream = victimLoc.clone().add(casterLoc.clone().subtract(victimLoc).toVector().multiply(progress));
                        world.spawnParticle(Particle.DUST, stream, 1, 0.02D, 0.03D, 0.02D, 0.0D, DUST_GREEN);
                    }
                    world.spawnParticle(Particle.DUST, victimLoc, 2, 0.10D, 0.10D, 0.10D, 0.0D, DUST_WHITE);
                    if (t % 6 == 0) {
                        double tickDamage = 1.0D;
                        if (victim instanceof Player pv && isFearMarked(pv)) {
                            tickDamage += 0.8D;
                        }
                        if (victim.getFireTicks() > 0) {
                            tickDamage += 0.6D;
                        }
                        if (phase == ScarecrowPhase.JUDGEMENT) {
                            tickDamage += 0.5D;
                        }
                        victim.damage(tickDamage, entity);
                        totalHeal += tickDamage * 0.75D;
                        world.spawnParticle(Particle.DUST, victimLoc, 3, 0.12, 0.12, 0.12, 0, DUST_WHITE);
                        if (victim instanceof Player playerVictim) {
                            playerVictim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 25, 0, false, true, true));
                            if (isFearMarked(playerVictim)) {
                                playerVictim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true, true));
                            }
                        }
                    }
                    index++;
                }

                if (totalHeal > 0) {
                    if (entity != null) {
                        var hpAttr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                        double maxHp = hpAttr != null ? hpAttr.getValue() : 40.0D;
                        entity.setHealth(Math.min(maxHp, entity.getHealth() + totalHeal));
                    }
                }

                if (t % 12 == 0) {
                    world.playSound(casterLoc, Sound.ENTITY_ENDERMAN_SCREAM, 0.22F, 1.7F);
                }
                if (t % 20 == 0) {
                    world.playSound(casterLoc, Sound.BLOCK_SCULK_CATALYST_BLOOM, 0.55F, 1.35F);
                }
            }
        };
        tasks.add(drain);
        drain.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * Bloom: scatter wither roses in random positions around the target.
     * Roses disappear after 7 seconds.
     */
    private void castBloom() {
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = tgt.getWorld();
        world.playSound(tgt.getLocation(), Sound.BLOCK_GRASS_PLACE, 0.8F, 0.5F);
        world.playSound(tgt.getLocation(), Sound.ITEM_FIRECHARGE_USE, 0.5F, 0.9F);
        world.spawnParticle(Particle.DUST, tgt.getLocation().add(0, 0.5, 0), 22, 1.6, 0.3, 1.6, 0, DUST_BLACK);

        List<Location> placed = new ArrayList<>();
        for (int i = 0; i < 8 + random.nextInt(4); i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double dist  = random.nextDouble() * 1.5D;
            int bx = tgt.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int bz = tgt.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int by = world.getHighestBlockYAt(bx, bz);
            Block ground = world.getBlockAt(bx, by - 1, bz);
            Block rose   = world.getBlockAt(bx, by, bz);
            if (ground.getType().isSolid() && rose.getType() == Material.AIR) {
                rose.setType(Material.WITHER_ROSE);
                if (phase != ScarecrowPhase.WATCHER && rose.getRelative(0, 1, 0).getType().isAir()) {
                    rose.getRelative(0, 1, 0).setType(Material.FIRE);
                }
                placed.add(rose.getLocation());
                witherRoseLocations.add(rose.getLocation());
                world.spawnParticle(Particle.DUST, rose.getLocation().add(0.5, 0.6, 0.5), 4, 0.2, 0.2, 0.2, 0, DUST_BLACK);
            }
        }

        BukkitRunnable remove = new BukkitRunnable() {
            @Override public void run() {
                for (Location l : placed) {
                    if (l.getBlock().getType() == Material.WITHER_ROSE) l.getBlock().setType(Material.AIR);
                    witherRoseLocations.remove(l);
                }
            }
        };
        tasks.add(remove);
        remove.runTaskLater(plugin, 140L); // 7 seconds
    }

    /**
     * Reap: half-moon sweep in front of the scarecrow (5-block radius).
     * Targets below 15% HP are instantly killed. Others are slowed by 50% for 2 seconds.
     */
    private void castReap() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        World world = caster.getWorld();
        Location center = caster.getEyeLocation();

        world.playSound(center, Sound.ITEM_TRIDENT_THROW, 0.9F, 0.6F);
        world.playSound(center, Sound.ENTITY_SKELETON_AMBIENT, 0.7F, 0.7F);

        // Forward direction (horizontal)
        Vector forward = caster.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.001D) forward = new Vector(1, 0, 0);
        forward.normalize();
        Vector perp = new Vector(-forward.getZ(), 0, forward.getX());

        // Draw the half-moon arc with particles
        for (double t = -Math.PI / 2; t <= Math.PI / 2; t += Math.PI / 20) {
            Vector arcDir = forward.clone().multiply(Math.cos(t)).add(perp.clone().multiply(Math.sin(t)));
            for (double r = 0.8D; r <= 5.0D; r += 0.6D) {
                Location tip = center.clone().add(arcDir.clone().multiply(r)).add(0, -0.3, 0);
                world.spawnParticle(Particle.CRIT, tip, 1, 0, 0, 0, 0.02);
                if (r > 3.5D) world.spawnParticle(Particle.SWEEP_ATTACK, tip, 1, 0, 0, 0, 0);
            }
        }

        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            Location pLoc = p.getLocation().add(0, 1, 0);
            if (pLoc.distanceSquared(center) > 25.0D) continue; // 5 block radius

            // Half-moon: must be in front hemisphere
            Vector toPlayer = pLoc.toVector().subtract(center.toVector()).setY(0).normalize();
            if (forward.dot(toPlayer) < 0.0D) continue;

            var hpAttr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            double maxHp = hpAttr != null ? hpAttr.getValue() : 20.0D;

            if (p.getHealth() / maxHp < 0.15D) {
                // Finishing blow
                p.setHealth(0);
                world.playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.9F, 0.5F);
                world.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0, DUST_RED);
            } else {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));
                double baseDamage = 5.0D + fearmongerStacks;
                if (isFearMarked(p)) {
                    baseDamage += 3.5D;
                    fearMarks.remove(p.getUniqueId());
                    world.spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 14, 0.25, 0.25, 0.25, 0.03);
                    world.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.7F, 0.75F);
                }
                if (phase == ScarecrowPhase.JUDGEMENT) {
                    baseDamage += 1.5D;
                }
                p.damage(baseDamage, caster);
                world.spawnParticle(Particle.CRIT, p.getLocation().add(0, 1, 0), 8, 0.3, 0.3, 0.3, 0.08);
            }
        }
    }

    /**
     * Fireballs: spit 1–4 small fireballs at the target, burning for 3 seconds each.
     */
    private void castFireballs() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        int count = switch (phase) {
            case WATCHER -> 2 + random.nextInt(3);
            case HARVESTER -> 3 + random.nextInt(3);
            case JUDGEMENT -> 4 + random.nextInt(3);
        };
        world.playSound(caster.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.8F, 0.9F);

        BukkitRunnable shoot = new BukkitRunnable() {
            int fired = 0;
            @Override public void run() {
                if (fired >= count || isDead()) { cancel(); return; }
                fired++;
                Location from = getCurrentLocation().add(0, 1.5, 0);
                if (from.getWorld() == null) return;

                Vector dir = tgt.getEyeLocation().toVector().subtract(from.toVector()).normalize();
                dir.add(new Vector(
                    (random.nextDouble() - 0.5D) * 0.10D,
                    (random.nextDouble() - 0.5D) * 0.06D,
                    (random.nextDouble() - 0.5D) * 0.10D));

                SmallFireball fb = from.getWorld().spawn(from, SmallFireball.class);
                fb.setShooter(caster);
                fb.setDirection(dir);
                fb.setYield(phase == ScarecrowPhase.JUDGEMENT ? 0.8F : 0.5F);
                if (isFearMarked(tgt)) {
                    tgt.setFireTicks(Math.max(tgt.getFireTicks(), 55));
                }
                from.getWorld().playSound(from, Sound.ENTITY_BLAZE_SHOOT, 0.5F, 1.2F + random.nextFloat() * 0.3F);
            }
        };
        tasks.add(shoot);
        shoot.runTaskTimer(plugin, 0L, phase == ScarecrowPhase.JUDGEMENT ? 6L : 9L);
    }

    /**
     * Phantom (reworked): launches three fiery crows that dive the target in sequence.
     */
    private void castPhantom() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        Location origin = caster.getEyeLocation().clone();
        world.playSound(origin, Sound.ENTITY_PHANTOM_FLAP, 0.9F, 1.35F);
        world.playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.7F, 1.2F);

        for (int i = 0; i < 3; i++) {
            int delay = i * 10;
            BukkitRunnable crow = new BukkitRunnable() {
                Location pos = origin.clone().add(
                    ScarecrowNPC.this.randomDouble(-1.2D, 1.2D),
                    ScarecrowNPC.this.randomDouble(0.3D, 1.0D),
                    ScarecrowNPC.this.randomDouble(-1.2D, 1.2D));
                int t = 0;

                @Override public void run() {
                    t++;
                    if (t > 45 || isDead() || !tgt.isOnline() || tgt.isDead()) {
                        cancel();
                        return;
                    }
                    Location aim = tgt.getLocation().clone().add(0.0D, 1.1D, 0.0D);
                    Vector to = aim.toVector().subtract(pos.toVector());
                    if (to.lengthSquared() > 0.001D) {
                        pos.add(to.normalize().multiply(0.48D));
                    }

                    world.spawnParticle(Particle.SMOKE, pos, 2, 0.06D, 0.06D, 0.06D, 0.01D);
                    world.spawnParticle(Particle.FLAME, pos, 2, 0.05D, 0.05D, 0.05D, 0.01D);
                    world.spawnParticle(Particle.DUST, pos, 1, 0.03D, 0.03D, 0.03D, 0.0D, DUST_ORANGE);

                    if (pos.distanceSquared(aim) <= 1.15D) {
                        tgt.damage(3.0D + fearmongerStacks * 0.25D, caster);
                        tgt.setFireTicks(Math.max(tgt.getFireTicks(), 40));
                        tgt.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 50, 0, false, true, true));
                        markFear(tgt);
                        world.spawnParticle(Particle.FLAME, pos, 16, 0.25D, 0.25D, 0.25D, 0.03D);
                        world.playSound(pos, Sound.ENTITY_BLAZE_HURT, 0.55F, 1.4F);
                        cancel();
                    }
                }
            };
            tasks.add(crow);
            crow.runTaskTimer(plugin, delay, 1L);
        }
    }

    /**
     * Crowstorm (reworked): a firestorm of orange embers and flames orbiting the Scarecrow,
     * punishing any player who ventures within its burning radius.
     */
    private void castCrowstorm() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = ensureTarget(42.0D);
        if (tgt == null) return;
        World world = caster.getWorld();
        Location center = caster.getLocation().clone().add(0.0D, 1.0D, 0.0D);
        world.playSound(center, Sound.ENTITY_BLAZE_SHOOT, 1.0F, 0.7F);
        world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 0.6F, 0.6F);
        world.playSound(center, Sound.ENTITY_WITHER_AMBIENT, 0.4F, 0.75F);
        world.spawnParticle(Particle.DUST,  center.clone().add(0, 1, 0), 35, 1.2, 0.6, 1.2, 0, DUST_AMBER);
        world.spawnParticle(Particle.DUST,  center.clone().add(0, 1, 0), 20, 1.0, 0.4, 1.0, 0, DUST_ORANGE);
        world.spawnParticle(Particle.FLAME, center, 30, 1.2, 0.8, 1.2, 0.04);
        world.spawnParticle(Particle.LAVA,  center, 14, 0.8, 0.4, 0.8, 0);

        final double OUTER_RADIUS  = 3.6D;
        final double INNER_RADIUS  = 1.8D;
        final double DAMAGE_RADIUS = 4.0D;

        BukkitRunnable crowTask = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 140 || isDead() || !tgt.isOnline() || tgt.isDead()) {
                    cancel();
                    return;
                }
                // Storm center tracks the scarecrow
                Location sc = caster.getLocation().clone().add(0.0D, 1.5D, 0.0D);

                // Outer ring — 16 amber+flame orbit points
                for (int i = 0; i < 16; i++) {
                    double angle  = (Math.PI * 2.0D / 16.0D) * i + (t * 0.07D);
                    double radius = OUTER_RADIUS + Math.sin(t * 0.09D + i * 0.4D) * 0.4D;
                    double height = Math.sin(t * 0.07D + i * 0.5D) * 1.1D;
                    Location p = sc.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
                    if (t % 2 == 0) world.spawnParticle(Particle.DUST,  p, 2, 0.05, 0.05, 0.05, 0, DUST_AMBER);
                    if (t % 3 == 0) world.spawnParticle(Particle.FLAME, p, 1, 0.04, 0.04, 0.04, 0.01);
                }

                // Inner vortex — 8 orange points spinning faster
                for (int i = 0; i < 8; i++) {
                    double angle  = (Math.PI * 2.0D / 8.0D) * i + (t * 0.14D);
                    double radius = INNER_RADIUS + Math.sin(t * 0.13D + i) * 0.3D;
                    double height = 0.4D + Math.sin(t * 0.10D + i * 0.6D) * 0.7D;
                    Location p = sc.clone().add(Math.cos(angle) * radius, height, Math.sin(angle) * radius);
                    if (t % 2 == 0) world.spawnParticle(Particle.DUST,  p, 1, 0.04, 0.04, 0.04, 0, DUST_ORANGE);
                    if (t % 5 == 0) world.spawnParticle(Particle.LAVA,  p, 1, 0.08, 0.08, 0.08, 0);
                }

                // Ambient fire sound pulse
                if (t % 25 == 0) {
                    world.playSound(sc, Sound.BLOCK_FIRE_AMBIENT, 0.55F, 0.7F + random.nextFloat() * 0.3F);
                }

                // Punish players standing within the storm radius
                if (t % 8 == 0) {
                    double radiusSq = DAMAGE_RADIUS * DAMAGE_RADIUS;
                    for (Player p : world.getPlayers()) {
                        if (p.isDead() || p.getLocation().distanceSquared(caster.getLocation()) > radiusSq) continue;
                        p.damage(2.0D + fearmongerStacks * 0.15D, caster);
                        p.setFireTicks(Math.max(p.getFireTicks(), 40));
                        world.spawnParticle(Particle.DUST,  p.getLocation().add(0, 1, 0), 6, 0.25, 0.3, 0.25, 0, DUST_AMBER);
                        world.spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 5, 0.2,  0.2, 0.2,  0.02);
                    }
                }
            }
        };
        tasks.add(crowTask);
        crowTask.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Dark Wind: cyan/black bouncing projectile. Targets nearest enemy on each bounce;
     * up to 5 bounces. Can re-target the same player.
     */
    private void castDarkWind() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        world.playSound(caster.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.6F, 1.6F);

        final double speed = 0.55D;
        final int[] bouncesLeft = { 5 };
        Location startPos = caster.getEyeLocation().clone();
        Vector startVel = tgt.getEyeLocation().toVector().subtract(startPos.toVector()).normalize().multiply(speed);

        BukkitRunnable proj = new BukkitRunnable() {
            final Location pos = startPos.clone();
            Vector v = startVel.clone();
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 130 || isDead() || bouncesLeft[0] < 0) { cancel(); return; }
                pos.add(v);

                world.spawnParticle(Particle.DUST,  pos, 5, 0.12, 0.12, 0.12, 0, DUST_CYAN);
                world.spawnParticle(Particle.DUST,  pos, 3, 0.08, 0.08, 0.08, 0, DUST_BLACK);
                world.spawnParticle(Particle.SMOKE, pos, 1, 0.03, 0.03, 0.03, 0.01);

                for (Player p : world.getPlayers()) {
                    if (p.isDead() || p.getLocation().distanceSquared(pos) > 1.5D) continue;

                    double hitDamage = 4.5D + fearmongerStacks * 0.5D;
                    if (isFearMarked(p)) {
                        hitDamage += 1.8D;
                    }
                    if (phase == ScarecrowPhase.JUDGEMENT) {
                        hitDamage += 1.0D;
                    }
                    p.damage(hitDamage, caster);
                    markFear(p);
                    world.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0, DUST_CYAN);
                    world.playSound(pos, Sound.ENTITY_WITHER_HURT, 0.5F, 1.8F);

                    bouncesLeft[0]--;
                    if (bouncesLeft[0] < 0) { cancel(); return; }

                    // Find nearest player to bounce to (re-targeting same player allowed)
                    Player nearest = null;
                    double nearestDist = 16.0D; // 4 blocks
                    for (Player other : world.getPlayers()) {
                        if (other.isDead()) continue;
                        double d = other.getLocation().distanceSquared(pos);
                        if (d < nearestDist) { nearestDist = d; nearest = other; }
                    }
                    if (nearest != null) {
                        v = nearest.getEyeLocation().toVector().subtract(pos.toVector()).normalize().multiply(speed);
                    } else {
                        cancel();
                        return;
                    }
                    break;
                }
            }
        };
        tasks.add(proj);
        proj.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * High Jump (reworked): ignites a compact bonfire ring under the target area.
     */
    private void castHighJump() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = ensureTarget(34.0D);
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        Location center = tgt.getLocation().clone();
        world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 0.9F, 0.7F);
        world.playSound(center, Sound.BLOCK_FIRE_AMBIENT, 0.8F, 1.1F);

        BukkitRunnable ring = new BukkitRunnable() {
            int t = 0;

            @Override public void run() {
                t++;
                if (t > 70 || isDead()) {
                    cancel();
                    return;
                }

                double radius = 2.7D;
                for (int i = 0; i < 24; i++) {
                    double a = (Math.PI * 2.0D) * i / 24.0D;
                    Location flame = center.clone().add(Math.cos(a) * radius, 0.05D, Math.sin(a) * radius);
                    world.spawnParticle(Particle.FLAME, flame, 1, 0.02D, 0.02D, 0.02D, 0.005D);
                    world.spawnParticle(Particle.DUST, flame, 1, 0.01D, 0.01D, 0.01D, 0.0D, DUST_ORANGE);
                }

                if (t % 10 == 0) {
                    for (Player p : world.getPlayers()) {
                        if (p.isDead() || p.getLocation().distanceSquared(center) > radius * radius) continue;
                        p.damage(2.5D + fearmongerStacks * 0.2D, caster);
                        p.setFireTicks(Math.max(p.getFireTicks(), 45));
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true, true));
                    }
                }
            }
        };
        tasks.add(ring);
        ring.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Fearmonger (passive): every 2 seconds, search nearby blocks for fully grown wheat.
     * Each consumed crop heals 1 HP and adds 10% damage via Sentinel damage scaling.
     */
    private void tickFearmonger() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Location loc  = caster.getLocation();
        World world   = loc.getWorld();
        if (world == null) return;

        int radius = 6;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block block = world.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz);
                    if (block.getType() != Material.WHEAT) continue;
                    if (!(block.getBlockData() instanceof Ageable ageable)) continue;
                    if (ageable.getAge() < ageable.getMaximumAge()) continue;

                    block.setType(Material.AIR);
                    fearmongerStacks++;

                    // Scale sentinel damage
                    SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
                    s.damage = 4.0D * getFearmongerMultiplier();

                    var hpAttr = caster.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    double maxHp = hpAttr != null ? hpAttr.getValue() : 40.0D;
                    caster.setHealth(Math.min(maxHp, caster.getHealth() + 2.0D));

                    world.spawnParticle(Particle.DUST, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, DUST_CYAN);
                    world.playSound(block.getLocation(), Sound.BLOCK_CROP_BREAK, 0.7F, 1.2F);
                    return; // one crop per check
                }
            }
        }
    }

    /** Returns the total damage multiplier from Fearmonger stacks. */
    public double getFearmongerMultiplier() {
        return 1.0D + fearmongerStacks * 0.10D;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private LivingEntity getLivingEntity() {
        if (!npc.isSpawned() || npc.getEntity() == null) return null;
        return npc.getEntity() instanceof LivingEntity le ? le : null;
    }

    private Player ensureTarget(double radius) {
        if (target != null && target.isOnline() && !target.isDead()
                && target.getWorld() == getCurrentLocation().getWorld()
                && target.getLocation().distanceSquared(getCurrentLocation()) <= radius * radius) {
            return target;
        }
        target = findNearestPlayer(getCurrentLocation(), radius);
        return target;
    }

    private Player findNearestPlayer(Location from, double radius) {
        Player nearest = null;
        double best = radius * radius;
        if (from.getWorld() == null) return null;
        for (Player p : from.getWorld().getPlayers()) {
            if (p.isDead() || !p.isOnline()) continue;
            double dist = p.getLocation().distanceSquared(from);
            if (dist < best) { best = dist; nearest = p; }
        }
        return nearest;
    }

    private void cancelControllerOnly() {
        if (controllerTask != null) {
            try { controllerTask.cancel(); } catch (Exception ignored) {}
            controllerTask = null;
        }
    }

    private void cancelTasks() {
        for (BukkitRunnable t : tasks) { try { t.cancel(); } catch (Exception ignored) {} }
        tasks.clear();
    }

    private void cleanupWitherRoses() {
        for (Location l : new ArrayList<>(witherRoseLocations)) {
            if (l.getBlock().getType() == Material.WITHER_ROSE) l.getBlock().setType(Material.AIR);
        }
        witherRoseLocations.clear();
    }

    private void dropLoot(World world, Location location) {
        // --- Common harvest drops ---
        if (random.nextDouble() <= 0.72D) world.dropItemNaturally(location, new ItemStack(Material.WHEAT_SEEDS,    2 + random.nextInt(4)));
        if (random.nextDouble() <= 0.68D) world.dropItemNaturally(location, new ItemStack(Material.WHEAT,          2 + random.nextInt(4)));
        if (random.nextDouble() <= 0.55D) world.dropItemNaturally(location, new ItemStack(Material.BEETROOT_SEEDS, 2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.46D) world.dropItemNaturally(location, new ItemStack(Material.PUMPKIN_SEEDS,  1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.46D) world.dropItemNaturally(location, new ItemStack(Material.MELON_SEEDS,    1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.58D) world.dropItemNaturally(location, new ItemStack(Material.STICK,          2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.42D) world.dropItemNaturally(location, new ItemStack(Material.STRING,         1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.36D) world.dropItemNaturally(location, new ItemStack(Material.BONE_MEAL,      2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.30D) world.dropItemNaturally(location, new ItemStack(Material.BEETROOT,       1 + random.nextInt(2)));

        // Cursed Scarecrow's Shard — carved pumpkin with dark flavour
        if (random.nextDouble() <= 0.18D) {
            ItemStack shard = new ItemStack(Material.CARVED_PUMPKIN);
            ItemMeta m = shard.getItemMeta();
            if (m != null) {
                m.setDisplayName("§6Cursed Pumpkin");
                m.setLore(java.util.List.of("§7Hollow eyes... they still watch.", "§8(Scarecrow trophy)"));
                shard.setItemMeta(m);
            }
            world.dropItemNaturally(location, shard);
        }

        // Dark Harvest Scythe — iron hoe with lore
        if (random.nextDouble() <= 0.12D) {
            ItemStack scythe = new ItemStack(Material.GOLDEN_HOE);
            ItemMeta m = scythe.getItemMeta();
            if (m != null) {
                m.setDisplayName("§eDark Harvest Scythe");
                m.setLore(java.util.List.of("§7Reaped by the Scarecrow of the Blood Moon."));
                scythe.setItemMeta(m);
            }
            world.dropItemNaturally(location, scythe);
        }

        // Soul Lantern — spirit ward drop
        if (random.nextDouble() <= 0.15D) world.dropItemNaturally(location, new ItemStack(Material.SOUL_LANTERN,  1));

        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.JACK_O_LANTERN, 1));
        if (random.nextDouble() <= 0.09D) world.dropItemNaturally(location, new ItemStack(Material.PUMPKIN_PIE,   1));
        if (random.nextDouble() <= 0.09D) world.dropItemNaturally(location, new ItemStack(Material.HONEY_BOTTLE,  1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.FERMENTED_SPIDER_EYE, 1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.SPIDER_EYE, 1 + random.nextInt(2)));

        // Crow Feather — black dye representing crow feathers
        if (random.nextDouble() <= 0.20D) {
            ItemStack feather = new ItemStack(Material.INK_SAC, 1 + random.nextInt(3));
            ItemMeta m = feather.getItemMeta();
            if (m != null) {
                m.setDisplayName("§0Crow Feather");
                m.setLore(java.util.List.of("§7Shed by the Scarecrow's murder of crows."));
                feather.setItemMeta(m);
            }
            world.dropItemNaturally(location, feather);
        }

        // Hay Bale & Rare Drops
        if (random.nextDouble() <= 0.22D) world.dropItemNaturally(location, new ItemStack(Material.HAY_BLOCK, 1));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.SUSPICIOUS_STEW, 1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.LANTERN, 1));

        // Rare: enchanted bow or book
        if (random.nextDouble() <= 0.09D) {
            ItemStack bow = new ItemStack(Material.BOW);
            bow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.POWER, 2);
            world.dropItemNaturally(location, bow);
        }
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.ENCHANTED_BOOK, 1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_APPLE, 1));

        // Judgement Mask — jack o' lantern with unique name (very rare)
        if (random.nextDouble() <= 0.04D) {
            ItemStack mask = new ItemStack(Material.JACK_O_LANTERN);
            ItemMeta m = mask.getItemMeta();
            if (m != null) {
                m.setDisplayName("§c§lJudgement Mask");
                m.setLore(java.util.List.of("§7Worn by the Scarecrow during the final harvest.", "§8(Very rare trophy)"));
                mask.setItemMeta(m);
            }
            world.dropItemNaturally(location, mask);
        }

        // Experience
        int xp = 35 + random.nextInt(30);
        ExperienceOrb orb = (ExperienceOrb) world.spawnEntity(location, EntityType.EXPERIENCE_ORB);
        orb.setExperience(xp);
    }

    private List<LivingEntity> getDrainTargets(LivingEntity caster, double radius) {
        List<LivingEntity> targets = new ArrayList<>();
        for (Entity nearby : caster.getNearbyEntities(radius, radius * 0.6D, radius)) {
            if (!(nearby instanceof LivingEntity living)) {
                continue;
            }
            if (living.isDead() || living.equals(caster) || nearby instanceof ArmorStand) {
                continue;
            }
            if (nearby.hasMetadata("NPC")) {
                continue;
            }
            targets.add(living);
        }
        return targets;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void playCitizensPlayerAnimation(Player player, String animationName) {
        try {
            Class<? extends Enum> animationClass = Class.forName("net.citizensnpcs.util.PlayerAnimation").asSubclass(Enum.class);
            Enum animation = Enum.valueOf(animationClass, animationName);
            animationClass.getMethod("play", Player.class).invoke(animation, player);
        } catch (ReflectiveOperationException ignored) {}
    }

    /** Draws a particle line between two locations. */
    private void drawLine(Location a, Location b, Particle.DustOptions dust) {
        if (a.getWorld() == null || a.getWorld() != b.getWorld()) return;
        Vector step = b.toVector().subtract(a.toVector());
        double len  = step.length();
        if (len < 0.01D) return;
        step.normalize().multiply(0.4D);
        Location pos = a.clone();
        for (double d = 0; d < len; d += 0.4D) {
            pos.getWorld().spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, dust);
            pos.add(step);
        }
    }

    private void drawOrganicTendril(Location start, Location end, Particle.DustOptions dust, double phase, double wobble) {
        if (start.getWorld() == null || start.getWorld() != end.getWorld()) return;
        Vector line = end.toVector().subtract(start.toVector());
        double length = line.length();
        if (length < 0.01D) return;
        Vector direction = line.clone().normalize();
        Vector perpendicular = new Vector(-direction.getZ(), 0.0D, direction.getX());
        if (perpendicular.lengthSquared() < 0.01D) {
            perpendicular = new Vector(1.0D, 0.0D, 0.0D);
        } else {
            perpendicular.normalize();
        }
        Location point = start.clone();
        for (double step = 0.0D; step < length; step += 0.28D) {
            double wave = Math.sin(phase + step * 1.5D) * wobble;
            double lift = Math.cos(phase * 0.7D + step * 1.1D) * wobble * 0.45D;
            Vector offset = perpendicular.clone().multiply(wave).setY(lift);
            Location particle = point.clone().add(offset);
            particle.getWorld().spawnParticle(Particle.DUST, particle, 1, 0.0D, 0.0D, 0.0D, 0.0D, dust);
            point.add(direction.clone().multiply(0.28D));
        }
    }

    private double randomDouble(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }
}







