package com.yourname.bloodmoon.listeners;

import com.yourname.bloodmoon.BloodMoonPlugin;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Watches world time/weather lifecycle events for Blood Moon transitions.
 */
public final class BloodMoonListener implements Listener {

    private static final long NIGHT_START = 13000L;

    private final BloodMoonPlugin plugin;

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

    private boolean crossesNightStart(long oldTime, long newTime) {
        if (oldTime < NIGHT_START && newTime >= NIGHT_START) {
            return true;
        }
        return oldTime > newTime && newTime >= NIGHT_START;
    }
}
