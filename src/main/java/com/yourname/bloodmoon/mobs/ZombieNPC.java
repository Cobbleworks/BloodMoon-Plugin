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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Zombie;
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
 * Main Blood Moon zombie controller.
 */
public final class ZombieNPC {

    public enum ZombieState {
        INFECTED_RAGE,
        COMBAT,
        CASTING,
        RISEN,
        DEAD
    }

    public enum ZombieAbility {
        PLAGUE_VOMIT(22),
        DEAD_WEIGHT(18),
        HORDE_CALL(16),
        NECROTIC_SLAM(14);

        private final int weight;

        ZombieAbility(int weight) {
            this.weight = weight;
        }

        public int getWeight() {
            return weight;
        }
    }

    private static final int COMBAT_ABILITY_INTERVAL = 40;
    private static final int COMBAT_AMBIENT_INTERVAL = 32;
    private static final int RAGE_TICKS = 50;
    private static final int RESURRECTION_DELAY = 60;
    private static final int MELEE_INFECTION_COOLDOWN = 20;
    private static final int PLAGUE_VOMIT_COOLDOWN = 180;
    private static final int DEAD_WEIGHT_COOLDOWN = 300;
    private static final int HORDE_CALL_COOLDOWN = 400;
    private static final int NECROTIC_SLAM_COOLDOWN = 220;
    private static final int DEATH_REMOVE_DELAY = 60;

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random;
    private final Map<ZombieAbility, Integer> cooldowns;
    private final List<BukkitRunnable> ownedTasks;
    private ZombieState state;
    private ZombieState stateBeforeCasting;
    private ZombieAbility pendingAbility;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private int stateTicks;
    private int castingTicks;
    private boolean cleanedUp;
    private boolean deathSequenceStarted;
    private boolean combatInitialized;
    private boolean undeadResilienceUsed;
    private boolean risenActive;
    private boolean resurrecting;
    private int meleeInfectionCooldown;

    public ZombieNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.random = new Random();
        this.cooldowns = new EnumMap<>(ZombieAbility.class);
        this.ownedTasks = new ArrayList<>();
        this.state = ZombieState.INFECTED_RAGE;
        this.stateBeforeCasting = ZombieState.COMBAT;
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

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

    public void onMeleeHit(Player player) {
        if (player == null || player.isDead()) {
            return;
        }
        if (meleeInfectionCooldown <= 0) {
            plugin.getInfectionEffect().applyInfection(player);
            meleeInfectionCooldown = MELEE_INFECTION_COOLDOWN;
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.6F, 0.8F);
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        if (!(event.getTarget() instanceof Player player)) {
            return;
        }
        if (state == ZombieState.DEAD || resurrecting) {
            event.setCancelled(true);
            return;
        }
        target = player;
        onMeleeHit(player);
        if (state != ZombieState.CASTING && random.nextDouble() < 0.10D) {
            ZombieAbility ability = chooseAbility();
            if (ability != null && canUseAbility(ability)) {
                startCasting(ability);
            }
        }
    }

    public boolean tryTriggerUndeadResilience(double incomingDamage) {
        if (undeadResilienceUsed || resurrecting || deathSequenceStarted || state == ZombieState.DEAD) {
            return false;
        }
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return false;
        }
        if (entity.getHealth() - incomingDamage > 0.0D) {
            return false;
        }

        undeadResilienceUsed = true;
        resurrecting = true;
        state = ZombieState.DEAD;
        stateTicks = 0;

        Location location = entity.getLocation();
        World world = location.getWorld();
        if (world != null) {
            world.playSound(location, Sound.ENTITY_ZOMBIE_DEATH, 1.0F, 0.6F);
            world.spawnParticle(Particle.SMOKE, location.clone().add(0.0D, 0.8D, 0.0D), 22, 0.45D, 0.45D, 0.45D, 0.04D);
        }

        entity.setHealth(1.0D);
        entity.setInvulnerable(true);
        entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, RESURRECTION_DELAY + 10, 0, true, false, false));
        npc.getNavigator().cancelNavigation();

        BukkitRunnable riseTask = new BukkitRunnable() {
            @Override
            public void run() {
                LivingEntity current = getLivingEntity();
                if (current == null || current.isDead()) {
                    cleanup();
                    return;
                }
                current.setInvulnerable(false);
                current.removePotionEffect(PotionEffectType.INVISIBILITY);
                double max = getMaxHealth(current);
                double risenHealth = max * plugin.getConfigManager().getZombieRisenHpFraction();
                current.setHealth(Math.max(1.0D, Math.min(max, risenHealth)));
                state = ZombieState.RISEN;
                risenActive = true;
                resurrecting = false;
                stateTicks = 0;
                if (current.getWorld() != null) {
                    current.getWorld().playSound(current.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.2F, 0.65F);
                    current.getWorld().spawnParticle(Particle.TRIAL_OMEN, current.getLocation().add(0.0D, 1.0D, 0.0D), 24, 0.4D, 0.5D, 0.4D, 0.0D);
                }
                setNavigationSpeed(1.15F);
            }
        };
        ownedTasks.add(riseTask);
        riseTask.runTaskLater(plugin, RESURRECTION_DELAY);
        return true;
    }

    public void startDeathSequence() {
        if (deathSequenceStarted || resurrecting) {
            return;
        }
        deathSequenceStarted = true;
        state = ZombieState.DEAD;
        stateTicks = 0;

        Location deathLocation = getCurrentLocation();
        lastKnownLocation = deathLocation.clone();
        cancelControllerOnly();
        cancelOwnedTasks();

        World world = deathLocation.getWorld();
        if (world != null) {
            triggerPlagueBurst(world, deathLocation);
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

    public void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        cancelControllerOnly();
        cancelOwnedTasks();
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
            .filter(player -> !player.isDead())
            .filter(player -> player.getLocation().distanceSquared(location) <= radiusSquared)
            .min(Comparator.comparingDouble(player -> player.getLocation().distanceSquared(location)))
            .orElse(null);
    }

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
        String skinName = plugin.getConfigManager().getZombieSkinName();
        String texture = plugin.getConfigManager().getZombieSkinTexture();
        String signature = plugin.getConfigManager().getZombieSkinSignature();
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
                String cacheKey = (skinName == null || skinName.isBlank()) ? "bloodmoon_selected_zombie" : skinName;
                setSkinPersistent.invoke(skinTrait, cacheKey, signature, texture);
                return;
            }

            if (skinName != null && !skinName.isBlank()) {
                Method setSkinName = skinTraitClass.getMethod("setSkinName", String.class, boolean.class);
                setSkinName.invoke(skinTrait, skinName, true);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply Citizens SkinTrait to zombie NPC " + npc.getId() + ": " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.setInvincible(false);
        sentinel.setHealth(plugin.getConfigManager().getZombieHealth());
        sentinel.health = plugin.getConfigManager().getZombieHealth();
        sentinel.damage = 5.5D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange = 30.0D;
        sentinel.armor = 0.15D;
        sentinel.protectFromIgnores = false;
        sentinel.allTargets = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
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
        if (meleeInfectionCooldown > 0) {
            meleeInfectionCooldown--;
        }
        decrementCooldowns();
        updateLastKnownLocation();

        switch (state) {
            case INFECTED_RAGE -> tickInfectedRage();
            case COMBAT -> tickCombat();
            case CASTING -> tickCasting();
            case RISEN -> tickRisen();
            case DEAD -> {
            }
        }
    }

    private void tickInfectedRage() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        if (stateTicks == 1) {
            World world = entity.getWorld();
            world.playSound(entity.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CURE, 1.0F, 0.6F);
            world.playSound(entity.getLocation(), Sound.ENTITY_WARDEN_ROAR, 0.45F, 1.7F);
        }
        if (stateTicks % 6 == 0) {
            entity.getWorld().spawnParticle(Particle.DUST, entity.getLocation().add(0.0D, 1.1D, 0.0D), 8, 0.4D, 0.45D, 0.4D, 0.0D,
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
        setNavigationSpeed(risenActive ? 1.2F : 1.0F);
        npc.getNavigator().setTarget(player, true);
        npc.faceLocation(player.getEyeLocation());

        if (stateTicks % COMBAT_AMBIENT_INTERVAL == 0) {
            World world = player.getWorld();
            world.playSound(getCurrentLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.9F, risenActive ? 0.8F : 0.65F);
        }

        if (stateTicks % COMBAT_ABILITY_INTERVAL == 0) {
            ZombieAbility ability = chooseAbility();
            if (ability != null && canUseAbility(ability)) {
                startCasting(ability);
            }
        }
    }

    private void tickRisen() {
        tickCombat();
    }

    private void initializeCombat() {
        if (combatInitialized) {
            return;
        }
        combatInitialized = true;
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.allTargets = new SentinelTargetList();
        sentinel.addTarget("players");
        sentinel.allIgnores = new SentinelTargetList();
        sentinel.chaseRange = 32.0D;
        sentinel.respawnTime = -1;
        if (risenActive) {
            sentinel.damage *= 1.2D;
        }
    }

    private void tickCasting() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        npc.getNavigator().cancelNavigation();
        if (stateTicks == 1) {
            entity.getWorld().spawnParticle(Particle.SMOKE, entity.getLocation().add(0.0D, 1.0D, 0.0D), 15, 0.4D, 0.5D, 0.4D, 0.03D);
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 0.8F, 0.45F);
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
        castingTicks = 18;
    }

    private void executeAbility(ZombieAbility ability) {
        switch (ability) {
            case PLAGUE_VOMIT -> castPlagueVomit();
            case DEAD_WEIGHT -> castDeadWeight();
            case HORDE_CALL -> castHordeCall();
            case NECROTIC_SLAM -> castNecroticSlam();
        }
        setCooldown(ability);
    }

    private void castPlagueVomit() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(30.0D);
        if (entity == null || player == null) {
            return;
        }

        Vector dir = player.getEyeLocation().toVector().subtract(entity.getEyeLocation().toVector());
        if (dir.lengthSquared() < 0.0001D) {
            return;
        }
        dir.normalize().multiply(1.0D);

        Slime slime = (Slime) entity.getWorld().spawnEntity(entity.getEyeLocation(), EntityType.SLIME);
        slime.setSize(1);
        slime.setInvisible(true);
        slime.setCollidable(false);
        slime.setInvulnerable(true);
        slime.setGravity(true);
        slime.setAI(false);
        slime.setMetadata("bloodmoon-zombie-vomit", new FixedMetadataValue(plugin, npc.getId()));
        slime.setVelocity(dir);

        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_DROWNED_SHOOT, 0.9F, 0.72F);

        BukkitRunnable trail = new BukkitRunnable() {
            @Override
            public void run() {
                if (!slime.isValid() || slime.isDead()) {
                    cancel();
                    return;
                }
                slime.getWorld().spawnParticle(Particle.SNEEZE, slime.getLocation().add(0.0D, 0.3D, 0.0D), 4, 0.12D, 0.12D, 0.12D, 0.0D);
                slime.getWorld().spawnParticle(Particle.DUST, slime.getLocation().add(0.0D, 0.35D, 0.0D), 3, 0.08D, 0.08D, 0.08D, 0.0D,
                    new Particle.DustOptions(Color.fromRGB(30, 150, 30), 0.9F));
            }
        };
        ownedTasks.add(trail);
        trail.runTaskTimer(plugin, 1L, 1L);
    }

    public void handleVomitImpact(Projectile projectile, Location location, Player directHitPlayer) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        world.spawnParticle(Particle.SNEEZE, location.clone().add(0.0D, 0.2D, 0.0D), 26, 0.6D, 0.2D, 0.6D, 0.02D);
        world.playSound(location, Sound.ENTITY_SLIME_SQUISH, 0.9F, 0.65F);

        AreaEffectCloud cloud = (AreaEffectCloud) world.spawnEntity(location, EntityType.AREA_EFFECT_CLOUD);
        cloud.setRadius(3.0F);
        cloud.setDuration(80);
        cloud.setWaitTime(0);
        cloud.setReapplicationDelay(10);
        cloud.setParticle(Particle.SNEEZE);
        cloud.addCustomEffect(new PotionEffect(PotionEffectType.POISON, 80, 1), true);

        if (directHitPlayer != null) {
            plugin.getInfectionEffect().applyInfection(directHitPlayer);
        }
        if (projectile != null) {
            projectile.remove();
        }
    }

    private void castDeadWeight() {
        LivingEntity entity = getLivingEntity();
        Player player = ensureTarget(24.0D);
        if (entity == null || player == null) {
            return;
        }

        Location destination = player.getLocation().clone().add(randomDouble(-0.8D, 0.8D), 0.0D, randomDouble(-0.8D, 0.8D));
        destination.setY(player.getLocation().getY());
        entity.teleport(destination);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 6, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, 2, true, true, true));

        BukkitRunnable gnaw = new BukkitRunnable() {
            int remaining = 4;

            @Override
            public void run() {
                if (remaining <= 0 || !player.isOnline() || player.isDead() || isDead()) {
                    cancel();
                    return;
                }
                player.damage(1.5D);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.6F, 1.1F);
                remaining--;
            }
        };
        ownedTasks.add(gnaw);
        gnaw.runTaskTimer(plugin, 0L, 20L);
    }

    private void castHordeCall() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }

        World world = entity.getWorld();
        double radius = plugin.getConfigManager().getZombieHordeCallRadius();
        double radiusSquared = radius * radius;
        world.playSound(entity.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT, 1.2F, 0.5F);

        Player nearest = findNearestPlayer(entity.getLocation(), radius);
        for (org.bukkit.entity.Entity nearby : world.getNearbyEntities(entity.getLocation(), radius, radius, radius)) {
            if (!(nearby instanceof Zombie vanillaZombie) || nearby.hasMetadata("NPC")) {
                continue;
            }
            if (nearby.getLocation().distanceSquared(entity.getLocation()) > radiusSquared) {
                continue;
            }
            vanillaZombie.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 300, 1, true, true, true));
            vanillaZombie.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 300, 0, true, true, true));
            if (nearest != null) {
                vanillaZombie.setTarget(nearest);
            }
        }
    }

    private void castNecroticSlam() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }

        entity.setVelocity(entity.getVelocity().setY(0.9D));

        BukkitRunnable slam = new BukkitRunnable() {
            @Override
            public void run() {
                if (isDead()) {
                    cancel();
                    return;
                }
                Location center = getCurrentLocation();
                World world = center.getWorld();
                if (world == null) {
                    cancel();
                    return;
                }

                world.playSound(center, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 1.0F, 0.7F);
                world.spawnParticle(Particle.BLOCK, center, 30, 1.1D, 0.2D, 1.1D, Material.SOUL_SOIL.createBlockData());
                world.spawnParticle(Particle.DUST, center.clone().add(0.0D, 0.1D, 0.0D), 18, 1.3D, 0.15D, 1.3D, 0.0D,
                    new Particle.DustOptions(Color.fromRGB(30, 30, 30), 1.1F));

                for (Player nearby : world.getPlayers()) {
                    if (nearby.isDead()) {
                        continue;
                    }
                    if (nearby.getLocation().distanceSquared(center) <= 16.0D) {
                        nearby.damage(4.0D);
                    }
                }

                spawnNecroticPatch(center);
            }
        };
        ownedTasks.add(slam);
        slam.runTaskLater(plugin, 16L);
    }

    private void spawnNecroticPatch(Location center) {
        BukkitRunnable patch = new BukkitRunnable() {
            int remaining = 16;

            @Override
            public void run() {
                if (remaining <= 0 || isDead()) {
                    cancel();
                    return;
                }
                World world = center.getWorld();
                if (world == null) {
                    cancel();
                    return;
                }

                world.spawnParticle(Particle.DUST, center.clone().add(0.0D, 0.05D, 0.0D), 10, 2.4D, 0.05D, 2.4D, 0.0D,
                    new Particle.DustOptions(Color.fromRGB(40, 40, 40), 0.9F));

                for (Player nearby : world.getPlayers()) {
                    if (nearby.isDead()) {
                        continue;
                    }
                    if (nearby.getLocation().distanceSquared(center) <= 6.25D) {
                        nearby.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 1, true, true, true));
                    }
                }
                remaining--;
            }
        };
        ownedTasks.add(patch);
        patch.runTaskTimer(plugin, 0L, 10L);
    }

    private void triggerPlagueBurst(World world, Location location) {
        double radius = plugin.getConfigManager().getZombiePlagueBurstRadius();
        double radiusSquared = radius * radius;
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.55F);
        world.spawnParticle(Particle.SNEEZE, location.clone().add(0.0D, 0.8D, 0.0D), 36, 1.1D, 0.6D, 1.1D, 0.03D);
        world.spawnParticle(Particle.CRIT, location.clone().add(0.0D, 0.8D, 0.0D), 28, 1.0D, 0.5D, 1.0D, 0.15D);

        for (Player player : world.getPlayers()) {
            if (player.isDead()) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                plugin.getInfectionEffect().applyInfection(player);
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 0, true, true, true));
            }
        }
    }

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.75D) {
            world.dropItemNaturally(location, new ItemStack(Material.ROTTEN_FLESH, randomRange(2, 5)));
        }
        if (random.nextDouble() <= 0.60D) {
            world.dropItemNaturally(location, new ItemStack(Material.BONE, randomRange(1, 4)));
        }
        if (random.nextDouble() <= 0.25D) {
            world.dropItemNaturally(location, new ItemStack(Material.IRON_INGOT, randomRange(1, 2)));
        }
        if (random.nextDouble() <= 0.15D) {
            world.dropItemNaturally(location, new ItemStack(Material.CARVED_PUMPKIN, 1));
        }
        if (random.nextDouble() <= 0.40D) {
            world.dropItemNaturally(location, new ItemStack(Material.SLIME_BALL, randomRange(2, 4)));
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
        if (random.nextDouble() <= 0.03D) {
            world.dropItemNaturally(location, new ItemStack(Material.ZOMBIE_HEAD, 1));
        }

        ExperienceOrb orb = world.spawn(location.clone().add(0.0D, 0.25D, 0.0D), ExperienceOrb.class);
        orb.setExperience(randomRange(35, 55));
    }

    private Player ensureTarget(double radius) {
        if (target == null || !target.isOnline() || target.isDead()) {
            target = null;
            return null;
        }
        Location current = getCurrentLocation();
        if (current.getWorld() != target.getWorld() || current.distanceSquared(target.getLocation()) > radius * radius) {
            target = null;
            return null;
        }
        return target;
    }

    private ZombieAbility chooseAbility() {
        int total = 0;
        for (ZombieAbility ability : ZombieAbility.values()) {
            if (canUseAbility(ability)) {
                total += ability.getWeight();
            }
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        int cumulative = 0;
        for (ZombieAbility ability : ZombieAbility.values()) {
            if (!canUseAbility(ability)) {
                continue;
            }
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
            case PLAGUE_VOMIT -> cooldowns.put(ability, PLAGUE_VOMIT_COOLDOWN);
            case DEAD_WEIGHT -> cooldowns.put(ability, DEAD_WEIGHT_COOLDOWN);
            case HORDE_CALL -> cooldowns.put(ability, HORDE_CALL_COOLDOWN);
            case NECROTIC_SLAM -> cooldowns.put(ability, NECROTIC_SLAM_COOLDOWN);
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

    private double getMaxHealth(LivingEntity entity) {
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? plugin.getConfigManager().getZombieHealth() : attr.getBaseValue();
    }

    private int randomRange(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    private double randomDouble(double min, double max) {
        return min + (random.nextDouble() * (max - min));
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
