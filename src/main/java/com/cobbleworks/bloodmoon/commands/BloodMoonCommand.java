package com.cobbleworks.bloodmoon.commands;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.utils.MessageUtils;
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
        if (args.length > 0 && "healthbar".equalsIgnoreCase(args[0])) {
            handleHealthBar(sender, args);
            return true;
        }

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
            case "stop" -> handleStop(sender, args);
            case "status" -> handleStatus(sender);
            case "spawn" -> handleSpawn(sender, args);
            case "clear" -> handleClear(sender, args);
            case "reload" -> handleReload(sender);
            case "chance" -> handleChance(sender, args);
            case "difficulty" -> handleDifficulty(sender, args);
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

    private void handleStop(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                MessageUtils.send(sender, "§cWorld not found.");
                return;
            }
            boolean ended = plugin.getBloodMoonManager().endBloodMoon(world, true);
            if (ended) {
                MessageUtils.send(sender, "§aStopped Blood Moon in §e" + world.getName() + "§a.");
            } else {
                MessageUtils.send(sender, "§eNo active Blood Moon in §e" + world.getName() + "§e.");
            }
        } else {
            int active = plugin.getBloodMoonManager().getActiveWorlds().size();
            plugin.getBloodMoonManager().forceEnd();
            MessageUtils.send(sender, "§aStopped all Blood Moon events and cleaned NPCs. §7(" + active + " world(s) ended)");
        }
    }

    private void handleStatus(CommandSender sender) {
        List<World> activeWorlds = plugin.getBloodMoonManager().getActiveWorlds();
        MessageUtils.send(sender, "§4BloodMoon status:");
        MessageUtils.send(sender, "§7Active worlds: §f" + (activeWorlds.isEmpty() ? "none" : activeWorlds.stream().map(World::getName).toList()));
        MessageUtils.send(sender, "§7Chance: §f1-in-" + plugin.getBloodMoonManager().getCurrentChance()
            + (plugin.getBloodMoonManager().hasChanceOverride() ? " §e(temporary override)" : ""));
        MessageUtils.send(sender, "§7Difficulty: §f" + plugin.getBloodMoonManager().getDifficultyToken()
            + " §8(health x" + formatMultiplier(plugin.getBloodMoonManager().getNonVampireHealthMultiplier())
            + ", rewards x" + formatMultiplier(plugin.getBloodMoonManager().getRewardMultiplier())
            + ", exp x" + formatMultiplier(plugin.getBloodMoonManager().getExpMultiplier()) + ")");
        MessageUtils.send(sender, "§7Active vampires: §f" + plugin.getNPCManager().getActiveVampires().size());
        MessageUtils.send(sender, "§7Active clowns: §f" + plugin.getNPCManager().getActiveClowns().size());
        MessageUtils.send(sender, "§7Active zombies: §f" + plugin.getNPCManager().getActiveZombies().size());
        MessageUtils.send(sender, "§7Active witches: §f" + plugin.getNPCManager().getActiveWitches().size());
        MessageUtils.send(sender, "§7Active scarecrows: §f" + plugin.getNPCManager().getActiveScarecrows().size());
        MessageUtils.send(sender, "§7Active ghosts: §f" + plugin.getNPCManager().getActiveGhosts().size());
        MessageUtils.send(sender, "§7Active werewolves: §f" + plugin.getNPCManager().getActiveWerewolves().size());
        MessageUtils.send(sender, "§7Tracked bats: §f" + plugin.getNPCManager().getActiveBatIds().size());
        MessageUtils.send(sender, "§7System mode: §fNPC-only enemies");
        for (World world : Bukkit.getWorlds()) {
            if (plugin.getBloodMoonManager().isConfiguredWorld(world) || plugin.getBloodMoonManager().isActive(world)) {
                MessageUtils.send(sender, "§8- §e" + world.getName() + "§7: "
                    + (plugin.getBloodMoonManager().isActive(world) ? "§cactive" : "§anot active")
                    + "§7, next roll " + plugin.getBloodMoonManager().describeNextWindow(world));
            }
        }
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (args.length < 3) {
            MessageUtils.send(sender, "§cUsage: /bloodmoon spawn <vampire|clown|zombie|witch|scarecrow|ghost|werewolf> <player>");
            return;
        }
        Player player = Bukkit.getPlayerExact(args[2]);
        if (player == null || !player.isOnline()) {
            MessageUtils.send(sender, "§cPlayer not found.");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "vampire" -> plugin.getNPCManager().spawnVampireNear(player).ifPresentOrElse(
                v -> MessageUtils.send(sender, "§aSpawned vampire near §e" + player.getName() + "§a."),
                () -> MessageUtils.send(sender, "§cCould not spawn. Citizens/Sentinel may not be ready."));
            case "clown" -> plugin.getNPCManager().spawnClownNear(player).ifPresentOrElse(
                c -> MessageUtils.send(sender, "§aSpawned clown near §e" + player.getName() + "§a."),
                () -> MessageUtils.send(sender, "§cCould not spawn clown here right now."));
            case "zombie" -> plugin.getNPCManager().spawnZombieNear(player).ifPresentOrElse(
                z -> MessageUtils.send(sender, "§aSpawned zombie near §e" + player.getName() + "§a."),
                () -> MessageUtils.send(sender, "§cCould not spawn zombie here right now."));
            case "witch" -> plugin.getNPCManager().spawnWitchNear(player).ifPresentOrElse(
                w -> MessageUtils.send(sender, "§aSpawned witch near §e" + player.getName() + "§a."),
                () -> MessageUtils.send(sender, "§cCould not spawn witch here right now."));
            case "scarecrow" -> plugin.getNPCManager().spawnScarecrowNear(player).ifPresentOrElse(
                s -> MessageUtils.send(sender, "§aSpawned scarecrow near §e" + player.getName() + "§a."),
                () -> MessageUtils.send(sender, "§cCould not spawn scarecrow here right now."));
            case "ghost" -> plugin.getNPCManager().spawnGhostNear(player).ifPresentOrElse(
                g -> MessageUtils.send(sender, "§aSpawned ghost near §e" + player.getName() + "§a."),
                () -> MessageUtils.send(sender, "§cCould not spawn ghost here right now."));
            case "werewolf" -> plugin.getNPCManager().spawnWerewolfNear(player).ifPresentOrElse(
                w -> MessageUtils.send(sender, "§aSpawned werewolf near §e" + player.getName() + "§a."),
                () -> MessageUtils.send(sender, "§cCould not spawn werewolf here right now."));
            default -> MessageUtils.send(sender, "§cUnknown type. Use: vampire, clown, zombie, witch, scarecrow, ghost, or werewolf.");
        }
    }

    private void handleClear(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                MessageUtils.send(sender, "§cWorld not found.");
                return;
            }
            if (plugin.getBloodMoonManager().isActive(world)) {
                plugin.getBloodMoonManager().endBloodMoon(world, true);
            } else {
                plugin.getNPCManager().cleanupWorld(world);
            }
            MessageUtils.send(sender, "§aCleared all Blood Moon enemies in §e" + world.getName() + "§a.");
        } else {
            if (!plugin.getBloodMoonManager().getActiveWorlds().isEmpty()) {
                plugin.getBloodMoonManager().forceEnd();
            } else {
                plugin.getNPCManager().cleanupAll();
            }
            MessageUtils.send(sender, "§aCleared all Blood Moon enemies across all worlds.");
        }
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

    private void handleDifficulty(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessageUtils.send(sender, "§7Current difficulty: §f" + plugin.getBloodMoonManager().getDifficultyToken());
            MessageUtils.send(sender, "§7Usage: /bloodmoon difficulty <easy|medium|hard|nightmare>");
            return;
        }
        String token = args[1].toLowerCase();
        boolean changed = plugin.getBloodMoonManager().setDifficulty(token);
        if (!changed) {
            MessageUtils.send(sender, "§cUnknown difficulty. Use: easy, medium, hard, nightmare.");
            return;
        }
        MessageUtils.send(sender, "§aBloodMoon difficulty set to §e" + plugin.getBloodMoonManager().getDifficultyToken() + "§a.");
        MessageUtils.send(sender, "§7Non-vampire health multiplier: §f" + formatMultiplier(plugin.getBloodMoonManager().getNonVampireHealthMultiplier()));
        MessageUtils.send(sender, "§7Reward multiplier: §f" + formatMultiplier(plugin.getBloodMoonManager().getRewardMultiplier()));
        MessageUtils.send(sender, "§7Exp multiplier: §f" + formatMultiplier(plugin.getBloodMoonManager().getExpMultiplier()));
    }

    private void handleHealthBar(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtils.send(sender, "§cOnly players can view special-mob overhead health bars.");
            return;
        }
        if (!sender.hasPermission("bloodmoon.healthbar")) {
            MessageUtils.send(sender, "§cYou do not have permission to use special-mob health bar features.");
            return;
        }
        MessageUtils.send(player, "§aSpecial-mob overhead health bars are active.");
        MessageUtils.send(player, "§7Each BloodMoon NPC now shows its segmented health above its head.");
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
        if (sender instanceof Player player && player.hasPermission("bloodmoon.healthbar")) {
            MessageUtils.send(sender, "§7/bloodmoon healthbar §8(view overhead-bar info)");
        }
        MessageUtils.send(sender, "§7/bloodmoon start [world]");
        MessageUtils.send(sender, "§7/bloodmoon stop [world]");
        MessageUtils.send(sender, "§7/bloodmoon status");
        MessageUtils.send(sender, "§7/bloodmoon spawn <vampire|clown|zombie|witch|scarecrow|ghost|werewolf> <player>");

        MessageUtils.send(sender, "§7/bloodmoon clear [world]");
        MessageUtils.send(sender, "§7/bloodmoon reload");
        MessageUtils.send(sender, "§7/bloodmoon chance <1-100>");
        MessageUtils.send(sender, "§7/bloodmoon difficulty <easy|medium|hard|nightmare>");
    }

    private String formatMultiplier(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}


