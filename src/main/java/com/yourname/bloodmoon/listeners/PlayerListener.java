package com.yourname.bloodmoon.listeners;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.VampireNPC;
import com.yourname.bloodmoon.mobs.WitchNPC;
import com.yourname.bloodmoon.mobs.ZombieNPC;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Handles player combat, bleed cleansing, and bat proximity checks.
 */
public final class PlayerListener implements Listener {

    private final BloodMoonPlugin plugin;
    private final Map<UUID, Long> proximityChecks = new HashMap<>();

    public PlayerListener(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity().hasMetadata("bloodmoon-witch-clone")) {
            int npcId = event.getEntity().getMetadata("bloodmoon-witch-clone").isEmpty()
                ? -1
                : event.getEntity().getMetadata("bloodmoon-witch-clone").get(0).asInt();
            WitchNPC witch = plugin.getNPCManager().getWitch(npcId);
            if (witch != null) {
                witch.handleCloneHit(event.getEntity());
            } else {
                event.getEntity().remove();
            }
            event.setCancelled(true);
            return;
        }

        Entity damager = event.getDamager();
        boolean bloodMoonBat = plugin.getNPCManager().getActiveBatIds().contains(damager.getUniqueId());
        VampireNPC vampire = plugin.getNPCManager().getVampire(damager);

        if (event.isCancelled() && (vampire != null || bloodMoonBat) && event.getEntity() instanceof LivingEntity) {
            event.setCancelled(false);
        }

        if (event.isCancelled()) {
            return;
        }

        if (event.getEntity() instanceof Zombie shambling && plugin.getNPCManager().isShamblingZombie(shambling)) {
            Player aggressor = event.getDamager() instanceof Player player ? player : null;
            plugin.getNPCManager().transformShamblingZombie(shambling, aggressor);
            event.setCancelled(true);
            return;
        }

        if (vampire != null && event.getEntity() instanceof LivingEntity livingEntity) {
            event.setDamage(event.getDamage() * vampire.getHemoplagueDamageMultiplier(livingEntity));
        }
        if (vampire != null && event.getEntity() instanceof Player player) {
            vampire.onMeleeHit(player);
        }

        ZombieNPC zombie = plugin.getNPCManager().getZombie(damager);
        if (zombie != null && event.getEntity() instanceof Player player) {
            zombie.onMeleeHit(player);
        }

        if (plugin.getNPCManager().getClown(event.getEntity()) != null) {
            // First hit should force the jester into snapped combat mode.
            plugin.getNPCManager().getClown(event.getEntity()).triggerSnapFromDamage();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        Entity entity = event.getEntity();
        if (plugin.getNPCManager().isBloodMoonNpc(entity)
            || plugin.getNPCManager().getActiveBatIds().contains(entity.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        if (event.getItem().getType() == Material.MILK_BUCKET) {
            plugin.getBleedEffect().cancelBleed(event.getPlayer());
            plugin.getInfectionEffect().cancelInfection(event.getPlayer());
            plugin.getDecayPlagueEffect().cancel(event.getPlayer());
            plugin.getMindHexEffect().cancel(event.getPlayer());
            return;
        }
        if (event.getItem().getType() == Material.GOLDEN_APPLE || event.getItem().getType() == Material.ENCHANTED_GOLDEN_APPLE) {
            plugin.getInfectionEffect().cancelInfection(event.getPlayer());
            plugin.getDecayPlagueEffect().cancel(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!projectile.hasMetadata("bloodmoon-zombie-vomit")) {
            return;
        }

        int npcId = projectile.getMetadata("bloodmoon-zombie-vomit").isEmpty()
            ? -1
            : projectile.getMetadata("bloodmoon-zombie-vomit").get(0).asInt();
        ZombieNPC zombie = plugin.getNPCManager().getZombie(npcId);
        if (zombie == null) {
            projectile.remove();
            return;
        }

        Player directHitPlayer = event.getHitEntity() instanceof Player p ? p : null;
        zombie.handleVomitImpact(projectile, projectile.getLocation(), directHitPlayer);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
            && event.getFrom().getBlockY() == event.getTo().getBlockY()
            && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        long now = System.currentTimeMillis();
        UUID uuid = event.getPlayer().getUniqueId();
        if (now - proximityChecks.getOrDefault(uuid, 0L) < 250L) {
            plugin.getMindHexEffect().tick(event.getPlayer(), event.getFrom(), event.getTo());
            return;
        }
        proximityChecks.put(uuid, now);
        plugin.getNPCManager().checkDisguisedProximity(event.getPlayer());
        plugin.getMindHexEffect().tick(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        plugin.getBleedEffect().cancelBleed(event.getEntity());
        plugin.getInfectionEffect().cancelInfection(event.getEntity());
        plugin.getDecayPlagueEffect().cancel(event.getEntity());
        plugin.getMindHexEffect().cancel(event.getEntity());
    }
}
