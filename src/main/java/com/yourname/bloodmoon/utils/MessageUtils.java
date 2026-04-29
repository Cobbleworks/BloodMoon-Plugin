package com.yourname.bloodmoon.utils;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Common message, title, action-bar, and sound helpers.
 */
public final class MessageUtils {

    private MessageUtils() {
    }

    public static String color(String message) {
        if (message == null) {
            return "";
        }
        return message.replace('&', '§');
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(color(message));
    }

    public static void broadcast(String message) {
        Bukkit.broadcastMessage(color(message));
    }

    public static void broadcastToWorld(World world, String message) {
        String colored = color(message);
        for (Player player : world.getPlayers()) {
            player.sendMessage(colored);
        }
    }

    public static void title(World world, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        String coloredTitle = color(title);
        String coloredSubtitle = color(subtitle);
        for (Player player : world.getPlayers()) {
            player.sendTitle(coloredTitle, coloredSubtitle, fadeIn, stay, fadeOut);
        }
    }

    public static void actionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(color(message)));
    }

    public static void playWorldSound(World world, Sound sound, float volume, float pitch) {
        for (Player player : world.getPlayers()) {
            player.playSound(player.getLocation(), sound, volume, pitch);
        }
    }

    public static void playNearby(Location location, Sound sound, float volume, float pitch, double radius) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        double radiusSquared = radius * radius;
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                player.playSound(location, sound, volume, pitch);
            }
        }
    }
}
