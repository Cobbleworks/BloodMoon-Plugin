package com.cobbleworks.bloodmoon.listeners;

import com.cobbleworks.bloodmoon.npc.BlockfolkNpc;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ClownNPC;
import com.cobbleworks.bloodmoon.mobs.GhostNPC;
import com.cobbleworks.bloodmoon.mobs.ScarecrowNPC;
import com.cobbleworks.bloodmoon.mobs.VampireNPC;
import com.cobbleworks.bloodmoon.mobs.WerewolfNPC;
import com.cobbleworks.bloodmoon.mobs.WitchNPC;
import com.cobbleworks.bloodmoon.mobs.ZombieNPC;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;

/**
 * Handles Bukkit damage and death events for Blood Moon NPCs.
 */
public final class NPCListener implements Listener {

    private final BloodMoonPlugin plugin;

    public NPCListener(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNpcDamage(EntityDamageEvent event) {
        BlockfolkNpc npc = resolveBloodMoonNpc(event.getEntity());
        if (npc == null) return;
        event.setDamage(npc.reduceArmorDamage(event.getDamage()));

        VampireNPC vampire = plugin.getNPCManager().getVampire(npc);
        if (vampire != null) {
            event.setDamage(vampire.reduceIncomingDamage(event.getDamage()));
            if (npc.isSpawned() && npc.getEntity() != null) {
                npc.getEntity().getWorld().playSound(npc.getEntity().getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_HURT, 0.8F, 0.65F);
            }
            return;
        }

        ClownNPC clown = plugin.getNPCManager().getClown(npc);
        if (clown != null && npc.isSpawned() && npc.getEntity() != null) {
            npc.getEntity().getWorld().playSound(npc.getEntity().getLocation(), Sound.ENTITY_WITCH_HURT, 0.9F, 1.2F);
            clown.onTakeDamage();
            return;
        }

        ZombieNPC zombie = plugin.getNPCManager().getZombie(npc);
        if (zombie != null) {
            if (npc.isSpawned() && npc.getEntity() != null) {
                npc.getEntity().getWorld().playSound(
                    npc.getEntity().getLocation(),
                    Sound.ENTITY_ZOMBIE_HURT, 0.9F, 0.8F);
            }
            zombie.onTakeDamage();
            return;
        }

        WitchNPC witch = plugin.getNPCManager().getWitch(npc);
        if (witch != null && npc.isSpawned() && npc.getEntity() != null) {
            npc.getEntity().getWorld().playSound(npc.getEntity().getLocation(), Sound.ENTITY_WITCH_HURT, 0.95F, 1.05F);
            witch.onTakeDamage(event.getDamage());
            return;
        }

        ScarecrowNPC scarecrow = plugin.getNPCManager().getScarecrow(npc);
        if (scarecrow != null && npc.isSpawned() && npc.getEntity() != null) {
            npc.getEntity().getWorld().playSound(npc.getEntity().getLocation(), Sound.ENTITY_SKELETON_HURT, 0.95F, 0.85F);
            scarecrow.onTakeDamage();
            return;
        }

        GhostNPC ghost = plugin.getNPCManager().getGhost(npc);
        if (ghost != null && npc.isSpawned() && npc.getEntity() != null) {
            if (ghost.isUntargetable()) {
                event.setCancelled(true);
                return;
            }
            event.setDamage(ghost.reduceIncomingDamage(event.getDamage()));
            ghost.onTakeDamage(event.getDamage());
            npc.getEntity().getWorld().playSound(npc.getEntity().getLocation(), Sound.ENTITY_ALLAY_HURT, 0.85F, 0.6F);
            return;
        }

        WerewolfNPC werewolf = plugin.getNPCManager().getWerewolf(npc);
        if (werewolf != null && npc.isSpawned() && npc.getEntity() != null) {
            werewolf.onTakeDamage();
            npc.getEntity().getWorld().playSound(npc.getEntity().getLocation(), Sound.ENTITY_WOLF_HURT, 0.95F, 0.75F);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        BlockfolkNpc attacker = resolveBloodMoonNpc(event.getDamager());
        BlockfolkNpc victim = resolveBloodMoonNpc(event.getEntity());
        if (attacker == null || victim == null || attacker.equals(victim)) {
            return;
        }

        // Do not proactively target each other, but retaliate if struck.
        if (attacker.isSpawned() && victim.isSpawned()) {
            attacker.getNavigator().setTarget(event.getEntity(), true);
            victim.getNavigator().setTarget(event.getDamager(), true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDeath(EntityDeathEvent event) {
        BlockfolkNpc npc = resolveBloodMoonNpc(event.getEntity());
        if (npc == null) return;

        VampireNPC vampire = plugin.getNPCManager().getVampire(npc);
        if (vampire != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            vampire.startDeathSequence();
            return;
        }

        ClownNPC clown = plugin.getNPCManager().getClown(npc);
        if (clown != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            clown.startDeathSequence();
            return;
        }

        ZombieNPC zombie = plugin.getNPCManager().getZombie(npc);
        if (zombie != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            zombie.startDeathSequence();
            return;
        }

        WitchNPC witch = plugin.getNPCManager().getWitch(npc);
        if (witch != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            witch.startDeathSequence();
            return;
        }

        ScarecrowNPC scarecrow = plugin.getNPCManager().getScarecrow(npc);
        if (scarecrow != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            scarecrow.startDeathSequence();
            return;
        }

        GhostNPC ghost = plugin.getNPCManager().getGhost(npc);
        if (ghost != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            ghost.startDeathSequence();
            return;
        }

        WerewolfNPC werewolf = plugin.getNPCManager().getWerewolf(npc);
        if (werewolf != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            werewolf.startDeathSequence();
        }

    }

    /**
     * Prevents vanilla mobs from targeting Blood Moon NPCs.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() == null) return;
        if (resolveBloodMoonNpc(event.getTarget()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents vanilla mobs (and stray projectiles from mobs) from damaging Blood Moon NPCs.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDamageNpc(EntityDamageByEntityEvent event) {
        if (resolveBloodMoonNpc(event.getEntity()) == null) return;
        Entity damager = event.getDamager();
        if (damager instanceof Player) return;
        if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player) return;
        if (resolveBloodMoonNpc(damager) != null) return;
        event.setCancelled(true);
    }

    private BlockfolkNpc resolveBloodMoonNpc(Entity entity) {
        if (entity == null) {
            return null;
        }
        VampireNPC vampire = plugin.getNPCManager().getVampire(entity);
        if (vampire != null) {
            return vampire.getNpc();
        }
        ClownNPC clown = plugin.getNPCManager().getClown(entity);
        if (clown != null) {
            return clown.getNpc();
        }
        ZombieNPC zombie = plugin.getNPCManager().getZombie(entity);
        if (zombie != null) {
            return zombie.getNpc();
        }
        WitchNPC witch = plugin.getNPCManager().getWitch(entity);
        if (witch != null) {
            return witch.getNpc();
        }
        ScarecrowNPC scarecrow = plugin.getNPCManager().getScarecrow(entity);
        if (scarecrow != null) {
            return scarecrow.getNpc();
        }
        GhostNPC ghost = plugin.getNPCManager().getGhost(entity);
        if (ghost != null) {
            return ghost.getNpc();
        }
        WerewolfNPC werewolf = plugin.getNPCManager().getWerewolf(entity);
        return werewolf == null ? null : werewolf.getNpc();
    }
}

