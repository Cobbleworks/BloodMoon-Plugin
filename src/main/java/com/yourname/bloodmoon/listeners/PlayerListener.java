package com.yourname.bloodmoon.listeners;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.VampireNPC;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
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
        Entity damager = event.getDamager();
        boolean bloodMoonBat = plugin.getNPCManager().getActiveBatIds().contains(damager.getUniqueId());
        VampireNPC vampire = plugin.getNPCManager().getVampire(damager);

        if (event.isCancelled() && (vampire != null || bloodMoonBat) && event.getEntity() instanceof LivingEntity) {
            event.setCancelled(false);
        }

        if (event.isCancelled()) {
            return;
        }

        if (vampire != null && event.getEntity() instanceof LivingEntity livingEntity) {
            event.setDamage(event.getDamage() * vampire.getHemoplagueDamageMultiplier(livingEntity));
        }
        if (vampire != null && event.getEntity() instanceof Player player) {
            vampire.onMeleeHit(player);
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
        }
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
            return;
        }
        proximityChecks.put(uuid, now);
        plugin.getNPCManager().checkDisguisedProximity(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        plugin.getBleedEffect().cancelBleed(event.getEntity());
    }
}
