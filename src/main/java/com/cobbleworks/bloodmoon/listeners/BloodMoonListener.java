package com.cobbleworks.bloodmoon.listeners;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import java.util.Comparator;
import java.util.Random;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Watches world time/weather lifecycle events for Blood Moon transitions.
 */
public final class BloodMoonListener implements Listener {

    private static final long NIGHT_START = 13000L;

    private final BloodMoonPlugin plugin;
    private final Random random = new Random();

    public BloodMoonListener(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCitizensEnable(CitizensEnableEvent event) {
        plugin.getNPCManager().initializeCitizens();
    }

    @EventHandler
    public void onTimeSkip(TimeSkipEvent event) {
        World world = event.getWorld();
        long oldTime = world.getTime();
        long newTime = Math.floorMod(oldTime + event.getSkipAmount(), 24000L);
        if (crossesNightStart(oldTime, newTime)) {
            plugin.getBloodMoonManager().handleNightTransition(world);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (plugin.getBloodMoonManager().isConfiguredWorld(event.getWorld())) {
            plugin.getLogger().fine("BloodMoon world registered: " + event.getWorld().getName());
        }
    }

    @EventHandler
    public void onNpcDeath(NPCDeathEvent event) {
        if (plugin.getNPCManager().getVampire(event.getNPC()) != null) {
            event.setDroppedExp(0);
            event.getDrops().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onNaturalMonsterSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
            && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }

        if (!(event.getEntity() instanceof Monster)) {
            return;
        }

        World world = event.getLocation().getWorld();
        if (world == null || !plugin.getBloodMoonManager().isActive(world)) {
            return;
        }

        if (random.nextDouble() > plugin.getConfigManager().getSpecialMobReplaceChance()) {
            return;
        }

        Player nearest = world.getPlayers().stream()
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(event.getLocation())))
            .orElse(null);

        boolean spawned = trySpawnReplacement(event.getEntity(), nearest);
        if (spawned) {
            event.setCancelled(true);
            event.getEntity().remove();
        }
    }

    private boolean trySpawnReplacement(Entity vanillaEntity, Player nearestPlayer) {
        double roll = random.nextDouble();

        if (roll < 0.28D) {
            return plugin.getNPCManager().spawnVampire(vanillaEntity.getLocation(), nearestPlayer).isPresent();
        }
        if (roll < 0.46D) {
            return plugin.getNPCManager().spawnClown(vanillaEntity.getLocation()).isPresent();
        }
        if (roll < 0.64D) {
            return plugin.getNPCManager().spawnZombie(vanillaEntity.getLocation(), nearestPlayer).isPresent();
        }
        if (roll < 0.76D) {
            return plugin.getNPCManager().spawnWitch(vanillaEntity.getLocation(), nearestPlayer).isPresent();
        }
        if (roll < 0.86D) {
            return plugin.getNPCManager().spawnScarecrow(vanillaEntity.getLocation(), nearestPlayer).isPresent();
        }
        if (roll < 0.94D) {
            return plugin.getNPCManager().spawnGhost(vanillaEntity.getLocation(), nearestPlayer).isPresent();
        }
        return plugin.getNPCManager().spawnWerewolf(vanillaEntity.getLocation(), nearestPlayer).isPresent();
    }

    private boolean crossesNightStart(long oldTime, long newTime) {
        if (oldTime < NIGHT_START && newTime >= NIGHT_START) {
            return true;
        }
        return oldTime > newTime && newTime >= NIGHT_START;
    }
}


