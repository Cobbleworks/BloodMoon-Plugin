package com.cobbleworks.bloodmoon.managers;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ClownNPC;
import com.cobbleworks.bloodmoon.mobs.GhostNPC;
import com.cobbleworks.bloodmoon.mobs.ScarecrowNPC;
import com.cobbleworks.bloodmoon.mobs.VampireNPC;
import com.cobbleworks.bloodmoon.mobs.WerewolfNPC;
import com.cobbleworks.bloodmoon.mobs.WitchNPC;
import com.cobbleworks.bloodmoon.mobs.ZombieNPC;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Renders overhead segmented health bars directly above active Blood Moon NPCs.
 */
public final class OverheadHealthBarManager {

    private final BloodMoonPlugin plugin;
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
    }

    private void applyOverheadBar(NPC npc, double currentHealth, double maximumHealth) {
        if (npc == null || !npc.isSpawned()) {
            return;
        }
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity living) || !living.isValid()) {
            return;
        }

        double max = Math.max(1.0D, maximumHealth);
        double current = Math.max(0.0D, Math.min(max, currentHealth));
        double progress = current / max;
        String name = "§8" + buildSegmentBar(progress);

        npc.data().set("nameplate-visible", true);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);
        npc.setName(name);
        living.setCustomName(name);
        living.setCustomNameVisible(true);
        ensureNameTagVisible(living);
    }

    private void applyOverheadBarEntity(LivingEntity living, double currentHealth, double maximumHealth) {
        if (living == null || !living.isValid()) {
            return;
        }

        double max = Math.max(1.0D, maximumHealth);
        double current = Math.max(0.0D, Math.min(max, currentHealth));
        double progress = current / max;
        String name = "§8" + buildSegmentBar(progress);

        living.setCustomName(name);
        living.setCustomNameVisible(true);
        ensureNameTagVisible(living);
    }

    private void hideNpcEntityNameplate(NPC npc, LivingEntity activeCarrier) {
        if (npc == null || !npc.isSpawned()) {
            return;
        }
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity npcEntity) || npcEntity == activeCarrier) {
            return;
        }
        npcEntity.setCustomNameVisible(false);
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

    private void ensureNameTagVisible(LivingEntity entity) {
        if (!(entity instanceof Player playerEntity)) {
            return;
        }
        try {
            Scoreboard board = Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
            if (board == null) {
                return;
            }
            Team hidden = board.getTeam("bm_hidden_npc");
            if (hidden != null) {
                hidden.removeEntry(playerEntity.getName());
            }
        } catch (Exception ignored) {
        }
    }

}


