package com.cobbleworks.bloodmoon.traits;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.WitchNPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

/**
 * Citizens trait marker and tick bridge for BloodMoon witch NPCs.
 */
@TraitName("bloodmoon_witch")
public final class WitchTrait extends Trait {

    private BloodMoonPlugin plugin;
    private WitchNPC witch;

    public WitchTrait() {
        super("bloodmoon_witch");
        this.plugin = BloodMoonPlugin.getInstance();
    }

    public void bind(WitchNPC witch) {
        this.witch = witch;
    }

    public WitchNPC getWitch() {
        if (witch == null && plugin != null && npc != null) {
            witch = plugin.getNPCManager().getWitch(npc);
        }
        return witch;
    }

    public boolean isActiveWitch() {
        WitchNPC controller = getWitch();
        return controller != null && !controller.isDead();
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        WitchNPC controller = getWitch();
        if (controller != null) {
            controller.handleSentinelAttack(event);
        }
    }

    @Override
    public void run() {
        WitchNPC controller = getWitch();
        if (controller != null) {
            controller.onTraitTick();
        }
    }
}


