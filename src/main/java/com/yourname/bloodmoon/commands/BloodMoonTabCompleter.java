package com.yourname.bloodmoon.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Full tab completion for /bloodmoon.
 */
public final class BloodMoonTabCompleter implements TabCompleter {

    private static final List<String> ROOT = List.of("start", "stop", "status", "spawn", "clear", "reload", "chance");
    private static final List<String> PLAYER_ROOT = List.of("healthbar");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("bloodmoon.admin")) {
                options.addAll(ROOT);
            }
            if (sender.hasPermission("bloodmoon.healthbar")) {
                options.addAll(PLAYER_ROOT);
            }
            return filter(options, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && "healthbar".equals(sub) && sender.hasPermission("bloodmoon.healthbar")) {
            return filter(List.of("on", "off", "toggle"), args[1]);
        }
        if (!sender.hasPermission("bloodmoon.admin")) {
            return List.of();
        }
        if (args.length == 2 && "start".equals(sub)) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[1]);
        }
        if (args.length == 2 && ("stop".equals(sub) || "clear".equals(sub))) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).toList(), args[1]);
        }
        if (args.length == 2 && "spawn".equals(sub)) {
            return filter(List.of("vampire", "clown", "zombie", "witch", "scarecrow", "ghost"), args[1]);
        }
        if (args.length == 3 && "spawn".equals(sub)
            && ("vampire".equals(args[1].toLowerCase()) || "clown".equals(args[1].toLowerCase()) || "zombie".equals(args[1].toLowerCase()) || "witch".equals(args[1].toLowerCase()) || "scarecrow".equals(args[1].toLowerCase()) || "ghost".equals(args[1].toLowerCase()))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 2 && "chance".equals(sub)) {
            return filter(List.of("1", "4", "8", "12", "25", "50", "100"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
