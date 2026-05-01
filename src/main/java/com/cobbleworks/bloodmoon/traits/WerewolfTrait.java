package com.cobbleworks.bloodmoon.traits;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.WerewolfNPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

@TraitName("bloodmoon_werewolf")
public final class WerewolfTrait extends Trait {

    private final BloodMoonPlugin plugin;
    private WerewolfNPC werewolf;

    public WerewolfTrait() {
        super("bloodmoon_werewolf");
        this.plugin = BloodMoonPlugin.getInstance();
    }

    public void bind(WerewolfNPC werewolf) {
        this.werewolf = werewolf;
    }

    public WerewolfNPC getWerewolf() {
        if (werewolf == null && plugin != null && npc != null) {
            werewolf = plugin.getNPCManager().getWerewolf(npc);
        }
        return werewolf;
    }

    public boolean isActiveWerewolf() {
        WerewolfNPC controller = getWerewolf();
        return controller != null && !controller.isDead();
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        WerewolfNPC controller = getWerewolf();
        if (controller != null) {
            controller.handleSentinelAttack(event);
        }
    }

    @Override
    public void run() {
        WerewolfNPC controller = getWerewolf();
        if (controller != null) {
            controller.onTraitTick();
        }
    }
}

