package com.cobbleworks.bloodmoon.npc;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.player.PlayerTeleportEvent;

/** BloodMoon's combat configuration around a real Blockfolk transient NPC handle. */
public final class BlockfolkNpc {
    private final BlockfolkBridge bridge;
    private final int id;
    private final Object handle;
    private final BlockfolkNavigator navigator = new BlockfolkNavigator(this);
    private double health = 20;
    private double armor;
    private double chaseRange = 32;
    private boolean protectedNpc;
    private boolean destroyed;

    BlockfolkNpc(BlockfolkBridge bridge, int id, Object handle) { this.bridge = bridge; this.id = id; this.handle = handle; }
    public int getId() { return id; }
    public String getName() { return (String) call("getName", new Class<?>[0]); }
    public Entity getEntity() { return (Entity) call("getEntity", new Class<?>[0]); }
    public boolean isSpawned() { return !destroyed && (boolean) call("isSpawned", new Class<?>[0]); }
    public BlockfolkNavigator getNavigator() { return navigator; }
    public boolean spawn(Location location) {
        boolean spawned = (boolean) call("spawn", new Class<?>[]{Location.class}, location);
        if (spawned && getEntity() instanceof LivingEntity living) {
            var maxHealth = living.getAttribute(com.cobbleworks.bloodmoon.util.ServerAttributes.maxHealth());
            if (maxHealth != null) maxHealth.setBaseValue(health);
            living.setHealth(health);
            living.setInvulnerable(protectedNpc);
            living.setCustomNameVisible(false);
            bridge.index(this, living);
        }
        return spawned;
    }
    public void despawn() { navigator.cancelNavigation(); bridge.unindex(this); call("despawn", new Class<?>[0]); }
    public void destroy() { if (destroyed) return; despawn(); call("destroy", new Class<?>[0]); destroyed = true; bridge.remove(this); }
    public void teleport(Location location, PlayerTeleportEvent.TeleportCause cause) {
        navigator.cancelNavigation(); call("teleport", new Class<?>[]{Location.class}, location);
    }
    public void faceLocation(Location location) { call("lookAt", new Class<?>[]{Location.class}, location); }
    public void setProtected(boolean value) { protectedNpc = value; if (getEntity() instanceof LivingEntity living) living.setInvulnerable(value); }
    public void setSkin(String name, String texture, String signature) {
        call("setSkin", new Class<?>[]{String.class, String.class, String.class}, name, texture, signature);
    }
    public void animate(String animation) { call("animate", new Class<?>[]{String.class}, animation); }
    public void configureCombat(double health, double armor, double chaseRange) {
        this.health = health; this.armor = armor; this.chaseRange = chaseRange;
        if (getEntity() instanceof LivingEntity living) {
            var attribute = living.getAttribute(com.cobbleworks.bloodmoon.util.ServerAttributes.maxHealth());
            if (attribute != null) attribute.setBaseValue(health);
            living.setHealth(Math.min(health, living.getHealth()));
        }
    }
    public double reduceArmorDamage(double damage) { return damage * (1.0 - armor); }
    public void setChaseRange(double value) { chaseRange = value; }
    public double getChaseRange() { return chaseRange; }
    void tick() { if (!destroyed) navigator.tick(); }
    Object call(String name, Class<?>[] parameters, Object... args) { return BlockfolkBridge.call(handle, name, parameters, args); }
}
