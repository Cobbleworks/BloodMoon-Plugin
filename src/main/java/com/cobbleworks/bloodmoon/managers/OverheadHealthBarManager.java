package com.cobbleworks.bloodmoon.managers;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ClownNPC;
import com.cobbleworks.bloodmoon.mobs.GhostNPC;
import com.cobbleworks.bloodmoon.mobs.ScarecrowNPC;
import com.cobbleworks.bloodmoon.mobs.VampireNPC;
import com.cobbleworks.bloodmoon.mobs.WerewolfNPC;
import com.cobbleworks.bloodmoon.mobs.WitchNPC;
import com.cobbleworks.bloodmoon.mobs.ZombieNPC;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Renders overhead segmented health bars directly above active Blood Moon NPCs.
 */
public final class OverheadHealthBarManager {

    private static final String BAR_OWNER_METADATA = "bloodmoon-healthbar-owner";

    private final BloodMoonPlugin plugin;
    private final NamespacedKey barOwnerKey;
    private final Map<UUID, ArmorStand> barEntities = new HashMap<>();
    private final Set<UUID> touchedThisTick = new HashSet<>();
    private int purgeCounter = 0;
    private BukkitRunnable updateTask;

    public OverheadHealthBarManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
        this.barOwnerKey = new NamespacedKey(plugin, BAR_OWNER_METADATA);
    }

    public void start() {
        stop();
        purgeAllManagedBars();
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        };
        updateTask.runTaskTimer(plugin, 1L, 10L);
    }

    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        clearAllBars();
    }

    public boolean toggle(Player player) {
        return true;
    }

    public void setEnabled(Player player, boolean enabled) {
        // Overhead NPC bars are always active by design.
    }

    public boolean isEnabled(Player player) {
        return true;
    }

    private void tick() {
        touchedThisTick.clear();

        for (VampireNPC vampire : plugin.getNPCManager().getActiveVampires()) {
            if (vampire == null || vampire.isDead()) {
                continue;
            }
            LivingEntity carrier = vampire.getHealthBarCarrier();
            applyOverheadBarEntity(carrier, vampire.getCurrentHealth(), vampire.getMaximumHealth());
            hideNpcEntityNameplate(vampire.getNpc(), carrier);
        }

        for (ClownNPC clown : plugin.getNPCManager().getActiveClowns()) {
            if (clown == null || clown.isDead()) {
                continue;
            }
            applyOverheadBar(clown.getNpc(), clown.getCurrentHealth(), clown.getMaximumHealth());
        }

        for (ZombieNPC zombie : plugin.getNPCManager().getActiveZombies()) {
            if (zombie == null || zombie.isDead()) {
                continue;
            }
            applyOverheadBar(zombie.getNpc(), zombie.getCurrentHealth(), zombie.getMaximumHealth());
        }

        for (WitchNPC witch : plugin.getNPCManager().getActiveWitches()) {
            if (witch == null || witch.isDead()) {
                continue;
            }
            applyOverheadBar(witch.getNpc(), witch.getCurrentHealth(), witch.getMaximumHealth());
        }

        for (ScarecrowNPC scarecrow : plugin.getNPCManager().getActiveScarecrows()) {
            if (scarecrow == null || scarecrow.isDead()) {
                continue;
            }
            applyOverheadBar(scarecrow.getNpc(), scarecrow.getCurrentHealth(), scarecrow.getMaximumHealth());
        }

        for (GhostNPC ghost : plugin.getNPCManager().getActiveGhosts()) {
            if (ghost == null || ghost.isDead()) {
                continue;
            }
            applyOverheadBar(ghost.getNpc(), ghost.getCurrentHealth(), ghost.getMaximumHealth());
        }

        for (WerewolfNPC werewolf : plugin.getNPCManager().getActiveWerewolves()) {
            if (werewolf == null || werewolf.isDead()) {
                continue;
            }
            applyOverheadBar(werewolf.getNpc(), werewolf.getCurrentHealth(), werewolf.getMaximumHealth());
        }

        cleanupUntouchedBars();

        // Every 200 ticks (~10 s) scan all loaded chunks for orphaned bars
        if (++purgeCounter >= 20) {
            purgeCounter = 0;
            purgeOrphanedBars();
        }
    }

    private void applyOverheadBar(NPC npc, double currentHealth, double maximumHealth) {
        if (npc == null || !npc.isSpawned()) {
            return;
        }
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity living) || !living.isValid()) {
            return;
        }

        forceHideCitizensNameplate(npc, living);
        updateFloatingBar(living, currentHealth, maximumHealth);
    }

    private void applyOverheadBarEntity(LivingEntity living, double currentHealth, double maximumHealth) {
        if (living == null || !living.isValid()) {
            return;
        }

        living.setCustomNameVisible(false);
        updateFloatingBar(living, currentHealth, maximumHealth);
    }

    private void hideNpcEntityNameplate(NPC npc, LivingEntity activeCarrier) {
        if (npc == null || !npc.isSpawned()) {
            return;
        }
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity npcEntity) || npcEntity == activeCarrier) {
            return;
        }
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npcEntity.setCustomNameVisible(false);
    }

    private void forceHideCitizensNameplate(NPC npc, LivingEntity living) {
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        living.setCustomNameVisible(false);
    }

    private void updateFloatingBar(LivingEntity living, double currentHealth, double maximumHealth) {
        UUID hostId = living.getUniqueId();
        touchedThisTick.add(hostId);

        double max = Math.max(1.0D, maximumHealth);
        double current = Math.max(0.0D, Math.min(max, currentHealth));
        double progress = current / max;
        String barText = "§8" + buildSegmentBar(progress);

        ArmorStand bar = barEntities.get(hostId);
        if (bar == null || !bar.isValid()) {
            Location spawnAt = getBarLocation(living);
            if (spawnAt.getWorld() == null) {
                return;
            }
            bar = spawnAt.getWorld().spawn(spawnAt, ArmorStand.class, stand -> {
                stand.setInvisible(true);
                stand.setMarker(true);
                stand.setSmall(true);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setSilent(true);
                stand.setCollidable(false);
                stand.setCustomNameVisible(true);
                stand.setBasePlate(false);
                stand.setArms(false);
            });
            bar.getPersistentDataContainer().set(barOwnerKey, PersistentDataType.STRING, hostId.toString());
            barEntities.put(hostId, bar);
        }

        cleanupDuplicateBarsForHost(living, hostId, bar);
        bar.teleport(getBarLocation(living));
        bar.setCustomName(barText);
        bar.setCustomNameVisible(true);
    }

    private Location getBarLocation(LivingEntity living) {
        double yOffset = Math.max(0.9D, living.getHeight() + 0.55D);
        return living.getLocation().add(0.0D, yOffset, 0.0D);
    }

    private void cleanupUntouchedBars() {
        Set<UUID> staleIds = new HashSet<>(barEntities.keySet());
        staleIds.removeAll(touchedThisTick);
        for (UUID staleId : staleIds) {
            ArmorStand bar = barEntities.remove(staleId);
            if (bar != null) {
                bar.remove(); // safe even if entity is in an unloaded chunk (Paper 1.17+)
            }
        }
    }

    /**
     * Scans all loaded-chunk entities for health bars that are no longer tracked
     * in barEntities (e.g. bars whose ArmorStand survived a chunk unload/reload
     * after their tracking reference was dropped). Runs every ~10 seconds.
     */
    private void purgeOrphanedBars() {
        Set<UUID> tracked = new HashSet<>();
        for (ArmorStand bar : barEntities.values()) {
            if (bar != null) tracked.add(bar.getUniqueId());
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ArmorStand stand)) continue;
                if (tracked.contains(stand.getUniqueId())) continue;
                boolean hasTag = stand.getPersistentDataContainer().has(barOwnerKey, PersistentDataType.STRING);
                if (hasTag || isHealthBarByAppearance(stand)) {
                    stand.remove();
                }
            }
        }
    }

    private void clearAllBars() {
        for (ArmorStand bar : barEntities.values()) {
            if (bar != null && bar.isValid()) {
                bar.remove();
            }
        }
        barEntities.clear();
        touchedThisTick.clear();
    }

    /**
     * Explicitly removes the health bar for a given host entity UUID.
     * Call this from each NPC's {@code cleanup()} to prevent orphaned bars
     * when the NPC dies or is unregistered mid-cycle.
     *
     * @param entityId the {@link UUID} of the host entity whose bar should be removed
     */
    public void removeBar(UUID entityId) {
        if (entityId == null) {
            return;
        }
        ArmorStand bar = barEntities.remove(entityId);
        if (bar != null && bar.isValid()) {
            bar.remove();
        }
        touchedThisTick.remove(entityId);
    }

    private void cleanupDuplicateBarsForHost(LivingEntity host, UUID hostId, ArmorStand keep) {
        World world = host.getWorld();
        if (world == null) {
            return;
        }
        for (Entity nearby : world.getNearbyEntities(host.getLocation(), 3.0D, 3.0D, 3.0D)) {
            if (!(nearby instanceof ArmorStand stand) || stand == keep) {
                continue;
            }
            PersistentDataContainer pdc = stand.getPersistentDataContainer();
            if (!pdc.has(barOwnerKey, PersistentDataType.STRING)) {
                continue;
            }
            String owner = pdc.get(barOwnerKey, PersistentDataType.STRING);
            if (hostId.toString().equals(owner)) {
                stand.remove();
            }
        }
    }

    private void purgeAllManagedBars() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof ArmorStand stand)) continue;
                // Remove by PDC tag (current system)
                if (stand.getPersistentDataContainer().has(barOwnerKey, PersistentDataType.STRING)) {
                    stand.remove();
                    continue;
                }
                // Remove by visual fingerprint (legacy bars from old metadata system, or crash survivors)
                if (isHealthBarByAppearance(stand)) {
                    stand.remove();
                }
            }
        }
    }

    /**
     * Identifies orphaned health bar ArmorStands by their visual characteristics.
     * Used to clean up bars that lost their PDC tag (e.g. from old metadata-based builds).
     */
    private boolean isHealthBarByAppearance(ArmorStand stand) {
        if (!stand.isInvisible() || !stand.isMarker() || !stand.isSmall()) return false;
        if (stand.hasGravity() || !stand.isCustomNameVisible()) return false;
        String name = stand.getCustomName();
        return name != null && name.contains("§7[") && (name.contains("§c■") || name.contains("§7□"));
    }

    private String buildSegmentBar(double progress) {
        int segments = 10;
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * segments);
        StringBuilder builder = new StringBuilder("§7[");
        for (int i = 0; i < segments; i++) {
            builder.append(i < filled ? "§c■" : "§7□");
        }
        builder.append("§7]");
        return builder.toString();
    }

}


