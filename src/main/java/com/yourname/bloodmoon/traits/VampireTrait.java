package com.yourname.bloodmoon.traits;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.mobs.VampireNPC;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;

/**
 * Citizens trait marker and tick bridge for BloodMoon vampires.
 */
@TraitName("bloodmoon_vampire")
public final class VampireTrait extends Trait {

    private BloodMoonPlugin plugin;
    private VampireNPC vampire;

    public VampireTrait() {
        super("bloodmoon_vampire");
        this.plugin = BloodMoonPlugin.getInstance();
    }

    /**
     * Binds the runtime controller.
     *
     * @param vampire vampire controller
     */
    public void bind(VampireNPC vampire) {
        this.vampire = vampire;
    }

    /**
     * Gets the runtime controller.
     *
     * @return vampire controller
     */
    public VampireNPC getVampire() {
        if (vampire == null && plugin != null && npc != null) {
            vampire = plugin.getNPCManager().getVampire(npc);
        }
        return vampire;
    }

    /**
     * Returns whether this trait has a live vampire controller.
     *
     * @return true if active
     */
    public boolean isActiveVampire() {
        VampireNPC controller = getVampire();
        return controller != null && !controller.isDead();
    }

    @Override
    public void run() {
        VampireNPC controller = getVampire();
        if (controller != null) {
            controller.onTraitTick();
        }
    }

    @Override
    public void onSpawn() {
        VampireNPC controller = getVampire();
        if (controller != null) {
            controller.onNpcSpawn();
        }
    }

    /**
     * Starts the vampire death sequence.
     */
    public void handleDeath() {
        VampireNPC controller = getVampire();
        if (controller != null) {
            controller.startDeathSequence();
        }
    }

    /**
     * Reduces damage when the blood shield is active.
     *
     * @param damage incoming damage
     * @return reduced damage
     */
    public double reduceIncomingDamage(double damage) {
        VampireNPC controller = getVampire();
        return controller == null ? damage : controller.reduceIncomingDamage(damage);
    }

    /**
     * Handles a Sentinel attack callback.
     *
     * @param event Sentinel attack event
     */
    public void handleSentinelAttack(SentinelAttackEvent event) {
        VampireNPC controller = getVampire();
        if (controller != null) {
            controller.handleSentinelAttack(event);
        }
    }
}
