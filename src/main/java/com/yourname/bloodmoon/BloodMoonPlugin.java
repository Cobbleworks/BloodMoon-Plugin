package com.yourname.bloodmoon;

import com.yourname.bloodmoon.commands.BloodMoonCommand;
import com.yourname.bloodmoon.commands.BloodMoonTabCompleter;
import com.yourname.bloodmoon.effects.BleedEffect;
import com.yourname.bloodmoon.listeners.BloodMoonListener;
import com.yourname.bloodmoon.listeners.NPCListener;
import com.yourname.bloodmoon.listeners.PlayerListener;
import com.yourname.bloodmoon.listeners.SpecialMonsterListener;
import com.yourname.bloodmoon.managers.BloodMoonManager;
import com.yourname.bloodmoon.managers.MobSpawnManager;
import com.yourname.bloodmoon.managers.NPCManager;
import com.yourname.bloodmoon.managers.SpecialMonsterManager;
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
    private MobSpawnManager mobSpawnManager;
    private NPCManager npcManager;
    private SpecialMonsterManager specialMonsterManager;
    private BleedEffect bleedEffect;

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
        mobSpawnManager = new MobSpawnManager(this);
        specialMonsterManager = new SpecialMonsterManager(this);
        bloodMoonManager = new BloodMoonManager(this);

        registerListeners();
        registerCommand();

        bloodMoonManager.start();
        getServer().getScheduler().runTask(this, () -> npcManager.initializeCitizens());
        getLogger().info("BloodMoon Event enabled.");
    }

    @Override
    public void onDisable() {
        if (bloodMoonManager != null) {
            bloodMoonManager.forceEnd();
            bloodMoonManager.stop();
        }
        if (mobSpawnManager != null) {
            mobSpawnManager.stop();
        }
        if (bleedEffect != null) {
            bleedEffect.cancelAll();
        }
        if (npcManager != null) {
            npcManager.cleanupAll();
        }
        if (specialMonsterManager != null) {
            specialMonsterManager.cleanupAll();
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
     * Gets the vanilla mob spawn manager.
     *
     * @return mob spawn manager
     */
    public MobSpawnManager getMobSpawnManager() {
        return mobSpawnManager;
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
     * Gets the special monster manager.
     *
     * @return special monster manager
     */
    public SpecialMonsterManager getSpecialMonsterManager() {
        return specialMonsterManager;
    }

    /**
     * Gets the bleed effect tracker.
     *
     * @return bleed effect tracker
     */
    public BleedEffect getBleedEffect() {
        return bleedEffect;
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
        pluginManager.registerEvents(new SpecialMonsterListener(this), this);
    }

    private void registerCommand() {
        PluginCommand command = Objects.requireNonNull(getCommand("bloodmoon"), "bloodmoon command missing from plugin.yml");
        command.setExecutor(new BloodMoonCommand(this));
        command.setTabCompleter(new BloodMoonTabCompleter());
    }
}
