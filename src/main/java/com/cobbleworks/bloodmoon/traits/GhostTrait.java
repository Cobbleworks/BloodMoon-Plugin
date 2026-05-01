package com.cobbleworks.bloodmoon.traits;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.GhostNPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

@TraitName("bloodmoon_ghost")
public final class GhostTrait extends Trait {

    private BloodMoonPlugin plugin;
    private GhostNPC ghost;

    public GhostTrait() {
        super("bloodmoon_ghost");
        this.plugin = BloodMoonPlugin.getInstance();
    }

    public void bind(GhostNPC ghost) {
        this.ghost = ghost;
    }

    public GhostNPC getGhost() {
        if (ghost == null && plugin != null && npc != null) {
            ghost = plugin.getNPCManager().getGhost(npc);
        }
        return ghost;
    }

    public boolean isActiveGhost() {
        GhostNPC controller = getGhost();
        return controller != null && !controller.isDead();
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        GhostNPC controller = getGhost();
        if (controller != null) {
            controller.handleSentinelAttack(event);
        }
    }

    @Override
    public void run() {
        GhostNPC controller = getGhost();
        if (controller != null) {
            controller.onTraitTick();
        }
    }
}


