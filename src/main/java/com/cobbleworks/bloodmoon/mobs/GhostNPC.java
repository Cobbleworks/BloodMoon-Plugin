package com.cobbleworks.bloodmoon.mobs;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.traits.GhostTrait;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
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
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class GhostNPC {

    private static final Particle.DustOptions DUST_WHITE = new Particle.DustOptions(Color.fromRGB(230, 230, 255), 1.2F);
    private static final Particle.DustOptions DUST_ICE = new Particle.DustOptions(Color.fromRGB(100, 180, 255), 1.0F);
    private static final Particle.DustOptions DUST_CYAN = new Particle.DustOptions(Color.fromRGB(0, 200, 200), 1.1F);
    private static final Particle.DustOptions DUST_REDSTONE = new Particle.DustOptions(Color.fromRGB(220, 30, 30), 0.9F);

    private enum GhostState { STALKING, RUSHING, DEAD }
    private enum GhostAbility { MIND_CONTROL, PARANORMAL_ACTIVITY, ECHO, POLTERGEIST_THROW }

    private static final Map<UUID, GhostNPC> POSSESSIONS = new HashMap<>();
    private static final int ITEM_SNATCH_COOLDOWN_TICKS = 180;
    private static final int ITEM_SNATCH_THROW_DELAY_TICKS = 42;
    private static final int COUNTERPLAY_WINDOW_TICKS = 60;

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<GhostAbility, Integer> cooldowns = new EnumMap<>(GhostAbility.class);
    private final Map<GhostAbility, Integer> abilityUseCounts = new EnumMap<>(GhostAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private final List<ItemStack> stolenItems = new ArrayList<>();
    private final List<NPC> echoIllusions = new ArrayList<>();
    private final Map<String, BlockRevert> paranormalReverts = new HashMap<>();
    private final Deque<Location> trackedPath = new ArrayDeque<>();

    private GhostState state = GhostState.STALKING;
    private BukkitRunnable controllerTask;
    private Player target;
    private Player possessedPlayer;
    private NPC controlledHost;
    private Location lastKnownLocation;
    private double lastPlayerDistSquared = Double.MAX_VALUE;
    private int stateTicks;
    private int vanishingTicks;
    private int phaseWalkTicks;
    private int itemSnatchCooldown;
    private int recentDamageTicks;
    private int stalkPauseTicks;
    private int vulnerableTicks;
    private boolean cleaned;
    private boolean deathStarted;
    private boolean untargetable;
    private boolean forcedVisibleByLight;

    private record BlockRevert(Location loc, Material original) {}

    public GhostNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

    public NPC getNpc() {
        return npc;
    }

    public boolean isDead() {
        return state == GhostState.DEAD || cleaned || deathStarted;
    }

    public Location getCurrentLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : (lastKnownLocation != null ? lastKnownLocation.clone() : spawnLocation.clone());
    }

    public double getCurrentHealth() {
        LivingEntity entity = getLivingEntity();
        return entity == null ? plugin.getConfigManager().getGhostHealth() : Math.max(0.0D, entity.getHealth());
    }

    public double getMaximumHealth() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return plugin.getConfigManager().getGhostHealth();
        }
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? plugin.getConfigManager().getGhostHealth() : Math.max(1.0D, attr.getValue());
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
        if (!(event.getTarget() instanceof Player player) || state == GhostState.DEAD) {
            return;
        }
        target = player;
    }

    public boolean isUntargetable() {
        return untargetable;
    }

    public double reduceIncomingDamage(double damage) {
        return phaseWalkTicks > 0 ? damage * 0.7D : damage;
    }

    public void onTakeDamage(double damage) {
        breakVanishing();
        recentDamageTicks = 60;
        vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS);
        if (damage >= 1.0D) {
            phaseWalkTicks = 0;
        }
    }

    public static GhostNPC getPossessingGhost(Player player) {
        return player == null ? null : POSSESSIONS.get(player.getUniqueId());
    }

    public void handlePossessedMove(Player player, org.bukkit.event.player.PlayerMoveEvent event) {
        if (possessedPlayer == null || !possessedPlayer.getUniqueId().equals(player.getUniqueId()) || event.getTo() == null) {
            return;
        }
        Vector delta = event.getTo().toVector().subtract(event.getFrom().toVector());
        Vector horizontal = new Vector(delta.getX(), 0.0D, delta.getZ());

        if (horizontal.lengthSquared() < 0.0004D) {
            Location look = event.getTo().clone();
            look.setYaw(event.getTo().getYaw() + (float) ((Math.sin(stateTicks * 0.45D) * 22.0D)));
            event.setTo(look);
            return;
        }

        double cos = Math.cos(Math.PI * 0.65D);
        double sin = Math.sin(Math.PI * 0.65D);
        Vector rotated = new Vector(
            horizontal.getX() * cos - horizontal.getZ() * sin,
            0.0D,
            horizontal.getX() * sin + horizontal.getZ() * cos
        );

        double speed = Math.min(0.38D, horizontal.length());
        Location drifted = event.getFrom().clone().add(rotated.normalize().multiply(speed));
        drifted.setY(event.getTo().getY());
        drifted.setYaw(event.getTo().getYaw() + (float) ((Math.cos(stateTicks * 0.4D) * 16.0D)));
        drifted.setPitch(event.getTo().getPitch());

        if (!drifted.getBlock().isPassable() || !drifted.clone().add(0, 1, 0).getBlock().isPassable()) {
            event.setTo(event.getTo().clone());
            return;
        }
        event.setTo(drifted);
    }

    public void handlePossessedVictimDamaged(double finalDamage) {
        if (possessedPlayer == null) {
            return;
        }
        if (finalDamage >= 4.0D) {
            ejectPossession(false);
        }
    }

    public void startDeathSequence() {
        if (deathStarted) {
            return;
        }
        deathStarted = true;
        state = GhostState.DEAD;
        cancelControllerOnly();
        cancelTasks();
        revertParanormalBlocks();
        Location death = getCurrentLocation();
        World world = death.getWorld();
        if (world != null) {
            world.playSound(death, Sound.ENTITY_WITHER_DEATH, 0.7F, 1.8F);
            world.spawnParticle(Particle.DUST, death.clone().add(0, 1, 0), 40, 0.6, 0.7, 0.6, 0, DUST_WHITE);
            world.spawnParticle(Particle.SNOWFLAKE, death.clone().add(0, 1, 0), 25, 0.4, 0.5, 0.4, 0.05);
            for (ItemStack item : stolenItems) {
                if (item != null && item.getType() != Material.AIR) {
                    world.dropItemNaturally(death, item);
                }
            }
            stolenItems.clear();
            dropLoot(world, death);
            ExperienceOrb orb = world.spawn(death.clone().add(0, 0.25, 0), ExperienceOrb.class);
            orb.setExperience(25 + random.nextInt(15));
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
        revertParanormalBlocks();
        endHostControl();
        ejectPossession(true);
        destroyEchoes();
        if (npc.isSpawned()) {
            npc.despawn();
        }
        npc.destroy();
        plugin.getNPCManager().unregisterGhost(npc.getId());
    }

    private void configureNpc() {
        npc.data().set("bloodmoon-ghost", true);
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        GhostTrait trait = npc.getOrAddTrait(GhostTrait.class);
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
        String skinName = plugin.getConfigManager().getGhostSkinName();
        String texture = plugin.getConfigManager().getGhostSkinTexture();
        String signature = plugin.getConfigManager().getGhostSkinSignature();
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
            plugin.getLogger().warning("Could not apply ghost skin: " + ex.getMessage());
        }
    }

    private void dropLoot(World world, Location location) {
        if (random.nextDouble() <= 0.62D) world.dropItemNaturally(location, new ItemStack(Material.PAPER, 1 + random.nextInt(3)));
        if (random.nextDouble() <= 0.44D) world.dropItemNaturally(location, new ItemStack(Material.STRING, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.32D) world.dropItemNaturally(location, new ItemStack(Material.WHITE_WOOL, 1));
        if (random.nextDouble() <= 0.38D) world.dropItemNaturally(location, new ItemStack(Material.GLASS_BOTTLE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.40D) world.dropItemNaturally(location, new ItemStack(Material.BONE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.40D) world.dropItemNaturally(location, new ItemStack(Material.SNOWBALL, 2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.20D) world.dropItemNaturally(location, new ItemStack(Material.PHANTOM_MEMBRANE, 1));
        if (random.nextDouble() <= 0.24D) world.dropItemNaturally(location, new ItemStack(Material.SOUL_SAND, 1));
        if (random.nextDouble() <= 0.18D) world.dropItemNaturally(location, new ItemStack(Material.BLUE_ICE, 1));
        if (random.nextDouble() <= 0.28D) world.dropItemNaturally(location, new ItemStack(Material.QUARTZ, 1 + random.nextInt(2)));

        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.GHAST_TEAR, 1));
        if (random.nextDouble() <= 0.09D) world.dropItemNaturally(location, new ItemStack(Material.ENDER_PEARL, 1));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.CLOCK, 1));
        if (random.nextDouble() <= 0.08D) world.dropItemNaturally(location, new ItemStack(Material.COMPASS, 1));
        if (random.nextDouble() <= 0.10D) world.dropItemNaturally(location, new ItemStack(Material.EXPERIENCE_BOTTLE, 1 + random.nextInt(2)));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.SPECTRAL_ARROW, 2 + random.nextInt(3)));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.MUSIC_DISC_13, 1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.NAUTILUS_SHELL, 1));
        if (random.nextDouble() <= 0.05D) world.dropItemNaturally(location, new ItemStack(Material.CRYING_OBSIDIAN, 1));
        if (random.nextDouble() <= 0.07D) world.dropItemNaturally(location, new ItemStack(Material.ENCHANTED_BOOK, 1));
    }

    private void configureSentinel() {
        SentinelTrait sentinel = npc.getOrAddTrait(SentinelTrait.class);
        sentinel.setInvincible(false);
        sentinel.setHealth(plugin.getConfigManager().getGhostHealth());
        sentinel.health = plugin.getConfigManager().getGhostHealth();
        sentinel.damage = 1.25D;
        sentinel.respawnTime = -1;
        sentinel.chaseRange = 0.0D;
        sentinel.armor = 0.05D;
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
        double health = plugin.getConfigManager().getGhostHealth();
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
        if (itemSnatchCooldown > 0) {
            itemSnatchCooldown--;
        }
        if (recentDamageTicks > 0) {
            recentDamageTicks--;
        }
        if (stalkPauseTicks > 0) {
            stalkPauseTicks--;
        }
        if (vulnerableTicks > 0) {
            vulnerableTicks--;
            breakVanishing();
            setUntargetable(false);
        }
        onTraitTick();
        updateLightRevealState();
        emitGhostTellParticles();

        tickVanishing();

        if (state == GhostState.DEAD || state == GhostState.RUSHING) {
            return;
        }

        Player player = ensureTarget(64.0D);
        if (player == null) {
            player = findNearestPlayer(getCurrentLocation(), 64.0D);
            target = player;
        }
        if (player == null) {
            return;
        }

        trackPlayerPath(player);
        tickColdPresence();

        if (phaseWalkTicks > 0) {
            tickPhaseWalk(player);
            lastPlayerDistSquared = getCurrentLocation().distanceSquared(player.getLocation());
            return;
        }

        npc.getNavigator().cancelNavigation();
        npc.faceLocation(player.getEyeLocation());

        if (stateTicks % 100 == 0) {
            Location loc = getCurrentLocation();
            if (loc.getWorld() != null) {
                loc.getWorld().playSound(loc, Sound.AMBIENT_CAVE, 0.3F, 1.6F + random.nextFloat() * 0.2F);
            }
        }

        double distanceSquared = getCurrentLocation().distanceSquared(player.getLocation());
        tickStalking(player, distanceSquared);

        int abilityInterval = Math.max(16, (int) Math.round(34.0D * plugin.getBloodMoonManager().getAbilityCadenceMultiplier()));
        if (state == GhostState.STALKING && vulnerableTicks <= 0 && vanishingTicks <= 0 && stateTicks % abilityInterval == 0 && distanceSquared < 2000.0D) {
            GhostAbility ability = chooseAbility();
            if (ability != null) {
                executeAbility(ability, player);
            }
        }

        lastPlayerDistSquared = distanceSquared;
    }

    private void tickStalking(Player player, double distanceSquared) {
        double currentDistance = Math.sqrt(distanceSquared);

        if (stalkPauseTicks > 0) {
            npc.getNavigator().cancelNavigation();
            npc.faceLocation(player.getEyeLocation());
            if (stateTicks % 8 == 0) {
                Location stare = getCurrentLocation().clone().add(0, 1, 0);
                World w = stare.getWorld();
                if (w != null) {
                    w.spawnParticle(Particle.DUST, stare, 1, 0.12, 0.18, 0.12, 0.0, DUST_WHITE);
                }
            }
            return;
        }

        LivingEntity entity = getLivingEntity();
        if (entity != null && currentDistance <= 18.0D && !entity.hasLineOfSight(player) && phaseWalkTicks <= 0 && random.nextDouble() <= 0.18D) {
            startPhaseWalk();
            return;
        }

        if (currentDistance <= 8.0D && itemSnatchCooldown <= 0 && recentDamageTicks <= 0) {
            startRush(player);
            return;
        }

        if (stateTicks % 30 == 0 && currentDistance > 8.0D) {
            teleportCloser(player, currentDistance);
            stalkPauseTicks = 16 + random.nextInt(8);
            if (vulnerableTicks <= 0 && random.nextDouble() <= 0.20D) {
                startVanishing(10 + random.nextInt(8));
            }
            return;
        }

        if (currentDistance <= 6.0D) {
            if (random.nextDouble() <= 0.32D) {
                startRush(player);
            } else {
                teleportAway(player);
                vulnerableTicks = Math.max(vulnerableTicks, 40);
            }
            return;
        }
        if (stateTicks % 25 == 0
            && lastPlayerDistSquared < Double.MAX_VALUE
            && distanceSquared > lastPlayerDistSquared + 9.0D
            && currentDistance > 9.0D) {
            teleportCloser(player, currentDistance);
            return;
        }
        if (stateTicks % 100 == 0 && currentDistance > 14.0D) {
            teleportCloser(player, currentDistance);
        }
    }

    private void teleportCloser(Player player, double currentDistance) {
        World world = player.getWorld();
        double newDistance = Math.max(8.0D, currentDistance - (4.0D + random.nextDouble() * 4.0D));
        Vector toGhost = getCurrentLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
        if (toGhost.lengthSquared() < 0.001D) {
            toGhost = new Vector(1, 0, 0);
        }
        toGhost.normalize();

        double angleOffset = (random.nextDouble() - 0.5D) * Math.PI * 0.85D;
        double cos = Math.cos(angleOffset);
        double sin = Math.sin(angleOffset);
        Vector rotated = new Vector(
            toGhost.getX() * cos - toGhost.getZ() * sin,
            0,
            toGhost.getX() * sin + toGhost.getZ() * cos).normalize().multiply(newDistance);

        Location newLoc = player.getLocation().clone().add(rotated);
        int surfaceY = world.getHighestBlockYAt(newLoc.getBlockX(), newLoc.getBlockZ());
        newLoc.setY(surfaceY + 1.0D);
        if (Math.abs(newLoc.getY() - player.getLocation().getY()) > 12.0D) {
            return;
        }

        Location before = getCurrentLocation().clone();
        npc.teleport(newLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        world.spawnParticle(Particle.DUST, before.clone().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0, DUST_WHITE);
        world.spawnParticle(Particle.DUST, newLoc.clone().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0, DUST_ICE);
        world.playSound(newLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.25F, 1.8F);
        if (vulnerableTicks <= 0 && random.nextDouble() <= 0.18D) {
            startVanishing(18 + random.nextInt(10));
        }
    }

    private void teleportAway(Player player) {
        World world = player.getWorld();
        double distance = 15.0D + random.nextDouble() * 6.0D;
        double angle = random.nextDouble() * Math.PI * 2.0D;
        Vector dir = new Vector(Math.cos(angle), 0, Math.sin(angle)).multiply(distance);
        Location newLoc = player.getLocation().clone().add(dir);
        int surfaceY = world.getHighestBlockYAt(newLoc.getBlockX(), newLoc.getBlockZ());
        newLoc.setY(surfaceY + 1.0D);
        if (Math.abs(newLoc.getY() - player.getLocation().getY()) > 14.0D) {
            return;
        }

        npc.teleport(newLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        world.spawnParticle(Particle.DUST, newLoc.clone().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0, DUST_ICE);
        world.playSound(newLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.2F, 1.9F);
    }

    private void startRush(Player player) {
        if (state == GhostState.RUSHING) {
            return;
        }
        breakVanishing();
        state = GhostState.RUSHING;

        LivingEntity caster = getLivingEntity();
        if (caster == null) {
            state = GhostState.STALKING;
            return;
        }

        World world = caster.getWorld();
        Location from = caster.getLocation().clone();
        if (player.getWorld() != world) {
            state = GhostState.STALKING;
            return;
        }
        Vector dir = player.getLocation().toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 0.0001D) {
            state = GhostState.STALKING;
            return;
        }
        dir.normalize();

        world.playSound(from, Sound.ENTITY_WITHER_AMBIENT, 0.9F, 1.9F);
        world.spawnParticle(Particle.DUST, from.clone().add(0, 1, 0), 16, 0.2, 0.3, 0.2, 0, DUST_WHITE);
        caster.setVelocity(dir.multiply(1.6D).setY(0.15D));

        BukkitRunnable rush = new BukkitRunnable() {
            private boolean stolen;
            private int ticks;

            @Override
            public void run() {
                ticks++;
                if (ticks > 45 || isDead()) {
                    if (player.isOnline() && !isDead()) {
                        teleportAway(player);
                    }
                    vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS);
                    state = GhostState.STALKING;
                    lastPlayerDistSquared = Double.MAX_VALUE;
                    cancel();
                    return;
                }

                Location current = getCurrentLocation();
                World currentWorld = current.getWorld();
                if (currentWorld != null) {
                    currentWorld.spawnParticle(Particle.DUST, current.clone().add(0, 1, 0), 4, 0.3, 0.4, 0.3, 0, DUST_ICE);
                    currentWorld.spawnParticle(Particle.SNOWFLAKE, current.clone().add(0, 1, 0), 2, 0.2, 0.2, 0.2, 0.05);
                }

                if (!stolen && player.isOnline() && !player.isDead() && player.getWorld() == current.getWorld() && current.distanceSquared(player.getLocation()) < 9.0D) {
                    stolen = trySnatchAndThrow(player, caster);
                }
            }
        };
        tasks.add(rush);
        rush.runTaskTimer(plugin, 0L, 1L);
    }

    private GhostAbility chooseAbility() {
        if (target != null && target.isOnline() && !target.isDead()
            && cooldowns.getOrDefault(GhostAbility.MIND_CONTROL, 0) <= 0
            && hasPossessionOpportunity(target)) {
            return GhostAbility.MIND_CONTROL;
        }

        List<GhostAbility> available = new ArrayList<>();
        for (GhostAbility ability : GhostAbility.values()) {
            if (cooldowns.getOrDefault(ability, 0) <= 0) {
                available.add(ability);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        int minUses = available.stream().mapToInt(a -> abilityUseCounts.getOrDefault(a, 0)).min().orElse(0);
        List<GhostAbility> underused = available.stream().filter(a -> abilityUseCounts.getOrDefault(a, 0) == minUses).toList();
        if (!underused.isEmpty() && random.nextDouble() <= 0.58D) {
            return underused.get(random.nextInt(underused.size()));
        }

        List<GhostAbility> pool = new ArrayList<>();
        for (GhostAbility ability : available) {
                int weight = switch (ability) {
                    case MIND_CONTROL -> 1;
                    case PARANORMAL_ACTIVITY -> 2;
                    case ECHO -> 2;
                    case POLTERGEIST_THROW -> 3;
                };
                for (int index = 0; index < weight; index++) {
                    pool.add(ability);
                }
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private void executeAbility(GhostAbility ability, Player player) {
        abilityUseCounts.merge(ability, 1, Integer::sum);
        breakVanishing();
        switch (ability) {
            case MIND_CONTROL -> castMindControl(player);
            case PARANORMAL_ACTIVITY -> castParanormalActivity(player);
            case ECHO -> castEcho(player);
            case POLTERGEIST_THROW -> castPoltergeistThrow(player);
        }
        cooldowns.put(ability, switch (ability) {
            case MIND_CONTROL -> 600;
            case PARANORMAL_ACTIVITY -> 320;
            case ECHO -> 220;
            case POLTERGEIST_THROW -> 180;
        });
    }

    private void castMindControl(Player player) {
        if (!CitizensAPI.hasImplementation()) {
            return;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            return;
        }

        NPC victim = null;
        double bestDistance = Double.MAX_VALUE;
        for (NPC candidate : registry) {
            if (!candidate.isSpawned() || candidate.getEntity() == null) {
                continue;
            }
            if (candidate.getId() == npc.getId()) {
                continue;
            }
            if (plugin.getNPCManager().isBloodMoonNpc(candidate.getEntity())) {
                continue;
            }
            if (candidate.getEntity().getWorld() != getCurrentLocation().getWorld()) {
                continue;
            }
            if (target == null || !target.isOnline() || target.isDead()) {
                continue;
            }
            double distGhost = candidate.getEntity().getLocation().distanceSquared(getCurrentLocation());
            double distPlayer = candidate.getEntity().getLocation().distanceSquared(target.getLocation());
            if (distGhost < 324.0D && distPlayer <= 36.0D && distGhost < bestDistance) {
                double distance = distGhost;
                bestDistance = distance;
                victim = candidate;
            }
        }
        if (victim == null) {
            return;
        }

        NPC controlled = victim;
    controlledHost = controlled;
    setUntargetable(true);
        World world = player.getWorld();
        Location victimLoc = controlled.getEntity().getLocation().clone();
        world.playSound(victimLoc, Sound.ENTITY_WITHER_AMBIENT, 0.6F, 1.5F);
        world.spawnParticle(Particle.DUST, victimLoc.clone().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0, DUST_CYAN);
    npc.teleport(victimLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

        boolean hasSentinel = controlled.hasTrait(SentinelTrait.class);
        if (hasSentinel) {
            try {
                SentinelTrait sentinel = controlled.getOrAddTrait(SentinelTrait.class);
                sentinel.addTarget("player:" + player.getName());
            } catch (Exception ignored) {
            }
        } else {
            controlled.getNavigator().setTarget(player, true);
        }

        BukkitRunnable controlTask = new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                ticks++;
                if (ticks > 140 || isDead() || !controlled.isSpawned()) {
                    endHostControl();
                    cancel();
                    return;
                }
                if (!controlled.isSpawned() || controlled.getEntity() == null) {
                    handleControlledHostDeath(player);
                    cancel();
                    return;
                }
                Location controlledLoc = controlled.getEntity().getLocation().add(0, 1, 0);
                npc.teleport(controlledLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                double angle = (ticks * 14.0D) * Math.PI / 180.0D;
                double rx = Math.cos(angle) * 0.7D;
                double rz = Math.sin(angle) * 0.7D;
                world.spawnParticle(Particle.DUST, controlledLoc.clone().add(rx, 0.4, rz), 2, 0.05, 0.05, 0.05, 0, DUST_CYAN);
                world.spawnParticle(Particle.DUST, controlledLoc.clone().add(-rx, 0.1, -rz), 2, 0.05, 0.05, 0.05, 0, DUST_CYAN);
            }
        };
        tasks.add(controlTask);
        controlTask.runTaskTimer(plugin, 0L, 1L);
    }

    private void castEcho(Player player) {
        if (trackedPath.size() < 12 || !CitizensAPI.hasImplementation()) {
            return;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            return;
        }

        List<Location> path = new ArrayList<>(trackedPath);
        NPC echo = registry.createNPC(org.bukkit.entity.EntityType.PLAYER, "");
        echo.data().set("nameplate-visible", false);
        echo.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        echo.setProtected(false);
        echo.spawn(path.get(0).clone());
        echoIllusions.add(echo);

        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = echo.getOrAddTrait(skinTraitClass);
            skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setFetchDefaultSkin", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setSkinName", String.class, boolean.class).invoke(skinTrait, player.getName(), true);
        } catch (ReflectiveOperationException ignored) {
        }

        if (echo.getEntity() instanceof LivingEntity living) {
            living.setCollidable(false);
            living.setSilent(true);
            living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 4, false, false, false));
        }

        BukkitRunnable echoTask = new BukkitRunnable() {
            int index = 0;
            @Override
            public void run() {
                if (index >= path.size() || !echo.isSpawned() || echo.getEntity() == null || isDead()) {
                    destroyEcho(echo);
                    cancel();
                    return;
                }
                Location step = path.get(index++).clone();
                echo.teleport(step, PlayerTeleportEvent.TeleportCause.PLUGIN);
                World world = step.getWorld();
                if (world != null) {
                    world.spawnParticle(Particle.DUST, step.clone().add(0, 1, 0), 8, 0.2, 0.4, 0.2, 0, DUST_WHITE);
                    for (Monster monster : world.getNearbyEntities(step, 10.0D, 4.0D, 10.0D).stream()
                            .filter(Monster.class::isInstance).map(Monster.class::cast).toList()) {
                        monster.setTarget((LivingEntity) echo.getEntity());
                    }
                }
            }
        };
        tasks.add(echoTask);
        echoTask.runTaskTimer(plugin, 0L, 2L);
    }

    private void castPossession(Player player) {
        // Player possession is intentionally disabled for this Ghost design.
    }

    private void castPoltergeistThrow(Player player) {
        World world = player.getWorld();
        int count = 2 + random.nextInt(3);
        List<org.bukkit.entity.Item> projectiles = new ArrayList<>();
        for (org.bukkit.entity.Item nearby : world.getNearbyEntities(getCurrentLocation(), 8.0D, 4.0D, 8.0D).stream()
                .filter(org.bukkit.entity.Item.class::isInstance).map(org.bukkit.entity.Item.class::cast).limit(count).toList()) {
            projectiles.add(nearby);
        }
        while (projectiles.size() < count) {
            Material material = switch (random.nextInt(4)) {
                case 0 -> Material.COBBLESTONE;
                case 1 -> Material.BONE;
                case 2 -> Material.ROTTEN_FLESH;
                default -> Material.GRAY_WOOL;
            };
            org.bukkit.entity.Item item = world.dropItem(getCurrentLocation().add(0, 1.2, 0), new ItemStack(material));
            item.setPickupDelay(Integer.MAX_VALUE);
            projectiles.add(item);
        }

        world.playSound(getCurrentLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.8F, 0.8F);
        for (org.bukkit.entity.Item projectile : projectiles) {
            Vector velocity = player.getEyeLocation().toVector().subtract(projectile.getLocation().toVector()).normalize()
                .multiply(0.7D + random.nextDouble() * 0.18D);
            projectile.setVelocity(velocity);
            projectile.setMetadata("bloodmoon-ghost-throw", new FixedMetadataValue(plugin, true));
            BukkitRunnable throwTask = new BukkitRunnable() {
                int ticks = 0;
                @Override
                public void run() {
                    ticks++;
                    if (!projectile.isValid() || ticks > 40) {
                        projectile.remove();
                        cancel();
                        return;
                    }
                    if (player.isOnline() && !player.isDead() && projectile.getLocation().distanceSquared(player.getLocation()) <= 2.25D) {
                        player.damage(4.0D, getLivingEntity());
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, 1, false, true, true));
                        world.spawnParticle(Particle.ITEM, player.getLocation().add(0, 1, 0), 8, 0.3, 0.3, 0.3, new ItemStack(projectile.getItemStack().getType()));
                        projectile.remove();
                        cancel();
                    }
                }
            };
            tasks.add(throwTask);
            throwTask.runTaskTimer(plugin, 0L, 1L);
        }
    }

    private void castParanormalActivity(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        world.playSound(center, Sound.AMBIENT_CAVE, 0.9F, 0.6F + random.nextFloat() * 0.3F);
        world.playSound(center, Sound.BLOCK_LEVER_CLICK, 0.6F, 0.8F);
        world.spawnParticle(Particle.DUST, center.clone().add(0, 1.5, 0), 22, 1.5, 0.3, 1.5, 0, DUST_WHITE);

        int radius = 12;
        List<Block> levers = new ArrayList<>();
        List<Block> lamps = new ArrayList<>();
        List<Block> swapCandidates = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radius * radius) {
                        continue;
                    }
                    Block block = world.getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    switch (block.getType()) {
                        case LEVER -> levers.add(block);
                        case REDSTONE_LAMP -> lamps.add(block);
                        case STONE, COBBLESTONE, GRAVEL, DIRT -> swapCandidates.add(block);
                        default -> {
                        }
                    }
                }
            }
        }

        for (Block lever : levers) {
            if (lever.getBlockData() instanceof Powerable powerable) {
                powerable.setPowered(!powerable.isPowered());
                lever.setBlockData(powerable, true);
                world.playSound(lever.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.7F, 1.0F);
            }
        }
        if (!levers.isEmpty()) {
            BukkitRunnable revertLevers = new BukkitRunnable() {
                @Override
                public void run() {
                    for (Block lever : levers) {
                        if (lever.getType() == Material.LEVER && lever.getBlockData() instanceof Powerable powerable) {
                            powerable.setPowered(!powerable.isPowered());
                            lever.setBlockData(powerable, true);
                        }
                    }
                }
            };
            tasks.add(revertLevers);
            revertLevers.runTaskLater(plugin, 60L + random.nextInt(40));
        }

        for (Block lamp : lamps) {
            if (lamp.getBlockData() instanceof Lightable lightable) {
                boolean originalLit = lightable.isLit();
                lightable.setLit(!originalLit);
                lamp.setBlockData(lightable, true);
                world.spawnParticle(Particle.END_ROD, lamp.getLocation().clone().add(0.5, 0.5, 0.5), 3, 0.2, 0.2, 0.2, 0.01);
                BukkitRunnable revertLamp = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (lamp.getType() == Material.REDSTONE_LAMP && lamp.getBlockData() instanceof Lightable revertLightable) {
                            revertLightable.setLit(originalLit);
                            lamp.setBlockData(revertLightable, true);
                        }
                    }
                };
                tasks.add(revertLamp);
                revertLamp.runTaskLater(plugin, 30L + random.nextInt(50));
            }
        }

        Collections.shuffle(swapCandidates, random);
        Material[] swapTargets = { Material.SOUL_SAND, Material.CRYING_OBSIDIAN, Material.TUFF, Material.MOSS_BLOCK };
        int swapCount = Math.min(6, swapCandidates.size());
        for (int index = 0; index < swapCount; index++) {
            Block block = swapCandidates.get(index);
            Material original = block.getType();
            Material replacement = swapTargets[random.nextInt(swapTargets.length)];
            String key = block.getX() + "," + block.getY() + "," + block.getZ();
            block.setType(replacement, false);
            paranormalReverts.put(key, new BlockRevert(block.getLocation().clone(), original));
            world.spawnParticle(Particle.DUST, block.getLocation().clone().add(0.5, 0.5, 0.5), 4, 0.3, 0.3, 0.3, 0, DUST_WHITE);
            BukkitRunnable revert = new BukkitRunnable() {
                @Override
                public void run() {
                    Block revertBlock = block.getLocation().getBlock();
                    if (revertBlock.getType() == replacement) {
                        revertBlock.setType(original, false);
                    }
                    paranormalReverts.remove(key);
                }
            };
            tasks.add(revert);
            revert.runTaskLater(plugin, 60L + random.nextInt(60));
        }
    }

    private void revertParanormalBlocks() {
        for (BlockRevert revert : paranormalReverts.values()) {
            revert.loc().getBlock().setType(revert.original(), false);
        }
        paranormalReverts.clear();
    }

    private void tickColdPresence() {
        Location current = getCurrentLocation();
        World world = current.getWorld();
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            double distanceSquared = player.getLocation().distanceSquared(current);
            if (distanceSquared <= 324.0D) {
                player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1.0, 0), 1, 0.25, 0.3, 0.25, 0, DUST_REDSTONE);
                if (stateTicks % 20 == 0) {
                    player.getWorld().playSound(player.getLocation(), Sound.BLOCK_REDSTONE_TORCH_BURNOUT, 0.25F, 1.2F + random.nextFloat() * 0.4F);
                }
                stirNearbyRedstone(player);
            }
            if (distanceSquared > 144.0D) {
                continue;
            }
            double distance = Math.sqrt(distanceSquared);
            int amplifier = distance <= 3.0D ? 3 : (distance <= 6.0D ? 2 : (distance <= 10.0D ? 1 : 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, amplifier, false, true, true));
            int freezeTicks = distance <= 3.0D ? 120 : (distance <= 6.0D ? 90 : (distance <= 10.0D ? 60 : 40));
            player.setFreezeTicks(Math.max(player.getFreezeTicks(), freezeTicks));
            player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 1, 0.4, 0.5, 0.4, 0.01);
            disturbCompass(player, current);
        }
    }

    private void stirNearbyRedstone(Player player) {
        World world = player.getWorld();
        Location center = player.getLocation();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    Material type = block.getType();
                    if (type == Material.REDSTONE_WIRE || type == Material.REDSTONE_TORCH || type == Material.REDSTONE_WALL_TORCH
                        || type == Material.REDSTONE_LAMP || type == Material.REPEATER || type == Material.COMPARATOR) {
                        world.spawnParticle(Particle.ELECTRIC_SPARK, block.getLocation().add(0.5, 0.45, 0.5), 1, 0.15, 0.1, 0.15, 0.01);
                        world.spawnParticle(Particle.DUST, block.getLocation().add(0.5, 0.45, 0.5), 1, 0.1, 0.08, 0.1, 0.0, DUST_REDSTONE);
                    }
                }
            }
        }
    }

    private void disturbCompass(Player player, Location current) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if ((main.getType() == Material.COMPASS || off.getType() == Material.COMPASS) && stateTicks % 6 == 0) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            Location fake = current.clone().add(Math.cos(angle) * 18.0D, 0, Math.sin(angle) * 18.0D);
            player.setCompassTarget(fake);
        }
    }

    private void trackPlayerPath(Player player) {
        if (stateTicks % 5 != 0) {
            return;
        }
        trackedPath.addLast(player.getLocation().clone());
        while (trackedPath.size() > 32) {
            trackedPath.removeFirst();
        }
    }

    private void startVanishing(int durationTicks) {
        if (forcedVisibleByLight) {
            return;
        }
        vanishingTicks = Math.max(vanishingTicks, durationTicks);
    }

    private void tickVanishing() {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        if (forcedVisibleByLight) {
            vanishingTicks = 0;
            entity.removePotionEffect(PotionEffectType.INVISIBILITY);
            entity.setSilent(false);
            return;
        }
        if (vanishingTicks > 0) {
            vanishingTicks--;
            entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 10, 0, false, false, false));
            entity.setSilent(true);
        } else {
            entity.setSilent(false);
        }
    }

    private void breakVanishing() {
        vanishingTicks = 0;
    }

    private void startPhaseWalk() {
        if (forcedVisibleByLight) {
            return;
        }
        phaseWalkTicks = 20;
        breakVanishing();
    }

    private void tickPhaseWalk(Player player) {
        if (forcedVisibleByLight) {
            phaseWalkTicks = 0;
            return;
        }
        if (phaseWalkTicks <= 0) {
            return;
        }
        phaseWalkTicks--;
        Location current = getCurrentLocation();
        Vector delta = player.getLocation().toVector().subtract(current.toVector());
        if (delta.lengthSquared() > 0.001D) {
            Location next = current.clone().add(delta.normalize().multiply(0.35D));
            npc.teleport(next, PlayerTeleportEvent.TeleportCause.PLUGIN);
            if (next.getWorld() != null) {
                next.getWorld().spawnParticle(Particle.DUST, next.clone().add(0, 1, 0), 6, 0.2, 0.4, 0.2, 0, DUST_ICE);
            }
        }
    }

    private void emitGhostTellParticles() {
        Location loc = getCurrentLocation().add(0, 1, 0);
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        if (stateTicks % 3 == 0) {
            world.spawnParticle(Particle.DUST, loc, 1, 0.18, 0.24, 0.18, 0, DUST_WHITE);
        }
        if (stateTicks % 7 == 0) {
            world.spawnParticle(Particle.DUST, loc, 1, 0.14, 0.18, 0.14, 0, DUST_ICE);
        }
    }

    private void updateLightRevealState() {
        Location current = getCurrentLocation();
        World world = current.getWorld();
        if (world == null) {
            forcedVisibleByLight = false;
            return;
        }

        forcedVisibleByLight = isWithinTorchOrLanternLight(current);
        if (!forcedVisibleByLight) {
            return;
        }

        breakVanishing();
        phaseWalkTicks = 0;
        setUntargetable(false);
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            entity.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private boolean isWithinTorchOrLanternLight(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return false;
        }

        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        int radius = 8;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    Material type = block.getType();
                    if (type != Material.TORCH
                        && type != Material.WALL_TORCH
                        && type != Material.SOUL_TORCH
                        && type != Material.SOUL_WALL_TORCH
                        && type != Material.LANTERN
                        && type != Material.SOUL_LANTERN) {
                        continue;
                    }

                    double distanceSquared = block.getLocation().add(0.5D, 0.5D, 0.5D).distanceSquared(origin);
                    if (distanceSquared <= 64.0D) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean trySnatchAndThrow(Player player, LivingEntity caster) {
        if (itemSnatchCooldown > 0) {
            return false;
        }
        teleportBehindPlayer(player);
        if (recentDamageTicks > 0) {
            return false;
        }
        int slot = findSnatchSlot(player.getInventory());
        if (slot < 0) {
            return false;
        }

        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null || stack.getType().isAir()) {
            return false;
        }

        World world = player.getWorld();
        world.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, 0.5F, 0.55F);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 12, 0.25, 0.35, 0.25, 0, DUST_WHITE);
        player.sendMessage("§7§oThe ghost reaches for your item... hit it to interrupt!");

        BukkitRunnable windup = new BukkitRunnable() {
            int ticks;

            @Override
            public void run() {
                ticks++;
                Location ghostLoc = getCurrentLocation().clone().add(0, 1.05D, 0);
                World ghostWorld = ghostLoc.getWorld();
                if (ghostWorld != null) {
                    ghostWorld.spawnParticle(Particle.DUST, ghostLoc, 3, 0.2, 0.25, 0.2, 0.0, DUST_CYAN);
                }

                if (isDead() || !player.isOnline() || player.isDead() || recentDamageTicks > 0
                    || ghostLoc.distanceSquared(player.getLocation().add(0, 1, 0)) > 16.0D) {
                    vulnerableTicks = Math.max(vulnerableTicks, COUNTERPLAY_WINDOW_TICKS + 20);
                    itemSnatchCooldown = Math.max(itemSnatchCooldown, 40);
                    cancel();
                    return;
                }

                if (ticks < 16) {
                    return;
                }

                ItemStack live = player.getInventory().getItem(slot);
                if (live == null || live.getType().isAir()) {
                    cancel();
                    return;
                }

                ItemStack stolen = live.clone();
                stolen.setAmount(1);
                if (live.getAmount() <= 1) {
                    player.getInventory().setItem(slot, new ItemStack(Material.AIR));
                } else {
                    live.setAmount(live.getAmount() - 1);
                    player.getInventory().setItem(slot, live);
                }

                itemSnatchCooldown = ITEM_SNATCH_COOLDOWN_TICKS;
                world.playSound(player.getLocation(), Sound.ENTITY_ALLAY_ITEM_GIVEN, 0.6F, 0.6F);
                world.spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 8, 0.22, 0.32, 0.22, 0, DUST_WHITE);

                BukkitRunnable delayedThrow = new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (isDead() || !player.isOnline() || player.isDead() || recentDamageTicks > 0) {
                            return;
                        }
                        Location origin = getCurrentLocation().clone().add(0, 1.2, 0);
                        World throwWorld = origin.getWorld();
                        if (throwWorld == null || throwWorld != player.getWorld()) {
                            return;
                        }
                        org.bukkit.entity.Item projectile = throwWorld.dropItem(origin, stolen);
                        projectile.setPickupDelay(Integer.MAX_VALUE);
                        Vector velocity = player.getEyeLocation().toVector().subtract(origin.toVector());
                        if (velocity.lengthSquared() > 0.0001D) {
                            projectile.setVelocity(velocity.normalize().multiply(0.82D));
                        }

                        player.damage(2.5D, caster);
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 35, 1, false, true, true));

                        BukkitRunnable tracker = new BukkitRunnable() {
                            int ticks;

                            @Override
                            public void run() {
                                ticks++;
                                if (!projectile.isValid() || ticks > 35 || isDead()) {
                                    if (projectile.isValid()) {
                                        projectile.setPickupDelay(20);
                                    }
                                    cancel();
                                    return;
                                }
                                if (player.isOnline() && !player.isDead() && player.getWorld() == projectile.getWorld()
                                    && projectile.getLocation().distanceSquared(player.getLocation().add(0, 1, 0)) <= 2.25D) {
                                    player.damage(3.5D, caster);
                                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 1, false, true, true));
                                    projectile.remove();
                                    cancel();
                                }
                            }
                        };
                        tasks.add(tracker);
                        tracker.runTaskTimer(plugin, 1L, 1L);
                    }
                };
                tasks.add(delayedThrow);
                delayedThrow.runTaskLater(plugin, ITEM_SNATCH_THROW_DELAY_TICKS);
                cancel();
            }
        };
        tasks.add(windup);
        windup.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private void teleportBehindPlayer(Player player) {
        LivingEntity entity = getLivingEntity();
        if (entity == null) {
            return;
        }
        Location pl = player.getLocation();
        Vector backwards = pl.getDirection().setY(0).normalize().multiply(-1.35D);
        if (backwards.lengthSquared() < 0.001D) {
            backwards = new Vector(0, 0, -1.35D);
        }
        Location behind = pl.clone().add(backwards);
        behind.setY(behind.getWorld().getHighestBlockYAt(behind) + 1.0D);
        behind.setYaw(entity.getLocation().getYaw());
        npc.teleport(behind, PlayerTeleportEvent.TeleportCause.PLUGIN);
        World world = behind.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.DUST, behind.clone().add(0, 1, 0), 10, 0.2, 0.3, 0.2, 0.0, DUST_WHITE);
            world.playSound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 0.25F, 1.9F);
        }
    }

    private boolean hasPossessionOpportunity(Player player) {
        if (!CitizensAPI.hasImplementation()) {
            return false;
        }
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        if (registry == null) {
            return false;
        }
        Location ghost = getCurrentLocation();
        for (NPC candidate : registry) {
            if (!candidate.isSpawned() || candidate.getEntity() == null) {
                continue;
            }
            if (candidate.getId() == npc.getId() || plugin.getNPCManager().isBloodMoonNpc(candidate.getEntity())) {
                continue;
            }
            if (candidate.getEntity().getWorld() != player.getWorld() || candidate.getEntity().getWorld() != ghost.getWorld()) {
                continue;
            }
            if (candidate.getEntity().getLocation().distanceSquared(player.getLocation()) <= 36.0D
                && candidate.getEntity().getLocation().distanceSquared(ghost) <= 324.0D) {
                return true;
            }
        }
        return false;
    }

    private void handleControlledHostDeath(Player player) {
        endHostControl();
        setUntargetable(true);
        startVanishing(36);
        if (player != null && player.isOnline() && !player.isDead()) {
            teleportAway(player);
        }
        BukkitRunnable vulnerableAgain = new BukkitRunnable() {
            @Override
            public void run() {
                if (!isDead() && controlledHost == null && possessedPlayer == null) {
                    setUntargetable(false);
                }
            }
        };
        tasks.add(vulnerableAgain);
        vulnerableAgain.runTaskLater(plugin, 36L);
    }

    private int findSnatchSlot(PlayerInventory inventory) {
        int held = inventory.getHeldItemSlot();
        ItemStack heldItem = inventory.getItem(held);
        if (heldItem != null && !heldItem.getType().isAir()) {
            return held;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private void setUntargetable(boolean untargetable) {
        this.untargetable = untargetable;
        npc.setProtected(untargetable);
        LivingEntity entity = getLivingEntity();
        if (entity != null) {
            entity.setCollidable(!untargetable);
            if (untargetable) {
                entity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, false, false, false));
            }
        }
    }

    private void endHostControl() {
        if (controlledHost != null && controlledHost.isSpawned() && controlledHost.getEntity() != null) {
            try {
                if (controlledHost.hasTrait(SentinelTrait.class)) {
                    SentinelTrait sentinel = controlledHost.getOrAddTrait(SentinelTrait.class);
                    if (target != null) {
                        sentinel.removeTarget("player:" + target.getName());
                    }
                } else {
                    controlledHost.getNavigator().cancelNavigation();
                }
            } catch (Exception ignored) {
            }
            Location end = controlledHost.getEntity().getLocation();
            end.getWorld().playSound(end, Sound.ENTITY_WITHER_AMBIENT, 0.4F, 1.7F);
            end.getWorld().spawnParticle(Particle.SMOKE, end.clone().add(0, 1, 0), 12, 0.3, 0.4, 0.3, 0.02);
        }
        controlledHost = null;
        if (possessedPlayer == null) {
            setUntargetable(false);
        }
    }

    private void ejectPossession(boolean silent) {
        if (possessedPlayer == null) {
            if (controlledHost == null) {
                setUntargetable(false);
            }
            return;
        }
        Player player = possessedPlayer;
        POSSESSIONS.remove(player.getUniqueId());
        possessedPlayer = null;
        if (!silent && player.isOnline()) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.7F, 1.8F);
            player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 14, 0.4, 0.5, 0.4, 0, DUST_CYAN);
        }
        if (controlledHost == null) {
            setUntargetable(false);
        }
    }

    private void scrambleHotbar(PlayerInventory inventory) {
        int a = random.nextInt(9);
        int b = random.nextInt(9);
        ItemStack first = inventory.getItem(a);
        inventory.setItem(a, inventory.getItem(b));
        inventory.setItem(b, first);
    }

    private void destroyEcho(NPC echo) {
        if (echo == null) {
            return;
        }
        echoIllusions.remove(echo);
        if (echo.isSpawned()) {
            echo.despawn();
        }
        echo.destroy();
    }

    private void destroyEchoes() {
        for (NPC echo : new ArrayList<>(echoIllusions)) {
            destroyEcho(echo);
        }
    }

    private LivingEntity getLivingEntity() {
        if (!npc.isSpawned() || npc.getEntity() == null) {
            return null;
        }
        return npc.getEntity() instanceof LivingEntity livingEntity ? livingEntity : null;
    }

    private Player ensureTarget(double range) {
        if (target != null && target.isOnline() && !target.isDead()) {
            Location targetLocation = target.getLocation();
            Location currentLocation = getCurrentLocation();
            if (targetLocation.getWorld() == currentLocation.getWorld()
                && targetLocation.distanceSquared(currentLocation) <= range * range) {
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
        double bestDistanceSquared = range * range;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.isDead() || !player.isOnline()) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(location);
            if (distance < bestDistanceSquared) {
                bestDistanceSquared = distance;
                nearest = player;
            }
        }
        return nearest;
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

