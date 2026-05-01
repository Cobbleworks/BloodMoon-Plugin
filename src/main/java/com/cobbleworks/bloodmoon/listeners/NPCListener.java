package com.cobbleworks.bloodmoon.listeners;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ClownNPC;
import com.cobbleworks.bloodmoon.mobs.GhostNPC;
import com.cobbleworks.bloodmoon.mobs.ScarecrowNPC;
import com.cobbleworks.bloodmoon.mobs.VampireNPC;
import com.cobbleworks.bloodmoon.mobs.WerewolfNPC;
import com.cobbleworks.bloodmoon.mobs.WitchNPC;
import com.cobbleworks.bloodmoon.mobs.ZombieNPC;
import com.cobbleworks.bloodmoon.traits.ClownTrait;
import com.cobbleworks.bloodmoon.traits.GhostTrait;
import com.cobbleworks.bloodmoon.traits.ScarecrowTrait;
import com.cobbleworks.bloodmoon.traits.VampireTrait;
import com.cobbleworks.bloodmoon.traits.WerewolfTrait;
import com.cobbleworks.bloodmoon.traits.WitchTrait;
import com.cobbleworks.bloodmoon.traits.ZombieTrait;
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
            clown.onTakeDamage();
            return;
        }

        ZombieNPC zombie = plugin.getNPCManager().getZombie(event.getNPC());
        if (zombie != null) {
            if (event.getNPC().isSpawned() && event.getNPC().getEntity() != null) {
                event.getNPC().getEntity().getWorld().playSound(
                    event.getNPC().getEntity().getLocation(),
                    Sound.ENTITY_ZOMBIE_HURT, 0.9F, 0.8F);
            }
            return;
        }

        WitchNPC witch = plugin.getNPCManager().getWitch(event.getNPC());
        if (witch != null && event.getNPC().isSpawned() && event.getNPC().getEntity() != null) {
            event.getNPC().getEntity().getWorld().playSound(event.getNPC().getEntity().getLocation(), Sound.ENTITY_WITCH_HURT, 0.95F, 1.05F);
            witch.onTakeDamage(event.getDamage());
            return;
        }

        ScarecrowNPC scarecrow = plugin.getNPCManager().getScarecrow(event.getNPC());
        if (scarecrow != null && event.getNPC().isSpawned() && event.getNPC().getEntity() != null) {
            event.getNPC().getEntity().getWorld().playSound(event.getNPC().getEntity().getLocation(), Sound.ENTITY_SKELETON_HURT, 0.95F, 0.85F);
            return;
        }

        GhostNPC ghost = plugin.getNPCManager().getGhost(event.getNPC());
        if (ghost != null && event.getNPC().isSpawned() && event.getNPC().getEntity() != null) {
            if (ghost.isUntargetable()) {
                event.setCancelled(true);
                return;
            }
            event.setDamage(ghost.reduceIncomingDamage(event.getDamage()));
            ghost.onTakeDamage(event.getDamage());
            event.getNPC().getEntity().getWorld().playSound(event.getNPC().getEntity().getLocation(), Sound.ENTITY_ALLAY_HURT, 0.85F, 0.6F);
            return;
        }

        WerewolfNPC werewolf = plugin.getNPCManager().getWerewolf(event.getNPC());
        if (werewolf != null && event.getNPC().isSpawned() && event.getNPC().getEntity() != null) {
            werewolf.onTakeDamage();
            event.getNPC().getEntity().getWorld().playSound(event.getNPC().getEntity().getLocation(), Sound.ENTITY_WOLF_HURT, 0.95F, 0.75F);
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

        ZombieNPC zombie = plugin.getNPCManager().getZombie(event.getNPC());
        if (zombie != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            zombie.startDeathSequence();
            return;
        }

        WitchNPC witch = plugin.getNPCManager().getWitch(event.getNPC());
        if (witch != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            witch.startDeathSequence();
            return;
        }

        ScarecrowNPC scarecrow = plugin.getNPCManager().getScarecrow(event.getNPC());
        if (scarecrow != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            scarecrow.startDeathSequence();
            return;
        }

        GhostNPC ghost = plugin.getNPCManager().getGhost(event.getNPC());
        if (ghost != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            ghost.startDeathSequence();
            return;
        }

        WerewolfNPC werewolf = plugin.getNPCManager().getWerewolf(event.getNPC());
        if (werewolf != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            werewolf.startDeathSequence();
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
            return;
        }
        if (event.getNPC().hasTrait(ZombieTrait.class)) {
            ZombieTrait trait = event.getNPC().getOrAddTrait(ZombieTrait.class);
            trait.handleSentinelAttack(event);
            return;
        }
        if (event.getNPC().hasTrait(WitchTrait.class)) {
            WitchTrait trait = event.getNPC().getOrAddTrait(WitchTrait.class);
            trait.handleSentinelAttack(event);
            return;
        }
        if (event.getNPC().hasTrait(ScarecrowTrait.class)) {
            ScarecrowTrait trait = event.getNPC().getOrAddTrait(ScarecrowTrait.class);
            trait.handleSentinelAttack(event);
            return;
        }
        if (event.getNPC().hasTrait(GhostTrait.class)) {
            GhostTrait trait = event.getNPC().getOrAddTrait(GhostTrait.class);
            trait.handleSentinelAttack(event);
            return;
        }
        if (event.getNPC().hasTrait(WerewolfTrait.class)) {
            WerewolfTrait trait = event.getNPC().getOrAddTrait(WerewolfTrait.class);
            trait.handleSentinelAttack(event);
        }
    }
}


