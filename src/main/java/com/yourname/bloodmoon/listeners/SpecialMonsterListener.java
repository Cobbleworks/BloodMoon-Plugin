package com.yourname.bloodmoon.listeners;

import com.yourname.bloodmoon.BloodMoonPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Handles special Blood Moon monster death drops.
 */
public final class SpecialMonsterListener implements Listener {

    private final BloodMoonPlugin plugin;

    public SpecialMonsterListener(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        plugin.getSpecialMonsterManager().handleDeath(event);
    }
}
