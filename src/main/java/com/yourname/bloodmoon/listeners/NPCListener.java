package com.yourname.bloodmoon.listeners;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.ClownNPC;
import com.yourname.bloodmoon.mobs.VampireNPC;
import com.yourname.bloodmoon.traits.ClownTrait;
import com.yourname.bloodmoon.traits.VampireTrait;
import net.citizensnpcs.api.event.NPCDamageEvent;
import net.citizensnpcs.api.event.NPCDeathEvent;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

/**
 * Handles Citizens and Sentinel events for Blood Moon NPCs.
 */
public final class NPCListener implements Listener {

    private final BloodMoonPlugin plugin;

    public NPCListener(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDamage(NPCDamageEvent event) {
        VampireNPC vampire = plugin.getNPCManager().getVampire(event.getNPC());
        if (vampire != null) {
            event.setDamage(vampire.reduceIncomingDamage(event.getDamage()));
            if (event.getNPC().isSpawned() && event.getNPC().getEntity() != null) {
                event.getNPC().getEntity().getWorld().playSound(event.getNPC().getEntity().getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_HURT, 0.8F, 0.65F);
            }
            return;
        }

        ClownNPC clown = plugin.getNPCManager().getClown(event.getNPC());
        if (clown != null && event.getNPC().isSpawned() && event.getNPC().getEntity() != null) {
            event.getNPC().getEntity().getWorld().playSound(event.getNPC().getEntity().getLocation(), Sound.ENTITY_WITCH_HURT, 0.9F, 1.2F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDeath(NPCDeathEvent event) {
        VampireNPC vampire = plugin.getNPCManager().getVampire(event.getNPC());
        if (vampire != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            vampire.startDeathSequence();
            return;
        }

        ClownNPC clown = plugin.getNPCManager().getClown(event.getNPC());
        if (clown != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            clown.startDeathSequence();
            return;
        }

    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSentinelAttack(SentinelAttackEvent event) {
        if (event.getNPC().hasTrait(VampireTrait.class)) {
            VampireTrait trait = event.getNPC().getOrAddTrait(VampireTrait.class);
            trait.handleSentinelAttack(event);
            return;
        }
        if (event.getNPC().hasTrait(ClownTrait.class)) {
            ClownTrait trait = event.getNPC().getOrAddTrait(ClownTrait.class);
            trait.handleSentinelAttack(event);
        }
    }
}
