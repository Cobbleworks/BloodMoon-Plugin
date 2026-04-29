package com.yourname.bloodmoon.listeners;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.VampireNPC;
import com.yourname.bloodmoon.traits.VampireTrait;
import net.citizensnpcs.api.event.NPCDamageEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

/**
 * Handles Citizens and Sentinel events for vampire NPCs.
 */
public final class NPCListener implements Listener {

    private final BloodMoonPlugin plugin;

    public NPCListener(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDamage(NPCDamageEvent event) {
        VampireNPC vampire = plugin.getNPCManager().getVampire(event.getNPC());
        if (vampire == null) {
            return;
        }
        event.setDamage(vampire.reduceIncomingDamage(event.getDamage()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDeath(NPCDeathEvent event) {
        VampireNPC vampire = plugin.getNPCManager().getVampire(event.getNPC());
        if (vampire == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        vampire.startDeathSequence();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSentinelAttack(SentinelAttackEvent event) {
        if (!event.getNPC().hasTrait(VampireTrait.class)) {
            return;
        }
        VampireTrait trait = event.getNPC().getOrAddTrait(VampireTrait.class);
        trait.handleSentinelAttack(event);
    }
}
