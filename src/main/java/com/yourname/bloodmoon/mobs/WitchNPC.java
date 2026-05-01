package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.traits.WitchTrait;
import java.lang.reflect.Method;
import java.util.*;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class WitchNPC {

    // ─── Appearance constants ─────────────────────────────────────────────────
    private static final Particle.DustOptions DUST_CRIMSON = new Particle.DustOptions(Color.fromRGB(180, 10,  10),  1.2F);
    private static final Particle.DustOptions DUST_VIOLET  = new Particle.DustOptions(Color.fromRGB(140,  0, 200),  1.0F);
    private static final Particle.DustOptions DUST_BLACK   = new Particle.DustOptions(Color.fromRGB( 30,   0,  30),  1.2F);
    private static final Particle.DustOptions DUST_AMBER   = new Particle.DustOptions(Color.fromRGB(255, 140,  20),  1.0F);
    private static final Particle.DustOptions DUST_FROST   = new Particle.DustOptions(Color.fromRGB(160, 220, 255),  1.1F);
    private static final Particle.DustOptions DUST_GOLD    = new Particle.DustOptions(Color.fromRGB(255, 200,   0),  1.0F);
    private static final Particle.DustOptions DUST_PINK    = new Particle.DustOptions(Color.fromRGB(255,  90, 200),  0.9F);

    // ─── Armor tier downgrade map (Netherite → Diamond → Iron → Gold → Leather)
    private static final Map<Material, Material> ARMOR_TIER_DOWN = new HashMap<>();
    static {
        String[] slots = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
        String[] tiers = {"NETHERITE", "DIAMOND", "IRON", "GOLDEN", "LEATHER"};
        for (String slot : slots) {
            for (int i = 0; i < tiers.length - 1; i++) {
                Material from = Material.matchMaterial(tiers[i]     + "_" + slot);
                Material to   = Material.matchMaterial(tiers[i + 1] + "_" + slot);
                if (from != null && to != null) ARMOR_TIER_DOWN.put(from, to);
            }
        }
    }

    // ─── Enums ────────────────────────────────────────────────────────────────
    private enum WitchState { COMBAT, CASTING, DEAD }
    private enum WitchPhase { COMPOSED, WRATH, UNRAVELING }
    private enum WitchAbility {
        // Signature
        SHARED_VESSEL, DEADLY_SPELL, HEX_CIRCLE, MIRROR_IMAGE,
        // Control
        ARMOR_CURSE, FREEZING_SPELL, VOID_CAGE, CURSE_OF_SILENCE, SWITCHING_SPELL, INVENTORY_SPELL,
        // Damage
        LIGHTNING_MARK, FIRE_SPELL, WILL_O_WISP, RAPID_FIRE, LIFE_DRAIN,
        // Utility / Summon
        POTION_VOLLEY, RUNE_TRAPS, ZOMBIFYING
    }

    // ─── Fields ───────────────────────────────────────────────────────────────
    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<WitchAbility, Integer> cooldowns = new EnumMap<>(WitchAbility.class);
    private final Map<WitchAbility, Integer> abilityUseCounts = new EnumMap<>(WitchAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private final List<Witch> clones = new ArrayList<>();
    private final List<Location> runeLocations = new ArrayList<>();

    // Deadly Spell accumulator
    private double deadlySpellAccumulated = 0.0D;
    private boolean brandActive = false;
    // Reactive reposition cooldown (ticks)
    private int repositionCooldown = 0;

    private WitchState state = WitchState.COMBAT;
    private WitchState beforeCast = WitchState.COMBAT;
    private WitchAbility pending;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private int stateTicks;
    private int castTicks;
    private boolean cleaned;
    private boolean deathStarted;
    private WitchPhase phase = WitchPhase.COMPOSED;
    private boolean wrathMirrorTriggered = false;
    private boolean unravelingTriggered = false;

    // ─── Constructor ──────────────────────────────────────────────────────────
    public WitchNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

    // ─── Public API ───────────────────────────────────────────────────────────
    public boolean isDead() { return state == WitchState.DEAD || cleaned || deathStarted; }

    public Location getCurrentLocation() {
        LivingEntity e = getLivingEntity();
        return e != null ? e.getLocation() : (lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone());
    }

    public double getCurrentHealth() {
        LivingEntity e = getLivingEntity();
        return e == null ? plugin.getConfigManager().getWitchHealth() : Math.max(0.0D, e.getHealth());
    }

    public double getMaximumHealth() {
        LivingEntity e = getLivingEntity();
        if (e == null) {
            return plugin.getConfigManager().getWitchHealth();
        }
        var attr = e.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? plugin.getConfigManager().getWitchHealth() : Math.max(1.0D, attr.getValue());
    }

    public void onTraitTick() {
        LivingEntity e = getLivingEntity();
        if (e != null) lastKnownLocation = e.getLocation();
    }

    public void onNpcSpawn() {
        LivingEntity e = getLivingEntity();
        if (e != null) applyConfiguredHealth(e);
    }

    /** Called by NPCListener when the witch takes a hit – feeds the Deadly Spell accumulator. */
    public void onTakeDamage(double damage) {
        if (brandActive && damage > 0) deadlySpellAccumulated += damage;
        if (damage > 0.0D && repositionCooldown <= 0 && random.nextDouble() < 0.40D) {
            doCircleStep();
            repositionCooldown = 40 + random.nextInt(20);
        }
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        if (!(event.getTarget() instanceof Player player) || state == WitchState.DEAD) return;
        target = player;
    }

    public void startDeathSequence() {
        if (deathStarted) return;
        deathStarted = true;
        state = WitchState.DEAD;
        cancelControllerOnly();
        cancelTasks();
        brandActive = false;
        runeLocations.clear();
        clones.forEach(c -> { if (c.isValid()) c.remove(); });
        clones.clear();
        Location death = getCurrentLocation();
        World world = death.getWorld();
        if (world != null) {
            world.playSound(death, Sound.ENTITY_WITCH_DEATH, 1.0F, 0.9F);
            world.spawnParticle(Particle.WITCH, death.clone().add(0, 1, 0), 30, 0.5, 0.6, 0.5, 0.2);
            dropLoot(world, death);
            if (random.nextDouble() <= Math.max(0.0D, plugin.getBloodMoonManager().getRewardMultiplier() - 1.0D)) {
                dropLoot(world, death);
            }
            ExperienceOrb orb = world.spawn(death.clone().add(0, 0.25, 0), ExperienceOrb.class);
            orb.setExperience((int) Math.max(1.0D,
                (45 + random.nextInt(20)) * plugin.getBloodMoonManager().getExpMultiplier()));
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
        brandActive = false;
        runeLocations.clear();
        clones.forEach(c -> { if (c.isValid()) c.remove(); });
        clones.clear();
        if (npc.isSpawned()) npc.despawn();
        npc.destroy();
        plugin.getNPCManager().unregisterWitch(npc.getId());
    }

    public void handleCloneHit(Entity entity) {
        if (entity instanceof Witch witch) {
            witch.getWorld().spawnParticle(Particle.SMOKE, witch.getLocation().add(0, 1, 0), 14, 0.2, 0.3, 0.2, 0.03);
            witch.getWorld().playSound(witch.getLocation(), Sound.ENTITY_WITCH_HURT, 0.7F, 1.3F);
            witch.remove();
            clones.remove(witch);
        }
    }

    // ─── NPC Setup ────────────────────────────────────────────────────────────
    private void configureNpc() {
        npc.data().set("bloodmoon-witch", true);
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        WitchTrait trait = npc.getOrAddTrait(WitchTrait.class);
        trait.bind(this);
        configureSkin();
        configureSentinel();
        if (!npc.isSpawned()) npc.spawn(spawnLocation.clone());
        LivingEntity e = getLivingEntity();
        if (e != null) { applyConfiguredHealth(e); hideNameplate(e); }
    }

    private void configureSkin() {
        String skinName  = plugin.getConfigManager().getWitchSkinName();
        String texture   = plugin.getConfigManager().getWitchSkinTexture();
        String signature = plugin.getConfigManager().getWitchSkinSignature();
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
            plugin.getLogger().warning("Could not apply witch skin: " + ex.getMessage());
        }
    }

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.62D) world.dropItemNaturally(location, new ItemStack(Material.REDSTONE, 2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.52D) world.dropItemNaturally(location, new ItemStack(Material.GLOWSTONE_DUST, 1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.48D) world.dropItemNaturally(location, new ItemStack(Material.GUNPOWDER, 1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.46D) world.dropItemNaturally(location, new ItemStack(Material.SPIDER_EYE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.32D) world.dropItemNaturally(location, new ItemStack(Material.FERMENTED_SPIDER_EYE, 1));
        if (random.nextDouble() <= 0.44D) world.dropItemNaturally(location, new ItemStack(Material.GLASS_BOTTLE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.38D) world.dropItemNaturally(location, new ItemStack(Material.SUGAR, 1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.34D) world.dropItemNaturally(location, new ItemStack(Material.STICK, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.30D) world.dropItemNaturally(location, new ItemStack(Material.NETHER_WART, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.20D) world.dropItemNaturally(location, new ItemStack(Material.MAGMA_CREAM, 1));

        if (random.nextDouble() <= 0.12D) world.dropItemNaturally(location, new ItemStack(Material.GHAST_TEAR, 1));
        if (random.nextDouble() <= 0.10D) world.dropItemNaturally(location, new ItemStack(Material.BLAZE_POWDER, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.10D) world.dropItemNaturally(location, new ItemStack(Material.ENDER_PEARL, 1));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.DRAGON_BREATH, 1));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.RABBIT_FOOT, 1));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, createPotionReward(Material.SPLASH_POTION, PotionType.POISON));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, createPotionReward(Material.SPLASH_POTION, PotionType.HARMING));
        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, createPotionReward(Material.LINGERING_POTION, PotionType.SLOWNESS));
        if (random.nextDouble() <= 0.06D) world.dropItemNaturally(location, new ItemStack(Material.ENCHANTED_BOOK, 1));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.GOLDEN_CARROT, 1));
    }

    private ItemStack createPotionReward(Material material, PotionType potionType) {
        ItemStack item = new ItemStack(material, 1);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(potionType);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void configureSentinel() {
        SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
        s.setInvincible(false);
        s.setHealth(plugin.getConfigManager().getWitchHealth());
        s.health = plugin.getConfigManager().getWitchHealth();
        s.damage = 4.5D;
        s.respawnTime = -1;
        s.chaseRange = 30.0D;
        s.armor = 0.1D;
        s.protectFromIgnores = false;
        s.allTargets = new SentinelTargetList();
        s.addTarget("players");
        s.allIgnores = new SentinelTargetList();
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
        double hp   = plugin.getConfigManager().getWitchHealth();
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
        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));
        if (repositionCooldown > 0) repositionCooldown--;
        onTraitTick();
        checkPhaseTransition();

        if (state == WitchState.CASTING) { tickCasting(); return; }
        if (state != WitchState.COMBAT) return;

        // Rune trap proximity check (every 4 ticks)
        if (!runeLocations.isEmpty() && stateTicks % 4 == 0) checkRuneProximity();

        Player player = ensureTarget(48.0D);
        if (player == null) { player = findNearestPlayer(getCurrentLocation(), 48.0D); target = player; }
        if (player == null) return;

        if (phase == WitchPhase.UNRAVELING) {
            npc.getNavigator().cancelNavigation();
        } else {
            npc.getNavigator().setTarget(player, true);
        }
        npc.faceLocation(player.getEyeLocation());
        if (stateTicks % 24 == 0) player.getWorld().playSound(getCurrentLocation(), Sound.ENTITY_WITCH_AMBIENT, 0.8F, 0.8F);
        int abilityInterval = Math.max(16, (int) Math.round(30.0D * plugin.getBloodMoonManager().getAbilityCadenceMultiplier()));
        if (stateTicks % abilityInterval == 0) {
            WitchAbility ability = chooseAbility();
            if (ability != null) startCasting(ability);
        }
    }

    private void tickCasting() {
        runCastingParticles();
        if (stateTicks < castTicks) return;
        WitchAbility ability = pending;
        pending = null;
        state = beforeCast;
        stateTicks = 0;
        castTicks = 0;
        if (ability != null) executeAbility(ability);
    }

    private void startCasting(WitchAbility ability) {
        if (state == WitchState.CASTING || state == WitchState.DEAD) return;
        pending  = ability;
        beforeCast = state;
        state    = WitchState.CASTING;
        stateTicks = 0;
        castTicks  = switch (ability) {
            case SHARED_VESSEL, DEADLY_SPELL, HEX_CIRCLE, MIRROR_IMAGE -> 35;
            default -> 20;
        };
        playCastingStartSound(ability);
    }

    private WitchAbility chooseAbility() {
        Player player = ensureTarget(48.0D);
        if (player == null) {
            return null;
        }

        if (cooldowns.getOrDefault(WitchAbility.ARMOR_CURSE, 0) <= 0 && playerHasHeavyArmor(player)) {
            return WitchAbility.ARMOR_CURSE;
        }

        if (cooldowns.getOrDefault(WitchAbility.SHARED_VESSEL, 0) <= 0) {
            long nearby = player.getWorld().getPlayers().stream()
                .filter(p -> !p.isDead())
                .filter(p -> p.getLocation().distanceSquared(getCurrentLocation()) <= 36.0D * 36.0D)
                .count();
            if (nearby >= 2) {
                return WitchAbility.SHARED_VESSEL;
            }
        }

        if (player.isSprinting() && player.getLocation().distanceSquared(getCurrentLocation()) < 100.0D) {
            if (cooldowns.getOrDefault(WitchAbility.VOID_CAGE, 0) <= 0) {
                return WitchAbility.VOID_CAGE;
            }
            if (cooldowns.getOrDefault(WitchAbility.FREEZING_SPELL, 0) <= 0) {
                return WitchAbility.FREEZING_SPELL;
            }
        }

        if (phase == WitchPhase.WRATH || phase == WitchPhase.UNRAVELING) {
            List<WitchAbility> wrathPool = List.of(
                WitchAbility.DEADLY_SPELL,
                WitchAbility.HEX_CIRCLE,
                WitchAbility.ARMOR_CURSE,
                WitchAbility.VOID_CAGE,
                WitchAbility.SWITCHING_SPELL,
                WitchAbility.LIFE_DRAIN,
                WitchAbility.WILL_O_WISP,
                WitchAbility.LIGHTNING_MARK,
                WitchAbility.MIRROR_IMAGE,
                WitchAbility.SHARED_VESSEL,
                WitchAbility.FIRE_SPELL,
                WitchAbility.POTION_VOLLEY,
                WitchAbility.RUNE_TRAPS,
                WitchAbility.ZOMBIFYING
            );
            List<WitchAbility> availableWrath = wrathPool.stream()
                .filter(a -> cooldowns.getOrDefault(a, 0) <= 0)
                .toList();
            if (!availableWrath.isEmpty()) {
                return availableWrath.get(random.nextInt(availableWrath.size()));
            }
        }

        List<WitchAbility> available = new ArrayList<>();
        for (WitchAbility ability : WitchAbility.values()) {
            if (cooldowns.getOrDefault(ability, 0) <= 0) {
                available.add(ability);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        int minUses = available.stream().mapToInt(a -> abilityUseCounts.getOrDefault(a, 0)).min().orElse(0);
        List<WitchAbility> underused = available.stream().filter(a -> abilityUseCounts.getOrDefault(a, 0) == minUses).toList();
        if (!underused.isEmpty() && random.nextDouble() <= 0.58D) {
            return underused.get(random.nextInt(underused.size()));
        }

        // Weighted pool: Signature weight 1, Control/Utility weight 2, Damage weight 3
        List<WitchAbility> pool = new ArrayList<>();
        for (WitchAbility a : available) {
            int weight = switch (a) {
                case SHARED_VESSEL, DEADLY_SPELL, HEX_CIRCLE, MIRROR_IMAGE -> 1;
                case ARMOR_CURSE, FREEZING_SPELL, VOID_CAGE, CURSE_OF_SILENCE, SWITCHING_SPELL, INVENTORY_SPELL -> 2;
                case LIGHTNING_MARK, FIRE_SPELL, WILL_O_WISP, RAPID_FIRE, LIFE_DRAIN -> 3;
                case POTION_VOLLEY, RUNE_TRAPS, ZOMBIFYING -> 2;
            };
            for (int i = 0; i < weight; i++) pool.add(a);
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private void executeAbility(WitchAbility ability) {
        abilityUseCounts.merge(ability, 1, Integer::sum);
        switch (ability) {
            case SHARED_VESSEL    -> castSharedVessel();
            case DEADLY_SPELL     -> castDeadlySpell();
            case HEX_CIRCLE       -> castHexCircle();
            case MIRROR_IMAGE     -> castMirrorImage();
            case ARMOR_CURSE      -> castArmorCurse();
            case FREEZING_SPELL   -> castFreezingSpell();
            case VOID_CAGE        -> castVoidCage();
            case CURSE_OF_SILENCE -> castCurseOfSilence();
            case SWITCHING_SPELL  -> castSwitchingSpell();
            case INVENTORY_SPELL  -> castInventorySpell();
            case LIGHTNING_MARK   -> castLightningMark();
            case FIRE_SPELL       -> castFireSpell();
            case WILL_O_WISP      -> castWillOWisp();
            case RAPID_FIRE       -> castRapidFire();
            case LIFE_DRAIN       -> castLifeDrain();
            case POTION_VOLLEY    -> castPotionVolley();
            case RUNE_TRAPS       -> castRuneTraps();
            case ZOMBIFYING       -> castZombifying();
        }
        cooldowns.put(ability, switch (ability) {
            case SHARED_VESSEL    -> 400;
            case DEADLY_SPELL     -> 300;
            case HEX_CIRCLE       -> 250;
            case MIRROR_IMAGE     -> 320;
            case ARMOR_CURSE      -> 280;
            case FREEZING_SPELL   -> 160;
            case VOID_CAGE        -> 200;
            case CURSE_OF_SILENCE -> 180;
            case SWITCHING_SPELL  -> 220;
            case INVENTORY_SPELL  -> 240;
            case LIGHTNING_MARK   -> 140;
            case FIRE_SPELL       -> 100;
            case WILL_O_WISP      -> 160;
            case RAPID_FIRE       -> 120;
            case LIFE_DRAIN       -> 200;
            case POTION_VOLLEY    -> 180;
            case RUNE_TRAPS       -> 280;
            case ZOMBIFYING       -> 500;
        });
    }

    private void runCastingParticles() {
        if (pending == null) return;
        Location base = getCurrentLocation().clone().add(0, 1.0D, 0);
        World world = base.getWorld();
        if (world == null) return;

        switch (pending) {
            case SHARED_VESSEL -> {
                double angle = stateTicks * 0.3D;
                for (int i = 0; i < 3; i++) {
                    double a = angle + (i * Math.PI * 2.0D / 3.0D);
                    Location tip = base.clone().add(Math.cos(a) * 2.5D, 0.2D, Math.sin(a) * 2.5D);
                    drawLine(base, tip, DUST_CRIMSON);
                }
                if (stateTicks % 8 == 0) world.playSound(base, Sound.ENTITY_WITHER_AMBIENT, 0.35F, 1.9F);
            }
            case HEX_CIRCLE -> {
                for (int i = 0; i < 6; i++) {
                    double a = (Math.PI * 2.0D) * i / 6.0D + stateTicks * 0.08D;
                    world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * 1.2D, -0.95D, Math.sin(a) * 1.2D), 2, 0.05, 0.02, 0.05, 0, DUST_VIOLET);
                }
                if (stateTicks % 5 == 0) world.playSound(base, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.3F, Math.min(1.8F, 0.5F + (stateTicks / (float) Math.max(1, castTicks))));
            }
            case DEADLY_SPELL -> {
                double progress = stateTicks / (double) Math.max(1, castTicks);
                double radius = 2.0D - (1.2D * progress);
                for (int i = 0; i < 16; i++) {
                    double a = (Math.PI * 2.0D) * i / 16.0D + stateTicks * 0.2D;
                    world.spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * radius, Math.sin(stateTicks * 0.1D) * 0.3D, Math.sin(a) * radius), 1, 0.02, 0.02, 0.02, 0, (i % 2 == 0) ? DUST_CRIMSON : DUST_BLACK);
                }
            }
            case MIRROR_IMAGE -> {
                world.spawnParticle(Particle.DUST, base, 10, 0.4, 0.5, 0.4, 0, DUST_VIOLET);
                world.spawnParticle(Particle.SMOKE, base, 6, 0.3, 0.3, 0.3, 0.03);
                if (stateTicks % 7 == 0) world.playSound(base, Sound.ENTITY_ENDERMAN_TELEPORT, 0.3F, 1.5F);
            }
            case ARMOR_CURSE -> world.spawnParticle(Particle.DUST, base, 8, 0.35, 0.25, 0.35, 0, DUST_BLACK);
            case FREEZING_SPELL -> world.spawnParticle(Particle.DUST, base, 8, 0.25, 0.25, 0.25, 0, DUST_FROST);
            case VOID_CAGE -> world.spawnParticle(Particle.DUST, base, 8, 0.35, 0.2, 0.35, 0, DUST_VIOLET);
            case CURSE_OF_SILENCE -> world.spawnParticle(Particle.DUST, base, 8, 0.3, 0.2, 0.3, 0, DUST_BLACK);
            case SWITCHING_SPELL -> world.spawnParticle(Particle.WITCH, base, 6, 0.2, 0.2, 0.2, 0.01);
            case INVENTORY_SPELL -> world.spawnParticle(Particle.DUST, base, 8, 0.35, 0.25, 0.35, 0, DUST_PINK);
            case LIGHTNING_MARK -> world.spawnParticle(Particle.DUST, base, 8, 0.3, 0.2, 0.3, 0, DUST_GOLD);
            case FIRE_SPELL -> world.spawnParticle(Particle.DUST, base, 8, 0.3, 0.2, 0.3, 0, DUST_AMBER);
            case WILL_O_WISP -> world.spawnParticle(Particle.SOUL_FIRE_FLAME, base, 6, 0.25, 0.25, 0.25, 0.01);
            case RAPID_FIRE -> world.spawnParticle(Particle.DUST, base, 8, 0.3, 0.2, 0.3, 0, DUST_PINK);
            case LIFE_DRAIN -> {
                world.spawnParticle(Particle.DUST, base, 8, 0.3, 0.2, 0.3, 0, DUST_BLACK);
                world.spawnParticle(Particle.DUST, base, 5, 0.2, 0.2, 0.2, 0, DUST_CRIMSON);
            }
            case POTION_VOLLEY -> world.spawnParticle(Particle.WITCH, base, 8, 0.3, 0.2, 0.3, 0.02);
            case RUNE_TRAPS -> world.spawnParticle(Particle.DUST, base, 8, 0.35, 0.2, 0.35, 0, DUST_VIOLET);
            case ZOMBIFYING -> world.spawnParticle(Particle.SMOKE, base, 8, 0.35, 0.25, 0.35, 0.02);
        }
    }

    private void playCastingStartSound(WitchAbility ability) {
        Location loc = getCurrentLocation();
        World world = loc.getWorld();
        if (world == null) return;
        switch (ability) {
            case SHARED_VESSEL -> world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 0.6F, 1.6F);
            case DEADLY_SPELL -> world.playSound(loc, Sound.ENTITY_WITHER_SHOOT, 0.7F, 1.2F);
            case HEX_CIRCLE -> world.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 0.7F, 0.7F);
            case MIRROR_IMAGE -> world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7F, 1.3F);
            case LIFE_DRAIN -> world.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 0.6F, 1.5F);
            default -> world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE, 0.5F, 0.8F + random.nextFloat() * 0.4F);
        }
    }

    // ─── SIGNATURE ABILITIES ─────────────────────────────────────────────────

    /** Shared Vessel – crimson thread binds nearby players; damage one, all receive it. */
    private void castSharedVessel() {
        Location casterLoc = getCurrentLocation();
        World world = casterLoc.getWorld();
        if (world == null) return;
        List<Player> nearby = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            if (!p.isDead() && p.getLocation().distanceSquared(casterLoc) <= 36.0D * 36.0D) nearby.add(p);
        }
        if (nearby.size() < 2) return;
        if (nearby.size() > 3) nearby.subList(3, nearby.size()).clear();
        List<Player> linked = List.copyOf(nearby);
        world.playSound(casterLoc, Sound.ENTITY_WITHER_AMBIENT,   0.7F, 1.8F);
        world.playSound(casterLoc, Sound.BLOCK_BEACON_ACTIVATE, 0.5F, 0.5F);

        Map<UUID, Double> lastHealth = new HashMap<>();
        for (Player p : linked) lastHealth.put(p.getUniqueId(), p.getHealth());
        Set<UUID> mirroringNow = new HashSet<>();

        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 200 || isDead()) { cancel(); return; }
                // Pulsing crimson thread between linked players
                if (t % 2 == 0) {
                    for (int i = 0; i < linked.size(); i++) {
                        for (int j = i + 1; j < linked.size(); j++) {
                            Player a = linked.get(i); Player b = linked.get(j);
                            if (a.isOnline() && b.isOnline()) drawLine(a.getEyeLocation(), b.getEyeLocation(), DUST_CRIMSON);
                        }
                    }
                }
                // Health-delta mirroring
                for (Player p : linked) {
                    if (!p.isOnline() || p.isDead()) continue;
                    double now  = p.getHealth();
                    double last = lastHealth.getOrDefault(p.getUniqueId(), now);
                    lastHealth.put(p.getUniqueId(), now);
                    double delta = now - last;
                    if (delta < -0.05D && mirroringNow.add(p.getUniqueId())) {
                        for (Player other : linked) {
                            if (other == p || !other.isOnline() || other.isDead() || !mirroringNow.add(other.getUniqueId())) continue;
                            other.damage(-delta);
                            other.getWorld().spawnParticle(Particle.DUST, other.getLocation().add(0, 1, 0), 5, 0.2, 0.3, 0.2, 0, DUST_CRIMSON);
                        }
                        Bukkit.getScheduler().runTaskLater(plugin, () -> mirroringNow.clear(), 1L);
                    }
                }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /** Deadly Spell – brand target with a curse mark; detonates at 5 s dealing direct + 50% absorbed witch-damage. */
    private void castDeadlySpell() {
        Player player = ensureTarget(35.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        World world = player.getWorld();
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0, DUST_CRIMSON);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0, DUST_BLACK);
        world.playSound(player.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.8F, 1.8F);
        brandActive = true;
        deadlySpellAccumulated = 0.0D;
        // Pulsing brand visual
        BukkitRunnable brandVisual = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 100 || !player.isOnline() || player.isDead()) { cancel(); return; }
                if (t % 10 == 0) {
                    player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 0.5, 0), 8, 0.25, 0.3, 0.25, 0, DUST_CRIMSON);
                    player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.5, 0), 5, 0.15, 0.1,  0.15, 0, DUST_BLACK);
                }
            }
        };
        tasks.add(brandVisual);
        brandVisual.runTaskTimer(plugin, 1L, 1L);
        // Detonation after 100 ticks (5 s)
        BukkitRunnable detonate = new BukkitRunnable() {
            @Override public void run() {
                brandActive = false;
                if (!player.isOnline() || player.isDead() || isDead()) return;
                double total = 6.0D + deadlySpellAccumulated * 0.5D;
                player.damage(total, caster);
                world.spawnParticle(Particle.FIREWORK, player.getLocation().add(0, 1, 0), 30, 0.4, 0.4, 0.4, 0.08);
                world.spawnParticle(Particle.DUST,     player.getLocation().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0, DUST_CRIMSON);
                world.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0F, 0.5F);
                world.playSound(player.getLocation(), Sound.ENTITY_WITHER_SHOOT,          0.8F, 0.6F);
            }
        };
        tasks.add(detonate);
        detonate.runTaskLater(plugin, 100L);
    }

    /** Hex Circle – slow-forming runic ring around player; seals and violently launches all inside. */
    private void castHexCircle() {
        Player player = ensureTarget(35.0D);
        if (player == null) return;
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 1, true, true, true));
        Location center = player.getLocation().clone();
        World world = center.getWorld();
        if (world == null) return;
        double radius = 3.5D;
        world.playSound(center, Sound.BLOCK_BEACON_AMBIENT, 1.0F, 0.5F);
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (isDead()) { cancel(); return; }
                if (player.isOnline() && !player.isDead() && t <= 80) {
                    Location current = player.getLocation();
                    center.setX(current.getX());
                    center.setY(current.getY());
                    center.setZ(current.getZ());
                }
                if (t > 80) {
                    // Seal: launch everything inside
                    world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL,    0.8F, 1.8F);
                    world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0F, 0.7F);
                    for (Entity e : world.getNearbyEntities(center, radius, 3.0D, radius)) {
                        if (e instanceof Player p && !p.isDead()) {
                            p.setVelocity(new Vector(0, 2.2D, 0));
                            p.damage(5.0D);
                        } else if (e instanceof LivingEntity le) {
                            le.setVelocity(new Vector(0, 1.8D, 0));
                        }
                    }
                    world.spawnParticle(Particle.DUST, center.clone().add(0, 0.5, 0), 80, radius, 0.3, radius, 0, DUST_VIOLET);
                    cancel(); return;
                }
                // Animated runic ring – density and colour shift over time
                int numPoints = 32 + t / 4;
                for (int i = 0; i < numPoints; i++) {
                    double angle = (Math.PI * 2.0D) * i / numPoints;
                    Location pt = center.clone().add(Math.cos(angle) * radius, 0.05D, Math.sin(angle) * radius);
                    world.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, t < 40 ? DUST_VIOLET : DUST_CRIMSON);
                }
                // Inner rune glyphs, slowly rotating
                if (t % 8 == 0) {
                    for (int i = 0; i < 6; i++) {
                        double a = (Math.PI * 2.0D) * i / 6.0D + t * 0.05D;
                        Location rune = center.clone().add(Math.cos(a) * radius * 0.5D, 0.05D, Math.sin(a) * radius * 0.5D);
                        world.spawnParticle(Particle.DUST, rune, 3, 0.1, 0.05, 0.1, 0, DUST_CRIMSON);
                    }
                    world.playSound(center, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.3F, 0.5F + (t / 80.0F) * 1.5F);
                }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /** Mirror Image – spawns 2-3 indistinguishable phantom witches that cast weakened spells. */
    private void castMirrorImage() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Location base = caster.getLocation();
        World world = base.getWorld();
        if (world == null) return;
        // Despawn existing clones first
        clones.forEach(c -> { if (c.isValid()) c.remove(); });
        clones.clear();
        world.playSound(base, Sound.ENTITY_ENDERMAN_TELEPORT, 0.9F, 1.3F);
        world.spawnParticle(Particle.SMOKE, base.clone().add(0, 1, 0), 25, 0.4, 0.4, 0.4, 0.04);
        int count = 2 + (random.nextDouble() < 0.4D ? 1 : 0); // 2 or 3
        for (int i = 0; i < count; i++) {
            Location spawnLoc = base.clone().add(randomDouble(-4, 4), 0, randomDouble(-4, 4));
            spawnLoc.setY(world.getHighestBlockYAt(spawnLoc) + 1.0D);
            Witch clone = world.spawn(spawnLoc, Witch.class);
            clone.setAI(true);
            clone.setSilent(false);
            clone.setMetadata("bloodmoon-witch-clone", new FixedMetadataValue(plugin, npc.getId()));
            clones.add(clone);
            world.spawnParticle(Particle.DUST, spawnLoc.clone().add(0, 1, 0), 15, 0.3, 0.4, 0.3, 0, DUST_VIOLET);
        }
        // Clones periodically fire weakened bolts
        BukkitRunnable cloneTask = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                clones.removeIf(c -> !c.isValid());
                if (t > 160 || clones.isEmpty() || isDead()) {
                    clones.forEach(c -> {
                        if (c.isValid()) {
                            c.getWorld().spawnParticle(Particle.SMOKE, c.getLocation().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.03);
                            c.remove();
                        }
                    });
                    clones.clear();
                    cancel(); return;
                }
                if (t % 25 == 0) {
                    Player nearestPlayer = findNearestPlayer(getCurrentLocation(), 40.0D);
                    for (Witch clone : clones) {
                        if (!clone.isValid() || nearestPlayer == null || nearestPlayer.isDead()) continue;
                        clone.setTarget(nearestPlayer);
                        Vector dir = nearestPlayer.getLocation().add(0, 1, 0).toVector().subtract(clone.getLocation().toVector());
                        if (dir.lengthSquared() > 0.001D) dir.normalize();
                        Location cur = clone.getLocation().clone().add(0, 1.2, 0);
                        World w = clone.getWorld();
                        for (int i = 0; i < 12; i++) {
                            cur.add(dir.clone().multiply(0.4D));
                            w.spawnParticle(Particle.DUST, cur, 1, 0.04, 0.04, 0.04, 0, DUST_VIOLET);
                        }
                        if (nearestPlayer.getLocation().distanceSquared(cur) <= 8.0D) nearestPlayer.damage(1.2D);
                    }
                }
            }
        };
        tasks.add(cloneTask);
        cloneTask.runTaskTimer(plugin, 1L, 1L);
    }

    // ─── CONTROL ABILITIES ────────────────────────────────────────────────────

    /** Armor Curse – black mist at player's location degrades armor tier-by-tier while inside. */
    private void castArmorCurse() {
        Player player = ensureTarget(30.0D);
        if (player == null) return;
        Location mistCenter = player.getLocation().clone();
        World world = mistCenter.getWorld();
        if (world == null) return;
        double mistRadius = 3.5D;
        world.playSound(mistCenter, Sound.ENTITY_WITHER_AMBIENT, 0.7F, 1.6F);
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 220 || isDead()) { cancel(); return; }
                // Black mist visual
                if (t % 3 == 0) {
                    for (int i = 0; i < 8; i++) {
                        double dx = randomDouble(-mistRadius, mistRadius);
                        double dz = randomDouble(-mistRadius, mistRadius);
                        if (dx * dx + dz * dz > mistRadius * mistRadius) continue;
                        world.spawnParticle(Particle.SMOKE, mistCenter.clone().add(dx, random.nextDouble() * 1.5D, dz), 1, 0.1, 0.05, 0.1, 0.003);
                        world.spawnParticle(Particle.DUST,  mistCenter.clone().add(dx, random.nextDouble() * 1.0D, dz), 1, 0, 0, 0, 0, DUST_BLACK);
                    }
                }
                // Armor degradation every 30 ticks while player is in mist
                if (t % 30 == 0) {
                    for (Player p : world.getPlayers()) {
                        if (!p.isDead() && p.getLocation().distanceSquared(mistCenter) <= mistRadius * mistRadius) {
                            degradeArmor(p, world);
                        }
                    }
                }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void degradeArmor(Player player, World world) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean degraded = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || piece.getType().isAir()) continue;
            Material down = ARMOR_TIER_DOWN.get(piece.getType());
            if (down == null) continue;
            ItemStack replacement = new ItemStack(down, 1);
            ItemMeta newMeta = replacement.getItemMeta();
            if (newMeta != null && piece.getItemMeta() != null) {
                piece.getItemMeta().getEnchants().forEach((ench, level) -> newMeta.addEnchant(ench, level, true));
                replacement.setItemMeta(newMeta);
            }
            armor[i] = replacement;
            degraded  = true;
        }
        if (degraded) {
            player.getInventory().setArmorContents(armor);
            world.spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.05);
            world.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.9F, 0.8F);
            player.sendMessage("\u00a75Your armor has been cursed by the witch!");
        }
    }

    /** Freezing Spell – slow icy projectile; flash-freezes target for 2 s on contact. */
    private void castFreezingSpell() {
        Player player = ensureTarget(30.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        Location from = caster.getEyeLocation();
        World world = from.getWorld();
        if (world == null) return;
        world.playSound(from, Sound.BLOCK_POWDER_SNOW_STEP, 1.0F, 0.5F);
        world.playSound(from, Sound.ENTITY_GUARDIAN_ATTACK,  0.5F, 1.8F);
        // Slow simulated projectile (0.28 blocks/tick ≈ 5.6 m/s)
        Vector vel = player.getEyeLocation().toVector().subtract(from.toVector()).normalize().multiply(0.28D);
        Location[] pos = { from.clone() };
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 90 || isDead()) { cancel(); return; }
                pos[0].add(vel);
                world.spawnParticle(Particle.DUST, pos[0], 4, 0.10, 0.10, 0.10, 0, DUST_FROST);
                world.spawnParticle(Particle.DUST, pos[0], 2, 0.06, 0.06, 0.06, 0, new Particle.DustOptions(Color.WHITE, 0.8F));
                for (Player p : world.getPlayers()) {
                    if (!p.isDead() && p.getLocation().add(0, 1, 0).distanceSquared(pos[0]) <= 1.0D) {
                        p.setFreezeTicks(40);
                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 4, true, true, true));
                        world.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0, DUST_FROST);
                        world.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.0F, 0.6F);
                        cancel(); return;
                    }
                }
                if (pos[0].getBlock().getType().isSolid()) { cancel(); }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /** Void Cage – invisible arcane barrier traps player for 3-4 s. Enforced by PlayerListener. */
    private void castVoidCage() {
        Player player = ensureTarget(30.0D);
        if (player == null) return;
        World world = player.getWorld();
        if (world == null) return;
        Location cageCenter = player.getLocation().clone();
        double cageRadius   = 2.5D;
        int    duration     = 60 + random.nextInt(20);
        // Encode cage data as metadata on the player
        player.setMetadata("bloodmoon-witch-void-cage",
            new FixedMetadataValue(plugin,
                cageCenter.getX() + "," + cageCenter.getY() + "," + cageCenter.getZ() + "," + cageRadius));
        world.playSound(cageCenter, Sound.BLOCK_BEACON_ACTIVATE, 0.8F, 1.8F);
        // Visual outline + auto-remove
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > duration || isDead() || !player.isOnline()) {
                    player.removeMetadata("bloodmoon-witch-void-cage", plugin);
                    cancel(); return;
                }
                Location pl = player.getLocation();
                double dx = pl.getX() - cageCenter.getX();
                double dz = pl.getZ() - cageCenter.getZ();
                double distSq = dx * dx + dz * dz;
                double maxSq = cageRadius * cageRadius;
                if (distSq > maxSq) {
                    Vector pushBack = new Vector(-dx, 0.15D, -dz).normalize().multiply(0.55D);
                    player.setVelocity(pushBack);
                    player.teleport(cageCenter.clone().add(dx * 0.85D, 0, dz * 0.85D));
                }
                if (t % 4 == 0) {
                    for (int i = 0; i < 24; i++) {
                        double a = (Math.PI * 2.0D) * i / 24.0D;
                        world.spawnParticle(Particle.DUST, cageCenter.clone().add(Math.cos(a) * cageRadius, 0.05D, Math.sin(a) * cageRadius), 1, 0, 0, 0, 0, DUST_VIOLET);
                        world.spawnParticle(Particle.DUST, cageCenter.clone().add(Math.cos(a) * cageRadius, 1.50D, Math.sin(a) * cageRadius), 1, 0, 0, 0, 0, DUST_VIOLET);
                    }
                }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /** Curse of Silence – strips player of hotbar/item control for 3 s. Enforced by PlayerListener. */
    private void castCurseOfSilence() {
        Player player = ensureTarget(30.0D);
        if (player == null) return;
        World world = player.getWorld();
        if (world == null) return;
        long expiry = System.currentTimeMillis() + 3000L;
        player.setMetadata("bloodmoon-witch-silenced", new FixedMetadataValue(plugin, expiry));
        world.playSound(player.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.7F, 1.9F);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1.5, 0), 15, 0.3, 0.2, 0.3, 0, DUST_BLACK);
        // Auto-cleanup slightly after expiry
        BukkitRunnable cleanup = new BukkitRunnable() {
            @Override public void run() {
                if (player.isOnline()) player.removeMetadata("bloodmoon-witch-silenced", plugin);
            }
        };
        tasks.add(cleanup);
        cleanup.runTaskLater(plugin, 63L);
    }

    /** Switching Spell – instantly swap positions; witch absorbs copies of player's active potion effects. */
    private void castSwitchingSpell() {
        Player player = ensureTarget(30.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        Location witchLoc  = caster.getLocation().clone();
        Location playerLoc = player.getLocation().clone();
        World world = witchLoc.getWorld();
        if (world == null) return;
        // Absorb player's potion effects
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            caster.addPotionEffect(new PotionEffect(effect.getType(), effect.getDuration(), effect.getAmplifier(), true, false));
        }
        // Swap
        player.teleport(witchLoc);
        npc.teleport(playerLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        world.spawnParticle(Particle.DUST, witchLoc.clone().add(0,  1, 0), 30, 0.5, 0.7, 0.5, 0, DUST_VIOLET);
        world.spawnParticle(Particle.DUST, playerLoc.clone().add(0, 1, 0), 30, 0.5, 0.7, 0.5, 0, DUST_VIOLET);
        world.playSound(witchLoc,  Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.7F);
        world.playSound(playerLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.3F);

        BukkitRunnable followUp = new BukkitRunnable() {
            @Override public void run() {
                if (isDead()) return;
                WitchAbility chained = cooldowns.getOrDefault(WitchAbility.LIFE_DRAIN, 0) <= 0
                    ? WitchAbility.LIFE_DRAIN
                    : WitchAbility.LIGHTNING_MARK;
                executeAbility(chained);
            }
        };
        tasks.add(followUp);
        followUp.runTaskLater(plugin, 8L);
    }

    /** Inventory Spell – large slow projectile that scatters the player's entire inventory on impact. */
    private void castInventorySpell() {
        Player player = ensureTarget(32.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        Location from = caster.getEyeLocation();
        World world = from.getWorld();
        if (world == null) return;
        world.playSound(from, Sound.ENTITY_WITCH_THROW, 1.0F, 0.6F);
        Vector vel = player.getEyeLocation().toVector().subtract(from.toVector()).normalize().multiply(0.22D);
        Location[] pos = { from.clone() };
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 120 || isDead()) { cancel(); return; }
                pos[0].add(vel);
                world.spawnParticle(Particle.WITCH,  pos[0], 4, 0.15, 0.15, 0.15, 0.01);
                world.spawnParticle(Particle.DUST,   pos[0], 2, 0.10, 0.10, 0.10, 0, DUST_VIOLET);
                for (Player p : world.getPlayers()) {
                    if (!p.isDead() && p.getLocation().add(0, 1, 0).distanceSquared(pos[0]) <= 1.5D) {
                        scatterInventory(p);
                        p.damage(3.5D, caster);
                        world.spawnParticle(Particle.FIREWORK, pos[0], 20, 0.3, 0.3, 0.3, 0.05);
                        world.playSound(pos[0], Sound.ENTITY_WITCH_THROW, 1.0F, 0.4F);
                        cancel(); return;
                    }
                }
                if (pos[0].getBlock().getType().isSolid()) { cancel(); }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void scatterInventory(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) items.add(item.clone());
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) items.add(item.clone());
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (!offhand.getType().isAir()) items.add(offhand.clone());
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        for (ItemStack item : items) {
            Location dropLoc = center.clone().add(randomDouble(-4, 4), 0.3D, randomDouble(-4, 4));
            Item dropped = world.dropItem(dropLoc, item);
            dropped.setVelocity(new Vector(
                randomDouble(-0.3, 0.3),
                0.3D + random.nextDouble() * 0.3D,
                randomDouble(-0.3, 0.3)));
        }
        world.playSound(center, Sound.ENTITY_ITEM_PICKUP, 1.0F, 0.4F);
        world.spawnParticle(Particle.WITCH, center.clone().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.02);
    }

    // ─── DAMAGE ABILITIES ─────────────────────────────────────────────────────

    /** Lightning Mark – glowing sigils on 2-4 blocks near player; delayed energy strike, area damage + ignition. */
    private void castLightningMark() {
        Player player = ensureTarget(35.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        World world = player.getWorld();
        int count = 2 + random.nextInt(3);
        List<Location> marks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location markLoc = player.getLocation().clone().add(randomDouble(-3.5D, 3.5D), 0, randomDouble(-3.5D, 3.5D));
            markLoc.setY(world.getHighestBlockYAt(markLoc) + 0.1D);
            marks.add(markLoc);
        }
        world.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.8F, 1.8F);
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (isDead()) { cancel(); return; }
                for (Location mark : marks) {
                    world.spawnParticle(Particle.DUST,  mark, 5, 0.3, 0.05, 0.3, 0, t < 30 ? DUST_GOLD : DUST_CRIMSON);
                    if (t % 6 == 0) world.spawnParticle(Particle.CRIT, mark.clone().add(0, 0.1, 0), 3, 0.2, 0.05, 0.2, 0.02);
                }
                if (t >= 50) {
                    for (Location mark : marks) {
                        world.strikeLightningEffect(mark);
                        world.getBlockAt(mark.getBlockX(), mark.getBlockY() - 1, mark.getBlockZ()).setType(Material.FIRE);
                        world.spawnParticle(Particle.CRIT, mark.clone().add(0, 0.5, 0), 25, 0.5, 0.8, 0.5, 0.1);
                        for (Player p : world.getPlayers()) {
                            if (!p.isDead() && p.getLocation().distanceSquared(mark) <= 9.0D) {
                                p.damage(5.5D, caster);
                                p.setFireTicks(Math.max(p.getFireTicks(), 40));
                            }
                        }
                    }
                    cancel();
                }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /** Fire Spell – arcing amber-trail fireball; impact damage + 2 s ablaze. */
    private void castFireSpell() {
        Player player = ensureTarget(32.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        Location from = caster.getEyeLocation();
        World world = from.getWorld();
        if (world == null) return;
        world.playSound(from, Sound.ENTITY_BLAZE_SHOOT, 1.0F, 0.8F);
        // Horizontal velocity toward target with slight upward arc
        Vector base = player.getLocation().add(0, 1, 0).toVector().subtract(from.toVector()).normalize().multiply(0.55D);
        double[] vy = { base.getY() + 0.06D };
        double gravity = -0.006D;
        Location[] pos = { from.clone() };
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 65 || isDead()) { cancel(); return; }
                vy[0] += gravity;
                pos[0].add(base.getX(), vy[0], base.getZ());
                world.spawnParticle(Particle.FLAME, pos[0], 5, 0.10, 0.10, 0.10, 0.01);
                world.spawnParticle(Particle.DUST,  pos[0], 3, 0.08, 0.08, 0.08, 0, DUST_AMBER);
                world.spawnParticle(Particle.LAVA,  pos[0], 1, 0.05, 0.05, 0.05, 0.01);
                for (Player p : world.getPlayers()) {
                    if (!p.isDead() && p.getLocation().add(0, 1, 0).distanceSquared(pos[0]) <= 1.2D) {
                        p.damage(6.0D, caster);
                        p.setFireTicks(Math.max(p.getFireTicks(), 40));
                        world.spawnParticle(Particle.FLAME, pos[0], 20, 0.4, 0.4, 0.4, 0.05);
                        cancel(); return;
                    }
                }
                if (pos[0].getBlock().getType().isSolid()) { cancel(); }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    /** Will-O-Wisp – homing blue flame that burns target for 5 s on contact. */
    private void castWillOWisp() {
        Player player = ensureTarget(35.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        Location casterLoc = caster.getLocation();
        World world = casterLoc.getWorld();
        if (world == null) return;
        int count = 1 + (random.nextDouble() < 0.4D ? 1 : 0);
        world.playSound(casterLoc, Sound.ENTITY_BLAZE_AMBIENT, 0.8F, 1.8F);
        for (int w = 0; w < count; w++) {
            Location[] pos = { casterLoc.clone().add(randomDouble(-2, 2), 1.5D, randomDouble(-2, 2)) };
            BukkitRunnable task = new BukkitRunnable() {
                int t = 0;
                @Override public void run() {
                    t++;
                    if (t > 160 || isDead() || !player.isOnline() || player.isDead()) { cancel(); return; }
                    // Home toward player
                    Vector step = player.getLocation().add(0, 1, 0).toVector().subtract(pos[0].toVector());
                    double len = step.length();
                    if (len > 0.01D) pos[0].add(step.normalize().multiply(Math.min(0.18D, len)));
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, pos[0], 4, 0.08, 0.08, 0.08, 0.005);
                    world.spawnParticle(Particle.DUST,            pos[0], 2, 0.05, 0.05, 0.05, 0, DUST_FROST);
                    if (pos[0].distanceSquared(player.getLocation().add(0, 1, 0)) <= 0.7D) {
                        player.setFireTicks(Math.max(player.getFireTicks(), 100));
                        player.damage(2.5D, caster);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, pos[0], 20, 0.4, 0.4, 0.4, 0.02);
                        world.playSound(pos[0], Sound.ENTITY_BLAZE_SHOOT, 0.7F, 1.6F);
                        cancel();
                    }
                }
            };
            tasks.add(task);
            task.runTaskTimer(plugin, 2L * w + 1L, 1L);
        }
    }

    /** Rapid Fire – 3-7 pinkish arcane bolts in quick succession, each dealing light damage. */
    private void castRapidFire() {
        Player player = ensureTarget(36.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null) return;
        Location from = getCurrentLocation().clone().add(0, 1.2D, 0);
        World world = from.getWorld();
        if (world == null) return;
        int shots = 3 + random.nextInt(5);
        BukkitRunnable task = new BukkitRunnable() {
            int left = shots;
            @Override public void run() {
                if (left-- <= 0 || player.isDead() || isDead()) { cancel(); return; }
                Vector dir = player.getLocation().add(0, 1, 0).toVector().subtract(from.toVector());
                if (dir.lengthSquared() > 0.001D) dir.normalize();
                dir.add(new Vector(randomDouble(-0.06, 0.06), randomDouble(-0.03, 0.03), randomDouble(-0.06, 0.06)));
                Location cur = from.clone();
                for (int i = 0; i < 16; i++) {
                    cur.add(dir.clone().multiply(0.5D));
                    world.spawnParticle(Particle.DUST, cur, 1, 0.04, 0.04, 0.04, 0, DUST_PINK);
                }
                if (caster != null && player.getLocation().distanceSquared(cur) <= 6.0D) {
                    player.damage(2.0D, caster);
                }
                world.playSound(from, Sound.BLOCK_NOTE_BLOCK_PLING, 0.3F, 1.5F + random.nextFloat() * 0.5F);
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 0L, 7L);
    }

    /** Life Drain – dark energy beam that heals witch equal to damage dealt; interrupted by range or LOS break. */
    private void castLifeDrain() {
        Player player = ensureTarget(20.0D);
        LivingEntity caster = getLivingEntity();
        if (player == null || caster == null) return;
        World world = caster.getWorld();
        if (world == null) return;
        npc.getNavigator().cancelNavigation();
        world.playSound(caster.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.8F, 1.4F);
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                LivingEntity e = getLivingEntity();
                if (t > 80 || isDead() || e == null) { cancel(); return; }
                npc.getNavigator().cancelNavigation();
                if (!player.isOnline() || player.isDead()) { cancel(); return; }
                if (e.getLocation().distanceSquared(player.getLocation()) > 400.0D || !e.hasLineOfSight(player)) { cancel(); return; }
                drawLine(e.getEyeLocation(), player.getEyeLocation(), DUST_BLACK);
                if (t % 8 == 0) {
                    double dmg = 1.5D;
                    player.damage(dmg, e);
                    var attr = e.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    if (attr != null) e.setHealth(Math.min(attr.getValue(), e.getHealth() + dmg));
                    world.spawnParticle(Particle.DUST, e.getLocation().add(0, 1.2, 0), 6, 0.3, 0.3, 0.3, 0, DUST_CRIMSON);
                }
            }
        };
        tasks.add(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    // ─── UTILITY / SUMMON ABILITIES ───────────────────────────────────────────

    /** Potion Volley – spread of Slowness, Weakness, and Poison potions in a wide arc. */
    private void castPotionVolley() {
        LivingEntity caster = getLivingEntity();
        Player player = ensureTarget(32.0D);
        if (caster == null || player == null) return;
        World world = caster.getWorld();
        if (world == null) return;
        world.playSound(caster.getLocation(), Sound.ENTITY_WITCH_THROW, 1.0F, 0.9F);
        PotionEffectType[] types = { PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.POISON };
        Vector base = player.getLocation().add(0, 1, 0).toVector().subtract(caster.getEyeLocation().toVector()).normalize().multiply(0.6D);
        for (int i = 0; i < 5; i++) {
            double spread = randomDouble(-0.15D, 0.15D);
            Vector vel = base.clone().add(new Vector(spread, randomDouble(0, 0.12D), spread));
            PotionEffectType chosenType = types[random.nextInt(types.length)];
            BukkitRunnable task = new BukkitRunnable() {
                final Location pos = caster.getEyeLocation().clone();
                int t = 0;
                @Override public void run() {
                    t++;
                    if (t > 55 || isDead()) { cancel(); return; }
                    pos.add(vel);
                    world.spawnParticle(Particle.WITCH, pos, 3, 0.10, 0.10, 0.10, 0.01);
                    for (Player p : world.getPlayers()) {
                        if (!p.isDead() && p.getLocation().add(0, 1, 0).distanceSquared(pos) <= 2.5D) {
                            int dur = chosenType == PotionEffectType.POISON ? 80 : 60;
                            p.addPotionEffect(new PotionEffect(chosenType, dur, 0, true, true, true));
                            world.spawnParticle(Particle.WITCH, pos, 15, 0.3, 0.3, 0.3, 0.02);
                            cancel(); return;
                        }
                    }
                    if (pos.getBlock().getType().isSolid()) { cancel(); }
                }
            };
            tasks.add(task);
            task.runTaskTimer(plugin, (long)(i * 3), 1L);
        }
    }

    /** Rune Traps – silently places 3-5 invisible runes; triggers Slowness, Blindness, or damage on step. */
    private void castRuneTraps() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player player = ensureTarget(35.0D);
        Location base = player != null ? player.getLocation() : getCurrentLocation();
        World world = base.getWorld();
        if (world == null) return;
        int count = 3 + random.nextInt(3);
        for (int i = 0; i < count; i++) {
            Location rune = base.clone().add(randomDouble(-5D, 5D), 0, randomDouble(-5D, 5D));
            rune.setY(world.getHighestBlockYAt(rune) + 0.05D);
            runeLocations.add(rune.clone());
        }
        world.playSound(base, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7F, 0.6F);
    }

    private void checkRuneProximity() {
        World world = spawnLocation.getWorld();
        if (world == null) return;
        Iterator<Location> iter = runeLocations.iterator();
        while (iter.hasNext()) {
            Location rune = iter.next();
            if (!Objects.equals(rune.getWorld(), world)) { iter.remove(); continue; }
            boolean triggered = false;
            for (Player p : world.getPlayers()) {
                if (!p.isDead() && p.getLocation().distanceSquared(rune) <= 1.0D) {
                    triggerRune(rune, p);
                    triggered = true;
                    break;
                }
            }
            if (triggered) { iter.remove(); continue; }
            // Very subtle ambient particle so rune isn't completely invisible (reward for sharp eyes)
            if (random.nextDouble() < 0.08D) world.spawnParticle(Particle.DUST, rune, 1, 0.04, 0.02, 0.04, 0, DUST_VIOLET);
        }
    }

    private void triggerRune(Location rune, Player player) {
        World world = rune.getWorld();
        if (world == null) return;
        world.spawnParticle(Particle.DUST, rune.clone().add(0, 0.5, 0), 20, 0.4, 0.3, 0.4, 0, DUST_VIOLET);
        world.playSound(rune, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0F, 1.5F);
        LivingEntity caster = getLivingEntity();
        switch (random.nextInt(3)) {
            case 0 -> player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, true, true, true));
            case 1 -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, true, true));
                world.playSound(rune, Sound.ENTITY_ENDERMAN_SCREAM, 0.5F, 1.6F);
            }
            case 2 -> {
                if (caster != null) player.damage(4.0D, caster);
                world.spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.04);
            }
        }
    }

    /** Zombifying – channels briefly then corrupts nearby villagers into hostile Zombie Villagers. */
    private void castZombifying() {
        Location casterLoc = getCurrentLocation();
        World world = casterLoc.getWorld();
        if (world == null) return;
        List<Villager> villagers = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(casterLoc, 20, 10, 20)) {
            if (e instanceof Villager v && !v.isDead()) villagers.add(v);
        }
        if (villagers.isEmpty()) return;
        world.playSound(casterLoc, Sound.ENTITY_WITHER_SPAWN, 0.6F, 1.8F);
        for (Villager villager : villagers) {
            BukkitRunnable channelFx = new BukkitRunnable() {
                int t = 0;
                @Override public void run() {
                    t++;
                    if (t > 30 || !villager.isValid()) { cancel(); return; }
                    villager.getWorld().spawnParticle(Particle.DUST,  villager.getLocation().add(0, 1, 0),   6, 0.3, 0.4, 0.3, 0, DUST_BLACK);
                    villager.getWorld().spawnParticle(Particle.SMOKE, villager.getLocation().add(0, 0.5, 0), 3, 0.2, 0.2, 0.2, 0.02);
                }
            };
            tasks.add(channelFx);
            channelFx.runTaskTimer(plugin, 1L, 1L);
            BukkitRunnable convert = new BukkitRunnable() {
                @Override public void run() {
                    if (!villager.isValid() || isDead()) return;
                    Location vl = villager.getLocation();
                    villager.remove();
                    ZombieVillager zv = (ZombieVillager) vl.getWorld().spawnEntity(vl, EntityType.ZOMBIE_VILLAGER);
                    Player nearest = findNearestPlayer(vl, 40.0D);
                    if (nearest != null) zv.setTarget(nearest);
                    vl.getWorld().playSound(vl, Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0F, 0.9F);
                    vl.getWorld().spawnParticle(Particle.SMOKE, vl.clone().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0.03);
                }
            };
            tasks.add(convert);
            convert.runTaskLater(plugin, 30L);
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Short-range evasive warp used during combat to make the witch difficult to target. */
    private void doCircleStep() {
        Player player = ensureTarget(30.0D);
        LivingEntity entity = getLivingEntity();
        if (player == null || entity == null) return;
        Location origin = entity.getLocation();
        World world = origin.getWorld();
        if (world == null) return;

        Location pivot = player.getLocation();
        double dx = origin.getX() - pivot.getX();
        double dz = origin.getZ() - pivot.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5D) dist = 5.0D;

        double angle = Math.atan2(dz, dx) + (random.nextBoolean() ? 0.6D : -0.6D);
        Location dest = pivot.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);
        dest.setY(world.getHighestBlockYAt(dest) + 1.0D);

        world.spawnParticle(Particle.DUST, origin.clone().add(0, 1, 0), 10, 0.35, 0.45, 0.35, 0, DUST_VIOLET);
        world.playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.35F, 1.8F);
        npc.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void checkPhaseTransition() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) return;
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null || attr.getValue() <= 0.0D) return;
        double ratio = entity.getHealth() / attr.getValue();

        if (!wrathMirrorTriggered && ratio <= 0.60D) {
            enterWrath();
        }
        if (!unravelingTriggered && ratio <= 0.25D) {
            enterUnraveling();
        }
    }

    private void enterWrath() {
        wrathMirrorTriggered = true;
        phase = WitchPhase.WRATH;

        Location loc = getCurrentLocation();
        World world = loc.getWorld();
        if (world != null) {
            world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8F, 1.6F);
            world.playSound(loc, Sound.ENTITY_WITHER_SPAWN, 0.6F, 0.8F);
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 60, 1.0, 1.0, 1.0, 0, DUST_CRIMSON);
            world.spawnParticle(Particle.DUST, loc.clone().add(0, 1, 0), 40, 0.8, 0.8, 0.8, 0, DUST_VIOLET);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1);
        }

        reduceCooldowns(0.70D);
        LivingEntity e = getLivingEntity();
        if (e != null) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 999, 0, true, false, false));
        }
        if (state != WitchState.CASTING) {
            startCasting(WitchAbility.MIRROR_IMAGE);
        }
    }

    private void enterUnraveling() {
        unravelingTriggered = true;
        phase = WitchPhase.UNRAVELING;

        Location loc = getCurrentLocation();
        World world = loc.getWorld();
        if (world != null) {
            world.playSound(loc, Sound.ENTITY_WITHER_DEATH, 0.7F, 1.8F);
            world.playSound(loc, Sound.ENTITY_WITCH_CELEBRATE, 1.0F, 0.3F);
            world.spawnParticle(Particle.WITCH, loc.clone().add(0, 1, 0), 80, 1.0, 1.0, 1.0, 0.1);
        }

        npc.getNavigator().cancelNavigation();
        SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
        s.chaseRange = 0.0D;

        reduceCooldowns(0.5D);
        cooldowns.put(WitchAbility.HEX_CIRCLE, 0);
        if (state != WitchState.CASTING) {
            startCasting(WitchAbility.SHARED_VESSEL);
        }
    }

    private void reduceCooldowns(double factor) {
        cooldowns.replaceAll((k, v) -> Math.max(0, (int) Math.round(v * factor)));
    }

    private boolean playerHasHeavyArmor(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (ItemStack piece : armor) {
            if (piece == null || piece.getType().isAir()) continue;
            String name = piece.getType().name();
            if (name.startsWith("NETHERITE_") || name.startsWith("DIAMOND_")) {
                return true;
            }
        }
        return false;
    }

    /** Reflects an incoming arrow back at the shooter at high speed. */
    public void reflectArrow(org.bukkit.entity.Arrow incomingArrow, Player shooter) {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Location from = caster.getEyeLocation();
        World world = from.getWorld();
        if (world == null) return;
        incomingArrow.remove();
        Vector dir = shooter.getEyeLocation().toVector().subtract(from.toVector());
        if (dir.lengthSquared() > 0.001D) dir.normalize();
        org.bukkit.entity.Arrow reflected = world.spawn(from, org.bukkit.entity.Arrow.class);
        reflected.setShooter(caster);
        reflected.setVelocity(dir.multiply(3.5D));
        reflected.setDamage(6.0D);
        world.playSound(from, Sound.ENTITY_WITCH_CELEBRATE, 0.7F, 1.8F);
        world.spawnParticle(Particle.CRIT, from, 10, 0.3, 0.3, 0.3, 0.05);
        world.spawnParticle(Particle.DUST, from,  8, 0.3, 0.3, 0.3, 0, DUST_VIOLET);
    }

    /** Draws a particle line between two locations. */
    private void drawLine(Location a, Location b, Particle.DustOptions dust) {
        if (!Objects.equals(a.getWorld(), b.getWorld()) || a.getWorld() == null) return;
        double dist  = a.distance(b);
        if (dist < 0.1D) return;
        int    steps = (int)(dist / 0.5D);
        Vector dir   = b.toVector().subtract(a.toVector()).normalize().multiply(0.5D);
        Location cur = a.clone();
        for (int i = 0; i < steps; i++) {
            cur.add(dir);
            cur.getWorld().spawnParticle(Particle.DUST, cur, 1, 0, 0, 0, 0, dust);
        }
    }

    private Player ensureTarget(double radius) {
        if (target == null || !target.isOnline() || target.isDead()) { target = null; return null; }
        Location cur = getCurrentLocation();
        if (!Objects.equals(cur.getWorld(), target.getWorld()) || cur.distanceSquared(target.getLocation()) > radius * radius) {
            target = null; return null;
        }
        return target;
    }

    private Player findNearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null) return null;
        double rs = radius * radius;
        return location.getWorld().getPlayers().stream()
            .filter(p -> !p.isDead())
            .filter(p -> Objects.equals(p.getWorld(), location.getWorld()))
            .filter(p -> p.getLocation().distanceSquared(location) <= rs)
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    private LivingEntity getLivingEntity() { return npc.isSpawned() && npc.getEntity() instanceof LivingEntity le ? le : null; }
    private double randomDouble(double min, double max) { return min + random.nextDouble() * (max - min); }
    private void cancelControllerOnly() { if (controllerTask != null) { controllerTask.cancel(); controllerTask = null; } }
    private void cancelTasks() { for (BukkitRunnable t : tasks) { try { t.cancel(); } catch (Exception ignored) {} } tasks.clear(); }
}