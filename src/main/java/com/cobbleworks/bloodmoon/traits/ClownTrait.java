package com.cobbleworks.bloodmoon.traits;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ClownNPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

/**
 * Citizens trait marker and tick bridge for BloodMoon clown NPCs.
 */
@TraitName("bloodmoon_clown")
public final class ClownTrait extends Trait {

    private BloodMoonPlugin plugin;
    private ClownNPC clown;

    public ClownTrait() {
        super("bloodmoon_clown");
        this.plugin = BloodMoonPlugin.getInstance();
    }

    public void bind(ClownNPC clown) {
        this.clown = clown;
    }

    public ClownNPC getClown() {
        if (clown == null && plugin != null && npc != null) {
            clown = plugin.getNPCManager().getClown(npc);
        }
        return clown;
    }

    public boolean isActiveClown() {
        ClownNPC controller = getClown();
        return controller != null && !controller.isDead();
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        ClownNPC controller = getClown();
        if (controller != null) {
            controller.handleSentinelAttack(event);
        }
    }

    @Override
    public void run() {
        ClownNPC controller = getClown();
        if (controller != null) {
            controller.onTraitTick();
        }
    }
}


