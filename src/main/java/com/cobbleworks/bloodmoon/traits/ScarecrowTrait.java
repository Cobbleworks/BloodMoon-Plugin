package com.cobbleworks.bloodmoon.traits;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ScarecrowNPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

/**
 * Citizens trait marker and tick bridge for BloodMoon scarecrow NPCs.
 */
@TraitName("bloodmoon_scarecrow")
public final class ScarecrowTrait extends Trait {

    private BloodMoonPlugin plugin;
    private ScarecrowNPC scarecrow;

    public ScarecrowTrait() {
        super("bloodmoon_scarecrow");
        this.plugin = BloodMoonPlugin.getInstance();
    }

    public void bind(ScarecrowNPC scarecrow) {
        this.scarecrow = scarecrow;
    }

    public ScarecrowNPC getScarecrow() {
        if (scarecrow == null && plugin != null && npc != null) {
            scarecrow = plugin.getNPCManager().getScarecrow(npc);
        }
        return scarecrow;
    }

    public boolean isActiveScarecrow() {
        ScarecrowNPC controller = getScarecrow();
        return controller != null && !controller.isDead();
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        ScarecrowNPC controller = getScarecrow();
        if (controller != null) {
            controller.handleSentinelAttack(event);
        }
    }

    @Override
    public void run() {
        ScarecrowNPC controller = getScarecrow();
        if (controller != null) {
            controller.onTraitTick();
        }
    }
}


