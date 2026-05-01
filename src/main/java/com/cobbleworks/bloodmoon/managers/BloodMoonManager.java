package com.cobbleworks.bloodmoon.managers;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.utils.MessageUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Owns Blood Moon event lifecycle, scheduling, and active state.
 */
public final class BloodMoonManager {

    public enum DifficultyProfile {
        EASY("easy", 0.85D, 0.90D, 0.90D, 1.12D),
        MEDIUM("medium", 1.00D, 1.00D, 1.00D, 1.00D),
        HARD("hard", 1.20D, 1.25D, 1.25D, 0.85D),
        NIGHTMARE("nightmare", 1.38D, 1.55D, 1.60D, 0.72D);

        private final String token;
        private final double nonVampireHealthMultiplier;
        private final double rewardMultiplier;
        private final double expMultiplier;
        private final double abilityCadenceMultiplier;

        DifficultyProfile(String token, double nonVampireHealthMultiplier, double rewardMultiplier, double expMultiplier, double abilityCadenceMultiplier) {
            this.token = token;
            this.nonVampireHealthMultiplier = nonVampireHealthMultiplier;
            this.rewardMultiplier = rewardMultiplier;
            this.expMultiplier = expMultiplier;
            this.abilityCadenceMultiplier = abilityCadenceMultiplier;
        }

        public String getToken() {
            return token;
        }

        public double getNonVampireHealthMultiplier() {
            return nonVampireHealthMultiplier;
        }

        public double getRewardMultiplier() {
            return rewardMultiplier;
        }

        public double getExpMultiplier() {
            return expMultiplier;
        }

        public double getAbilityCadenceMultiplier() {
            return abilityCadenceMultiplier;
        }

        public static DifficultyProfile fromToken(String token) {
            if (token == null || token.isBlank()) {
                return null;
            }
            String normalized = token.trim().toLowerCase();
            for (DifficultyProfile profile : values()) {
                if (profile.token.equals(normalized)) {
                    return profile;
                }
            }
            return null;
        }
    }

    private static final long NIGHT_START = 13000L;
    private static final long SUNRISE_END = 23500L;

    private final BloodMoonPlugin plugin;
    private final Set<UUID> activeWorldIds = new HashSet<>();
    private final Map<UUID, Long> lastBloodMoonNight = new HashMap<>();
    private final Map<UUID, Long> lastNightRoll = new HashMap<>();
    private final Random random = new Random();
    private BukkitRunnable timeCheckTask;
    private BukkitRunnable vampireSpawnTask;
    private BukkitRunnable ambientParticleTask;
    private Integer chanceOverride;
    private DifficultyProfile difficultyProfile = DifficultyProfile.MEDIUM;

    private static final double VAMPIRE_PULSE_CHANCE = 0.18D;
    private static final double CLOWN_PULSE_CHANCE = 0.12D;
    private static final double ZOMBIE_PULSE_CHANCE = 0.14D;
    private static final double WITCH_PULSE_CHANCE = 0.10D;
    private static final double SCARECROW_PULSE_CHANCE = 0.08D;
    private static final double GHOST_PULSE_CHANCE = 0.07D;
    private static final double WEREWOLF_PULSE_CHANCE = 0.06D;

    public BloodMoonManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the periodic fallback time checker.
     */
    public void start() {
        stop();
        timeCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                fallbackTimeCheck();
            }
        };
        timeCheckTask.runTaskTimer(plugin, 100L, 100L);
    }

    /**
     * Stops manager-owned scheduler tasks.
     */
    public void stop() {
        if (timeCheckTask != null) {
            timeCheckTask.cancel();
            timeCheckTask = null;
        }
        stopVampireSpawnTask();
    }

    /**
     * Handles a detected night transition.
     *
     * @param world world
     */
    public void handleNightTransition(World world) {
        if (world == null || !isConfiguredWorld(world) || isActive(world)) {
            return;
        }
        if (!isNight(world)) {
            return;
        }

        long night = getNightIndex(world);
        UUID id = world.getUID();
        if (lastNightRoll.getOrDefault(id, Long.MIN_VALUE) == night) {
            return;
        }
        lastNightRoll.put(id, night);

        if (!canTriggerNaturally(world, night)) {
            return;
        }
        if (random.nextInt(getCurrentChance()) == 0) {
            startBloodMoon(world, false);
        }
    }

    /**
     * Starts a Blood Moon.
     *
     * @param world world
     * @param forced whether command-forced
     * @return true if started
     */
    public boolean startBloodMoon(World world, boolean forced) {
        if (world == null || isActive(world)) {
            return false;
        }
        if (!forced && !isConfiguredWorld(world)) {
            return false;
        }
        if (!isNight(world)) {
            return false;
        }

        activeWorldIds.add(world.getUID());
        lastBloodMoonNight.put(world.getUID(), getNightIndex(world));
        forceStorm(world);
        broadcastStart(world);

        startAmbientParticles();
        return true;
    }

    /**
     * Ends a Blood Moon in one world.
     *
     * @param world world
     * @param forced whether command-forced
     * @return true if ended
     */
    public boolean endBloodMoon(World world, boolean forced) {
        if (world == null || !isActive(world)) {
            return false;
        }

        activeWorldIds.remove(world.getUID());
        plugin.getNPCManager().cleanupWorld(world);
        stopStorm(world);
        broadcastEnd(world);

        if (activeWorldIds.isEmpty()) {
            stopVampireSpawnTask();
            stopAmbientParticles();
        }
        return true;
    }

    /**
     * Force ends every active event and clears managed NPCs.
     */
    public void forceEnd() {
        for (World world : new ArrayList<>(getActiveWorlds())) {
            endBloodMoon(world, true);
        }
        activeWorldIds.clear();
        stopVampireSpawnTask();
        stopAmbientParticles();
        plugin.getNPCManager().cleanupAll();
    }

    /**
     * Returns whether a world has an active event.
     *
     * @param world world
     * @return active state
     */
    public boolean isActive(World world) {
        return world != null && activeWorldIds.contains(world.getUID());
    }

    /**
     * Returns all active worlds.
     *
     * @return active worlds
     */
    public List<World> getActiveWorlds() {
        List<World> worlds = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (activeWorldIds.contains(world.getUID())) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    /**
     * Sets a temporary 1-in-N chance override.
     *
     * @param chance chance denominator
     */
    public void setChanceOverride(int chance) {
        chanceOverride = Math.max(1, Math.min(100, chance));
    }

    /**
     * Clears the chance override.
     */
    public void clearChanceOverride() {
        chanceOverride = null;
    }

    /**
     * Returns the active chance denominator.
     *
     * @return chance denominator
     */
    public int getCurrentChance() {
        return chanceOverride == null ? plugin.getConfigManager().getBloodMoonChance() : chanceOverride;
    }

    /**
     * Returns whether chance is temporarily overridden.
     *
     * @return true if overridden
     */
    public boolean hasChanceOverride() {
        return chanceOverride != null;
    }

    public DifficultyProfile getDifficultyProfile() {
        return difficultyProfile;
    }

    public String getDifficultyToken() {
        return difficultyProfile.getToken();
    }

    public boolean setDifficulty(String token) {
        DifficultyProfile parsed = DifficultyProfile.fromToken(token);
        if (parsed == null) {
            return false;
        }
        difficultyProfile = parsed;
        return true;
    }

    public double getNonVampireHealthMultiplier() {
        return difficultyProfile.getNonVampireHealthMultiplier();
    }

    public double getRewardMultiplier() {
        return difficultyProfile.getRewardMultiplier();
    }

    public double getExpMultiplier() {
        return difficultyProfile.getExpMultiplier();
    }

    public double getAbilityCadenceMultiplier() {
        return difficultyProfile.getAbilityCadenceMultiplier();
    }

    /**
     * Formats the time until the next roll window for status output.
     *
     * @param world world
     * @return status text
     */
    public String describeNextWindow(World world) {
        if (world == null) {
            return "unknown";
        }
        if (isActive(world)) {
            return "active now";
        }
        long time = world.getTime();
        long ticksUntil;
        if (time < NIGHT_START) {
            ticksUntil = NIGHT_START - time;
        } else {
            ticksUntil = 24000L - time + NIGHT_START;
        }
        long seconds = ticksUntil / 20L;
        return ticksUntil + " ticks (~" + seconds + "s)";
    }

    /**
     * Checks whether a world is enabled in config.
     *
     * @param world world
     * @return true if enabled
     */
    public boolean isConfiguredWorld(World world) {
        return world != null && plugin.getConfigManager().getEnabledWorlds().contains(world.getName());
    }

    /**
     * Checks whether a world is currently in night range.
     *
     * @param world world
     * @return true if night
     */
    public boolean isNight(World world) {
        long time = world.getTime();
        return time >= NIGHT_START && time < SUNRISE_END;
    }

    private void fallbackTimeCheck() {
        for (World world : plugin.getServer().getWorlds()) {
            if (isActive(world)) {
                checkSunriseEnd(world);
                continue;
            }
            long time = world.getTime();
            if (time >= NIGHT_START && time <= NIGHT_START + 120L) {
                handleNightTransition(world);
            }
        }
    }

    private void checkSunriseEnd(World world) {
        long time = world.getTime();
        if (time >= SUNRISE_END || time < NIGHT_START) {
            endBloodMoon(world, false);
        }
    }

    private void startAmbientParticles() {
        if (ambientParticleTask != null) {
            return;
        }
        ambientParticleTask = new BukkitRunnable() {
            @Override
            public void run() {
                tickAmbientParticles();
            }
        };
        ambientParticleTask.runTaskTimer(plugin, 40L, 40L);
    }

    private void stopAmbientParticles() {
        if (ambientParticleTask != null) {
            ambientParticleTask.cancel();
            ambientParticleTask = null;
        }
    }

    private void tickAmbientParticles() {
        for (World world : getActiveWorlds()) {
            for (Player player : world.getPlayers()) {
                Location origin = player.getLocation();
                for (int i = 0; i < 9; i++) {
                    double ox = (random.nextDouble() * 2.0 - 1.0) * 14.0;
                    double oy = random.nextDouble() * 6.0;
                    double oz = (random.nextDouble() * 2.0 - 1.0) * 14.0;
                    Location loc = origin.clone().add(ox, oy, oz);
                    world.spawnParticle(Particle.ASH, loc, 2, 0.12D, 0.18D, 0.12D, 0.0D);
                }
            }
        }
    }

    private boolean canTriggerNaturally(World world, long night) {
        long last = lastBloodMoonNight.getOrDefault(world.getUID(), Long.MIN_VALUE);
        return last != night && last != night - 1L;
    }

    private long getNightIndex(World world) {
        return world.getFullTime() / 24000L;
    }

    private void forceStorm(World world) {
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(12000);
        world.setThunderDuration(0);
    }

    private void stopStorm(World world) {
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(12000);
        world.setThunderDuration(12000);
    }

    private void broadcastStart(World world) {
        String title = plugin.getConfigManager().getEventStartMessage();
        MessageUtils.title(world, title, "§7The night hungers.", 10, 60, 10);
        MessageUtils.broadcastToWorld(world, title);
        MessageUtils.playWorldSound(world, Sound.ENTITY_WITHER_SPAWN, 1.0F, 0.5F);
    }

    private void broadcastEnd(World world) {
        MessageUtils.broadcastToWorld(world, plugin.getConfigManager().getEventEndMessage());
        MessageUtils.playWorldSound(world, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0F, 1.0F);
    }

    private void spawnInitialVampires(World world) {
        for (Player player : world.getPlayers()) {
            spawnSpecialsNearPlayerByChance(player, 0.55D);
        }
    }

    private void startVampireSpawnTask() {
        if (vampireSpawnTask != null) {
            return;
        }
        vampireSpawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : getActiveWorlds()) {
                    spawnVampirePulse(world);
                }
            }
        };
        vampireSpawnTask.runTaskTimer(plugin, 200L, 200L);
    }

    private void stopVampireSpawnTask() {
        if (vampireSpawnTask != null) {
            vampireSpawnTask.cancel();
            vampireSpawnTask = null;
        }
    }

    private void spawnVampirePulse(World world) {
        if (world.getPlayers().isEmpty()) {
            return;
        }

        for (Player player : world.getPlayers()) {
            spawnSpecialsNearPlayerByChance(player, 1.0D);
        }
    }

    private void spawnSpecialsNearPlayerByChance(Player player, double chanceMultiplier) {
        if (player == null || !player.isOnline()) {
            return;
        }

        maybeSpawn(player,
            plugin.getNPCManager().countVampiresNear(player, 96.0D),
            plugin.getConfigManager().getMaxVampiresPerPlayer(),
            VAMPIRE_PULSE_CHANCE * chanceMultiplier,
            () -> plugin.getNPCManager().spawnVampireNear(player));

        maybeSpawn(player,
            plugin.getNPCManager().countClownsNear(player, 96.0D),
            plugin.getConfigManager().getClownMaxPerPlayer(),
            CLOWN_PULSE_CHANCE * chanceMultiplier,
            () -> plugin.getNPCManager().spawnClownNear(player));

        maybeSpawn(player,
            plugin.getNPCManager().countZombiesNear(player, 96.0D),
            plugin.getConfigManager().getZombieMaxPerPlayer(),
            ZOMBIE_PULSE_CHANCE * chanceMultiplier,
            () -> plugin.getNPCManager().spawnZombieNear(player));

        maybeSpawn(player,
            plugin.getNPCManager().countWitchesNear(player, 96.0D),
            plugin.getConfigManager().getWitchMaxPerPlayer(),
            WITCH_PULSE_CHANCE * chanceMultiplier,
            () -> plugin.getNPCManager().spawnWitchNear(player));

        maybeSpawn(player,
            plugin.getNPCManager().countScarecrowsNear(player, 96.0D),
            plugin.getConfigManager().getScarecrowMaxPerPlayer(),
            SCARECROW_PULSE_CHANCE * chanceMultiplier,
            () -> plugin.getNPCManager().spawnScarecrowNear(player));

        maybeSpawn(player,
            plugin.getNPCManager().countGhostsNear(player, 96.0D),
            plugin.getConfigManager().getGhostMaxPerPlayer(),
            GHOST_PULSE_CHANCE * chanceMultiplier,
            () -> plugin.getNPCManager().spawnGhostNear(player));

        maybeSpawn(player,
            plugin.getNPCManager().countWerewolvesNear(player, 96.0D),
            plugin.getConfigManager().getWerewolfMaxPerPlayer(),
            WEREWOLF_PULSE_CHANCE * chanceMultiplier,
            () -> plugin.getNPCManager().spawnWerewolfNear(player));
    }

    private void maybeSpawn(Player player, int currentNearCount, int maxPerPlayer, double chance, Runnable spawnAction) {
        if (maxPerPlayer <= 0 || currentNearCount >= maxPerPlayer) {
            return;
        }
        if (random.nextDouble() <= Math.max(0.0D, Math.min(1.0D, chance))) {
            spawnAction.run();
        }
    }


}


