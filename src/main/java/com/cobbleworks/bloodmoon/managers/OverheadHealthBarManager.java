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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Renders overhead segmented health bars directly above active Blood Moon NPCs.
 */
public final class OverheadHealthBarManager {

    private final BloodMoonPlugin plugin;
    private final Map<UUID, ArmorStand> barEntities = new HashMap<>();
    private final Set<UUID> touchedThisTick = new HashSet<>();
    private BukkitRunnable updateTask;

    public OverheadHealthBarManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
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
            barEntities.put(hostId, bar);
        }

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
            if (bar != null && bar.isValid()) {
                bar.remove();
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

    private String buildSegmentBar(double progress) {
        int segments = 10;
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, progress)) * segments);
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < segments; i++) {
            builder.append(i < filled ? "§c■" : "§8□");
        }
        builder.append("§7]");
        return builder.toString();
    }

}


