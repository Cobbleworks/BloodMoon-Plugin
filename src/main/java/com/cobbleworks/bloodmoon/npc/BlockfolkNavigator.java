package com.cobbleworks.bloodmoon.npc;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Keeps moving entity targets up to date while Blockfolk supplies native pathfinding. */
public final class BlockfolkNavigator {
    private final BlockfolkNpc npc;
    private Entity entityTarget;
    private Location locationTarget;
    private float modifier = 1;
    BlockfolkNavigator(BlockfolkNpc npc) { this.npc = npc; }
    public BlockfolkNavigator setSpeedModifier(float modifier) { this.modifier = modifier; return this; }
    public void setTarget(Entity entity, boolean aggressive) { entityTarget = entity; locationTarget = null; tick(); }
    public void setTarget(Location location) { entityTarget = null; locationTarget = location.clone(); tick(); }
    public void cancelNavigation() { entityTarget = null; locationTarget = null; npc.call("stopNavigating", new Class<?>[0]); }
    void tick() {
        if (!npc.isSpawned()) return;
        if (entityTarget != null && (!entityTarget.isValid() || entityTarget.getWorld() != npc.getEntity().getWorld())) {
            cancelNavigation(); return;
        }
        Location target = entityTarget == null ? locationTarget : entityTarget.getLocation();
        if (target != null) npc.call("navigate", new Class<?>[]{Location.class, double.class}, target, 4.317 * Math.max(0.05, modifier));
    }
}
