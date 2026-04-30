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
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
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

        // 15 % outgoing-damage reduction when the player has the zombie-weakened debuff
        if (event.getDamager() instanceof Player attackingPlayer
                && attackingPlayer.hasMetadata("bloodmoon-zombie-weakened")) {
            long expiry = attackingPlayer.getMetadata("bloodmoon-zombie-weakened").get(0).asLong();
            if (System.currentTimeMillis() < expiry) {
                event.setDamage(event.getDamage() * 0.85D);
            } else {
                attackingPlayer.removeMetadata("bloodmoon-zombie-weakened", plugin);
            }
        }

        if (plugin.getNPCManager().getClown(event.getEntity()) != null) {
            // First hit should force the jester into snapped combat mode.
            plugin.getNPCManager().getClown(event.getEntity()).triggerSnapFromDamage();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();

        // Cancel fall damage for Blood Moon NPCs / bats
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            if (plugin.getNPCManager().isBloodMoonNpc(entity)
                    || plugin.getNPCManager().getActiveBatIds().contains(entity.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }

        // Accelerated armor wear when the player has the acid-hit debuff
        if (entity instanceof Player victim && victim.hasMetadata("bloodmoon-zombie-acid-hit")) {
            long expiry = victim.getMetadata("bloodmoon-zombie-acid-hit").get(0).asLong();
            if (System.currentTimeMillis() < expiry) {
                damageArmor(victim, 3);
            } else {
                victim.removeMetadata("bloodmoon-zombie-acid-hit", plugin);
            }
        }
    }

    /** Applies extra durability damage to all worn armor pieces. */
    private void damageArmor(Player player, int amount) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (ItemStack piece : armor) {
            if (piece == null || piece.getType().isAir()) {
                continue;
            }
            if (piece.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d) {
                int maxDur = piece.getType().getMaxDurability();
                int newDmg = Math.min(d.getDamage() + amount, maxDur - 1);
                d.setDamage(newDmg);
                piece.setItemMeta(d);
            }
        }
        player.getInventory().setArmorContents(armor);
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
        if (!projectile.hasMetadata("bloodmoon-zombie-acid-spit")) {
            return;
        }

        int npcId = projectile.getMetadata("bloodmoon-zombie-acid-spit").isEmpty()
            ? -1
            : projectile.getMetadata("bloodmoon-zombie-acid-spit").get(0).asInt();
        ZombieNPC zombie = plugin.getNPCManager().getZombie(npcId);
        if (zombie == null) {
            projectile.remove();
            return;
        }

        projectile.remove();
        Player hitPlayer = event.getHitEntity() instanceof Player p ? p : null;
        if (hitPlayer != null) {
            zombie.handleAcidSpit(hitPlayer);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        if (!item.hasMetadata("bloodmoon-clown-gift") && !item.hasMetadata("bloodmoon-clown-bait")) {
            return;
        }

        if (item.hasMetadata("bloodmoon-clown-bait")) {
            event.setCancelled(true);
            item.remove();
            int npcId = item.getMetadata("bloodmoon-clown-bait").get(0).asInt();
            com.yourname.bloodmoon.mobs.ClownNPC clown = plugin.getNPCManager().getClown(npcId);
            if (clown != null) clown.triggerBaitPickup(player);
            return;
        }

        event.setCancelled(true);
        item.remove();

        player.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, player.getLocation().add(0, 0.6, 0), 1);
        player.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, player.getLocation().add(0, 0.7, 0), 24, 0.35, 0.25, 0.35, 0.03);
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 0.7F, 1.5F);
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NAUSEA, 60, 0, true, true, true));
        player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 40, 1, true, true, true));
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

        // Void Cage enforcement
        Player vPlayer = event.getPlayer();
        if (vPlayer.hasMetadata("bloodmoon-witch-void-cage")) {
            String data = vPlayer.getMetadata("bloodmoon-witch-void-cage").get(0).asString();
            String[] parts = data.split(",");
            if (parts.length == 4) {
                double cx = Double.parseDouble(parts[0]);
                double cy = Double.parseDouble(parts[1]);
                double cz = Double.parseDouble(parts[2]);
                double r  = Double.parseDouble(parts[3]);
                Location to = event.getTo();
                double dx = to.getX() - cx;
                double dz = to.getZ() - cz;
                if (dx * dx + dz * dz > r * r) {
                    event.setTo(event.getFrom());
                    return;
                }
            }
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.hasMetadata("bloodmoon-witch-silenced")) return;
        long expiry = player.getMetadata("bloodmoon-witch-silenced").get(0).asLong();
        if (System.currentTimeMillis() < expiry) {
            event.setCancelled(true);
        } else {
            player.removeMetadata("bloodmoon-witch-silenced", plugin);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!player.hasMetadata("bloodmoon-witch-silenced")) return;
        long expiry = player.getMetadata("bloodmoon-witch-silenced").get(0).asLong();
        if (System.currentTimeMillis() < expiry) {
            event.setCancelled(true);
        } else {
            player.removeMetadata("bloodmoon-witch-silenced", plugin);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        plugin.getBleedEffect().cancelBleed(p);
        plugin.getInfectionEffect().cancelInfection(p);
        plugin.getDecayPlagueEffect().cancel(p);
        plugin.getMindHexEffect().cancel(p);
        p.removeMetadata("bloodmoon-witch-void-cage", plugin);
        p.removeMetadata("bloodmoon-witch-silenced", plugin);
    }
}
