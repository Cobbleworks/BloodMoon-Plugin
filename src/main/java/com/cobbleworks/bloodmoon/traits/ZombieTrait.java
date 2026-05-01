package com.cobbleworks.bloodmoon.traits;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import com.cobbleworks.bloodmoon.mobs.ZombieNPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

/**
 * Citizens trait marker and tick bridge for BloodMoon zombie NPCs.
 */
@TraitName("bloodmoon_zombie")
public final class ZombieTrait extends Trait {

    private BloodMoonPlugin plugin;
    private ZombieNPC zombie;

    public ZombieTrait() {
        super("bloodmoon_zombie");
        this.plugin = BloodMoonPlugin.getInstance();
    }

    public void bind(ZombieNPC zombie) {
        this.zombie = zombie;
    }

    public ZombieNPC getZombie() {
        if (zombie == null && plugin != null && npc != null) {
            zombie = plugin.getNPCManager().getZombie(npc);
        }
        return zombie;
    }

    public boolean isActiveZombie() {
        ZombieNPC controller = getZombie();
        return controller != null && !controller.isDead();
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        ZombieNPC controller = getZombie();
        if (controller != null) {
            controller.handleSentinelAttack(event);
        }
    }

    @Override
    public void run() {
        ZombieNPC controller = getZombie();
        if (controller != null) {
            controller.onTraitTick();
        }
    }
}


