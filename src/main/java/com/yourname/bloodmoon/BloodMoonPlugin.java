package com.yourname.bloodmoon;

import com.yourname.bloodmoon.commands.BloodMoonCommand;
import com.yourname.bloodmoon.commands.BloodMoonTabCompleter;
import com.yourname.bloodmoon.effects.BleedEffect;
import com.yourname.bloodmoon.listeners.BloodMoonListener;
import com.yourname.bloodmoon.listeners.NPCListener;
import com.yourname.bloodmoon.listeners.PlayerListener;
import com.yourname.bloodmoon.managers.BloodMoonManager;
import com.yourname.bloodmoon.managers.NPCManager;
import com.yourname.bloodmoon.managers.VampireHealthBarManager;
import com.yourname.bloodmoon.utils.ConfigManager;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin entry point for BloodMoon.
 */
public final class BloodMoonPlugin extends JavaPlugin {

    private static BloodMoonPlugin instance;

    private ConfigManager configManager;
    private BloodMoonManager bloodMoonManager;
    private NPCManager npcManager;
    private BleedEffect bleedEffect;
    private VampireHealthBarManager vampireHealthBarManager;

    /**
     * Gets the active plugin instance.
     *
     * @return plugin instance
     */
    public static BloodMoonPlugin getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        if (!hasRequiredDependencies()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        configManager = new ConfigManager(this);
        configManager.load();

        bleedEffect = new BleedEffect(this);
        npcManager = new NPCManager(this);
        bloodMoonManager = new BloodMoonManager(this);
        vampireHealthBarManager = new VampireHealthBarManager(this);

        registerListeners();
        registerCommand();

        bloodMoonManager.start();
        vampireHealthBarManager.start();
        getServer().getScheduler().runTask(this, () -> npcManager.initializeCitizens());
        getLogger().info("BloodMoon Event enabled.");
    }

    @Override
    public void onDisable() {
        if (bloodMoonManager != null) {
            bloodMoonManager.forceEnd();
            bloodMoonManager.stop();
        }
        if (bleedEffect != null) {
            bleedEffect.cancelAll();
        }
        if (vampireHealthBarManager != null) {
            vampireHealthBarManager.stop();
        }
        if (npcManager != null) {
            npcManager.cleanupAll();
        }
        getServer().getScheduler().cancelTasks(this);
        getLogger().info("BloodMoon Event disabled.");
        instance = null;
    }

    /**
     * Gets the configuration manager.
     *
     * @return config manager
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Gets the event lifecycle manager.
     *
     * @return blood moon manager
     */
    public BloodMoonManager getBloodMoonManager() {
        return bloodMoonManager;
    }

    /**
     * Gets the Citizens NPC manager.
     *
     * @return NPC manager
     */
    public NPCManager getNPCManager() {
        return npcManager;
    }

    /**
     * Gets the bleed effect tracker.
     *
     * @return bleed effect tracker
     */
    public BleedEffect getBleedEffect() {
        return bleedEffect;
    }

    public VampireHealthBarManager getVampireHealthBarManager() {
        return vampireHealthBarManager;
    }

    private boolean hasRequiredDependencies() {
        PluginManager pluginManager = getServer().getPluginManager();
        if (!pluginManager.isPluginEnabled("Citizens")) {
            getLogger().severe("Citizens is required for BloodMoon Event.");
            return false;
        }
        if (!pluginManager.isPluginEnabled("Sentinel")) {
            getLogger().severe("Sentinel is required for BloodMoon Event.");
            return false;
        }
        return true;
    }

    private void registerListeners() {
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new BloodMoonListener(this), this);
        pluginManager.registerEvents(new PlayerListener(this), this);
        pluginManager.registerEvents(new NPCListener(this), this);
    }

    private void registerCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("bloodmoon"), "bloodmoon command missing from plugin.yml");
        command.setExecutor(new BloodMoonCommand(this));
        command.setTabCompleter(new BloodMoonTabCompleter());
    }
}
