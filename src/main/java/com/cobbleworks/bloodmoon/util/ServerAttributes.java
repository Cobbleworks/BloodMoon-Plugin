package com.cobbleworks.bloodmoon.util;

import org.bukkit.attribute.Attribute;

/**
 * Resolves attribute names across Bukkit's 1.21.9 registry-name migration.
 */
public final class ServerAttributes {
    private static final Attribute MAX_HEALTH = resolve("MAX_HEALTH", "GENERIC_MAX_HEALTH");
    private static final Attribute MOVEMENT_SPEED = resolve("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED");
    private static final Attribute ATTACK_DAMAGE = resolve("ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE");
    private static final Attribute KNOCKBACK_RESISTANCE = resolve(
            "KNOCKBACK_RESISTANCE", "GENERIC_KNOCKBACK_RESISTANCE");

    private ServerAttributes() {
    }

    public static Attribute maxHealth() {
        return MAX_HEALTH;
    }

    public static Attribute movementSpeed() {
        return MOVEMENT_SPEED;
    }

    public static Attribute attackDamage() {
        return ATTACK_DAMAGE;
    }

    public static Attribute knockbackResistance() {
        return KNOCKBACK_RESISTANCE;
    }

    private static Attribute resolve(String currentName, String legacyName) {
        try {
            return Attribute.valueOf(currentName);
        } catch (IllegalArgumentException ignored) {
            return Attribute.valueOf(legacyName);
        }
    }
}
