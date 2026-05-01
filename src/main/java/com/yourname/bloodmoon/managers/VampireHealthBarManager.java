package com.yourname.bloodmoon.managers;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.ClownNPC;
import com.yourname.bloodmoon.mobs.GhostNPC;
import com.yourname.bloodmoon.mobs.ScarecrowNPC;
import com.yourname.bloodmoon.mobs.VampireNPC;
import com.yourname.bloodmoon.mobs.WerewolfNPC;
import com.yourname.bloodmoon.mobs.WitchNPC;
import com.yourname.bloodmoon.mobs.ZombieNPC;
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
public final class VampireHealthBarManager {

    private final BloodMoonPlugin plugin;
    private BukkitRunnable updateTask;

    public VampireHealthBarManager(BloodMoonPlugin plugin) {
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
            applyOverheadBar(vampire.getNpc(), "Vampire", vampire.getCurrentHealth(), vampire.getMaximumHealth());
        }

        for (ClownNPC clown : plugin.getNPCManager().getActiveClowns()) {
            if (clown == null || clown.isDead()) {
                continue;
            }
            applyOverheadBar(clown.getNpc(), "Clown", clown.getCurrentHealth(), clown.getMaximumHealth());
        }

        for (ZombieNPC zombie : plugin.getNPCManager().getActiveZombies()) {
            if (zombie == null || zombie.isDead()) {
                continue;
            }
            applyOverheadBar(zombie.getNpc(), "Zombie", zombie.getCurrentHealth(), zombie.getMaximumHealth());
        }

        for (WitchNPC witch : plugin.getNPCManager().getActiveWitches()) {
            if (witch == null || witch.isDead()) {
                continue;
            }
            applyOverheadBar(witch.getNpc(), "Witch", witch.getCurrentHealth(), witch.getMaximumHealth());
        }

        for (ScarecrowNPC scarecrow : plugin.getNPCManager().getActiveScarecrows()) {
            if (scarecrow == null || scarecrow.isDead()) {
                continue;
            }
            applyOverheadBar(scarecrow.getNpc(), "Scarecrow", scarecrow.getCurrentHealth(), scarecrow.getMaximumHealth());
        }

        for (GhostNPC ghost : plugin.getNPCManager().getActiveGhosts()) {
            if (ghost == null || ghost.isDead()) {
                continue;
            }
            applyOverheadBar(ghost.getNpc(), "Ghost", ghost.getCurrentHealth(), ghost.getMaximumHealth());
        }

        for (WerewolfNPC werewolf : plugin.getNPCManager().getActiveWerewolves()) {
            if (werewolf == null || werewolf.isDead()) {
                continue;
            }
            applyOverheadBar(werewolf.getNpc(), "Werewolf", werewolf.getCurrentHealth(), werewolf.getMaximumHealth());
        }
    }

    private void applyOverheadBar(NPC npc, String label, double currentHealth, double maximumHealth) {
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
        String name = "§4" + label + " §8" + buildSegmentBar(progress) + " §7" + format(current) + "§8/§7" + format(max);

        npc.data().set("nameplate-visible", true);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);
        npc.setName(name);
        living.setCustomName(name);
        living.setCustomNameVisible(true);
        ensureNameTagVisible(living);
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

    private String format(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }
}
