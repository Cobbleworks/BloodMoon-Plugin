package com.yourname.bloodmoon.commands;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.utils.MessageUtils;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Executor for /bloodmoon administration.
 */
public final class BloodMoonCommand implements CommandExecutor {

    private final BloodMoonPlugin plugin;

    public BloodMoonCommand(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bloodmoon.admin")) {
            MessageUtils.send(sender, "§cYou do not have permission to use BloodMoon.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "start" -> handleStart(sender, args);
            case "stop" -> handleStop(sender);
            case "status" -> handleStatus(sender);
            case "spawn" -> handleSpawn(sender, args);
            case "reload" -> handleReload(sender);
            case "chance" -> handleChance(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleStart(CommandSender sender, String[] args) {
        World world = resolveWorld(sender, args.length >= 2 ? args[1] : null);
        if (world == null) {
            MessageUtils.send(sender, "§cWorld not found.");
            return;
        }
        world.setTime(13000L);
        boolean started = plugin.getBloodMoonManager().startBloodMoon(world, true);
        if (started) {
            MessageUtils.send(sender, "§aStarted a Blood Moon in §e" + world.getName() + "§a.");
        } else {
            MessageUtils.send(sender, "§eA Blood Moon is already active or could not start in that world.");
        }
    }

    private void handleStop(CommandSender sender) {
        int active = plugin.getBloodMoonManager().getActiveWorlds().size();
        plugin.getBloodMoonManager().forceEnd();
        MessageUtils.send(sender, "§aStopped Blood Moon events and cleaned NPCs. §7(Active worlds ended: " + active + ")");
    }

    private void handleStatus(CommandSender sender) {
        List<World> activeWorlds = plugin.getBloodMoonManager().getActiveWorlds();
        MessageUtils.send(sender, "§4BloodMoon status:");
        MessageUtils.send(sender, "§7Active worlds: §f" + (activeWorlds.isEmpty() ? "none" : activeWorlds.stream().map(World::getName).toList()));
        MessageUtils.send(sender, "§7Chance: §f1-in-" + plugin.getBloodMoonManager().getCurrentChance()
            + (plugin.getBloodMoonManager().hasChanceOverride() ? " §e(temporary override)" : ""));
        MessageUtils.send(sender, "§7Active vampires: §f" + plugin.getNPCManager().getActiveNpcIds().size());
        MessageUtils.send(sender, "§7Tracked bats: §f" + plugin.getNPCManager().getActiveBatIds().size());
        MessageUtils.send(sender, "§7Special monsters: §f" + plugin.getSpecialMonsterManager().getMonsterIds().size());
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getBloodMoonManager().isConfiguredWorld(world) || plugin.getBloodMoonManager().isActive(world)) {
                MessageUtils.send(sender, "§8- §e" + world.getName() + "§7: "
                    + (plugin.getBloodMoonManager().isActive(world) ? "§cactive" : "§anot active")
                    + "§7, next roll " + plugin.getBloodMoonManager().describeNextWindow(world));
            }
        }
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (args.length < 3 || !"vampire".equalsIgnoreCase(args[1])) {
            MessageUtils.send(sender, "§cUsage: /bloodmoon spawn vampire <player>");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null || !player.isOnline()) {
            MessageUtils.send(sender, "§cPlayer not found.");
            return;
        }
        plugin.getNPCManager().spawnVampireNear(player).ifPresentOrElse(
            vampire -> MessageUtils.send(sender, "§aSpawned vampire NPC §e" + vampire.getNpc().getId() + "§a near §e" + player.getName() + "§a."),
            () -> MessageUtils.send(sender, "§cCould not spawn a vampire. Citizens/Sentinel may not be ready.")
        );
    }

    private void handleReload(CommandSender sender) {
        plugin.getConfigManager().reload();
        plugin.getNPCManager().getActiveVampires().forEach(vampire -> vampire.refreshSentinelSettings());
        MessageUtils.send(sender, "§aBloodMoon config reloaded.");
    }

    private void handleChance(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtils.send(sender, "§cUsage: /bloodmoon chance <1-100>");
            return;
        }
        try {
            int chance = Integer.parseInt(args[1]);
            if (chance < 1 || chance > 100) {
                MessageUtils.send(sender, "§cChance must be between 1 and 100.");
                return;
            }
            plugin.getBloodMoonManager().setChanceOverride(chance);
            MessageUtils.send(sender, "§aTemporary Blood Moon chance set to §e1-in-" + chance + "§a.");
        } catch (NumberFormatException ex) {
            MessageUtils.send(sender, "§cChance must be a number from 1 to 100.");
        }
    }

    private World resolveWorld(CommandSender sender, String name) {
        if (name != null) {
            return Bukkit.getWorld(name);
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        List<String> configured = plugin.getConfigManager().getEnabledWorlds();
        if (!configured.isEmpty() && Bukkit.getWorld(configured.get(0)) != null) {
            return Bukkit.getWorld(configured.get(0));
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    private void sendUsage(CommandSender sender) {
        MessageUtils.send(sender, "§4BloodMoon commands:");
        MessageUtils.send(sender, "§7/bloodmoon start [world]");
        MessageUtils.send(sender, "§7/bloodmoon stop");
        MessageUtils.send(sender, "§7/bloodmoon status");
        MessageUtils.send(sender, "§7/bloodmoon spawn vampire <player>");
        MessageUtils.send(sender, "§7/bloodmoon reload");
        MessageUtils.send(sender, "§7/bloodmoon chance <1-100>");
    }
}
