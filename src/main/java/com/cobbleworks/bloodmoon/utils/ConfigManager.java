package com.cobbleworks.bloodmoon.utils;

import com.cobbleworks.bloodmoon.BloodMoonPlugin;
import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Owns typed access to BloodMoon configuration values.
 */
public final class ConfigManager {

    private final BloodMoonPlugin plugin;

    public ConfigManager(BloodMoonPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration config = plugin.getConfig();
        config.options().copyDefaults(true);
        addDefaults(config);
        plugin.saveDefaultConfig();
        plugin.saveConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        addDefaults(plugin.getConfig());
        plugin.saveConfig();
    }

    public FileConfiguration getConfig() {
        return plugin.getConfig();
    }

    public int getBloodMoonChance() {
        return Math.max(1, getConfig().getInt("bloodmoon.chance", 24));
    }

    public double getSpecialMobReplaceChance() {
        return Math.max(0.0D, Math.min(1.0D, getConfig().getDouble("bloodmoon.special-mob-replace-chance", 0.08D)));
    }

    public List<String> getEnabledWorlds() {
        return getConfig().getStringList("bloodmoon.worlds");
    }

    public double getVampireHealth() {
        return getConfig().getDouble("vampire.health", 40.0D);
    }

    public int getStalkTicksMin() {
        return Math.max(1, getConfig().getInt("vampire.stalk-ticks-min", 80));
    }

    public int getStalkTicksMax() {
        return Math.max(getStalkTicksMin(), getConfig().getInt("vampire.stalk-ticks-max", 140));
    }

    public int getVampireSpawnRadius() {
        return Math.max(8, getConfig().getInt("vampire.spawn-radius", 48));
    }

    public int getMaxVampiresPerPlayer() {
        return Math.max(0, getConfig().getInt("vampire.max-per-player", 1));
    }

    public String getVampireSkinName() {
        return getConfig().getString("vampire.skin-name", "bloodmoon_vampire_150ea3e3982a1bd3");
    }

    public String getVampireSkinTexture() {
        return getConfig().getString("vampire.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc2NTIzMzk2OTc2NiwKICAicHJvZmlsZUlkIiA6ICIwNWU0OTczZTk5ZDk0NGY2YjczNjY3ZTEzMzdiY2IzOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lX1NraW4iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjk4YTliNzdlOWNmM2MxYjE1ZmMzNTlhN2FjZmMxZjYyMWYyYTc1OWVmNzZmYmI5N2I0Nzg3NTQ3MWZkMzI1ZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
    }

    public String getVampireSkinSignature() {
        return getConfig().getString("vampire.skin-signature",
            "wYc8PrLfnZyMfsRSHyTBP/CeBQRwzDG66MSQVlsUnghqwxvJdF47pwWlNPcI7yhU1+/Vwi+OYCVF/Ur7Pf+PP/Lx1U9z4x2jm0T50os8MzPh5VbRP54n82Qd727p9iSVJE9YUTrAG+nbqJ5joy0O5JLP9fBjZNA9RLsj6fFwnYccTc4zIV2QVamj0MNuYHihNHYiBh4lWTR8pLi+WH+/qS0Ll5And0QraSLLQdKdpsVl1IPJjghjGmqaRtUlQ+KaOhQBzcYnxxR7Dq4/IEOrkJUyq1/y/BmVuPpQldk9l6iQ8YVBEjJNHd9Ji9vkeplEJw+XxtWkh+yTbDz6XeszGUyIZ5CbY28VIDJYMmRWLMfgg37eK46xTrKzk+N9Yw2/qOrJtGHQ4n2cbbi76OAGv92eGsOWbR7Dw36zVBRHuUdn6+YqKChW3sqL3ES8JqA3KSwgs76GQMkDK/WlxR0LdE/ekaTLRYxKcBxbtverWhqCWt4Mt0zGraPfdNyQs9fNE6dqs2zHh0WCY0bPZ9gdcwBnzEQdgFMkNPGtVdFsCdU64hUzA/BgFGPLL4cWSss5IDNgJ3o+VxXS8u3wANIemvZIhHozEt04uNla7X6/VWhdm6D/k8PC1F8IhAwD8byY1wkYx2Q4ocgdVIGT4pq1gEWWtJahQFPfyzHHvP512H0=");
    }

    public String getVampireSkinUrl() {
        return getConfig().getString("vampire.skin-url", "");
    }

    public double getClownHealth() {
        return Math.max(1.0D, getConfig().getDouble("clown.health", 32.0D) * getNonVampireHealthMultiplier());
    }

    public int getClownMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("clown.max-per-player", 1));
    }

    public int getClownSpawnRadius() {
        return Math.max(8, getConfig().getInt("clown.spawn-radius", 48));
    }

    public double getClownSnapRadius() {
        return Math.max(1.0D, getConfig().getDouble("clown.snap-radius", 6.0D));
    }

    public double getClownManicHpThreshold() {
        return Math.max(0.05D, Math.min(0.95D, getConfig().getDouble("clown.manic-hp-threshold", 0.4D)));
    }

    public double getClownManicCooldownMultiplier() {
        return Math.max(0.1D, Math.min(1.0D, getConfig().getDouble("clown.manic-cooldown-multiplier", 0.5D)));
    }

    public int getClownBalloonCap() {
        return Math.max(1, getConfig().getInt("clown.balloon-cap", 6));
    }

    public int getClownTwistedTeleportHops() {
        return Math.max(1, getConfig().getInt("clown.twisted-teleport-hops", 3));
    }

    public int getClownTwistedTeleportIntervalTicks() {
        return Math.max(10, getConfig().getInt("clown.twisted-teleport-interval-ticks", 40));
    }

    public String getClownSkinName() {
        return getConfig().getString("clown.skin-name", "bloodmoon_clown_tdexterious");
    }

    public String getClownSkinTexture() {
        return getConfig().getString("clown.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzU1NzM5MDEwMSwKICAicHJvZmlsZUlkIiA6ICJjMmYxYWZkZmU3NGU0Zjk4YmFmN2MxNWI2YjVlYzFhYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJ0ZGV4dGVyaW91cyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS84Njg2YjFlNGRiM2VlZTEzZTMwMmIwMzY2YWIxOTg2ODcwZThiNTcxMTJhYzI2YmMwOWY5NDVkNDc5YzAzMTkyIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=");
    }

    public String getClownSkinSignature() {
        return getConfig().getString("clown.skin-signature",
            "AqNWXbfduDlOYFxlql5nRs6JxVttd3URTQmjHPvc6N73XNHNWYfc4SaTZ6vWwzp2JHgutcXGrSLAg+U3YDIf4TGSlXMWxLbxR7yL57ca4xo1Vhkvfzkr0j1770TEAAYm23exnkw/g7CaRWU/Y3VN0c3xAG5wmWgXSs+wunLqF0rnKyCeuW//JYSWNDKPRghlOQltxpQ4OJAvT3/3HDIPXgigx1UP6bMJx4YkbcZ1nNlB0Ka06LxXfR7xp21ZKvGYi8XHW35VEpofGhwXfeDIj8TdX41bRVTlFiXuL7FIauHmhrhPS5FqPi97AahTGOvuRvK2BiyGbkLZt5dBB4KRizhz8c5b4LBc/U+sJs/f0FAF84NF+C/hHVTspNY5hj1o7xyyqJKzh4jtzutxyRbrGIkG9MR44TsO6qNPYV2szkEGPWMu8GxC2yw6czPy8Q1EnPvqL3goiTek6isWeba3DDtfkKfKQ7YcFwN/VMgN/0Xr2H/Jlbc3U7U3i2G3EfWB4mfNcq4xdR2GS8gvO0DpFrMNQATitZqWE+W0X430Lr0Ojji6KtaiFwBI3WjL2fwxzwrxbK1UJNPU3Qi28c/+Iz6z0tXCRTjqhNF5vDxBVWfvyysQTkS/yNh2d46/bm/ERqdA2BfXDDqiHuJOLqyhOnoRcBzfa/02aLx4sEeZkIU=");
    }

    public double getZombieHealth() {
        return Math.max(1.0D, getConfig().getDouble("zombie.health", 50.0D) * getNonVampireHealthMultiplier());
    }

    public int getZombieMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("zombie.max-per-player", 1));
    }

    public int getZombieSpawnRadius() {
        return Math.max(8, getConfig().getInt("zombie.spawn-radius", 48));
    }

    public int getZombieInfectionTickInterval() {
        return Math.max(1, getConfig().getInt("zombie.infection-tick-interval", 60));
    }

    public double getZombieInfectionDamage() {
        return Math.max(0.0D, getConfig().getDouble("zombie.infection-damage", 0.5D));
    }

    public int getZombieInfectionDurationTicks() {
        return Math.max(20, getConfig().getInt("zombie.infection-duration-ticks", 400));
    }

    public double getZombieInfectionJumpRadius() {
        return Math.max(1.0D, getConfig().getDouble("zombie.infection-jump-radius", 4.0D));
    }

    public double getZombieHordeCallRadius() {
        return Math.max(1.0D, getConfig().getDouble("zombie.horde-call-radius", 20.0D));
    }

    public double getZombieRisenHpFraction() {
        return Math.max(0.05D, Math.min(0.95D, getConfig().getDouble("zombie.risen-hp-fraction", 0.4D)));
    }

    public double getZombiePlagueBurstRadius() {
        return Math.max(1.0D, getConfig().getDouble("zombie.plague-burst-radius", 6.0D));
    }

    public String getZombieSkinName() {
        return getConfig().getString("zombie.skin-name", "bloodmoon_zombie_977d6645fa698789");
    }

    public String getZombieSkinTexture() {
        return getConfig().getString("zombie.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTcyOTM0MTA2NTk5NSwKICAicHJvZmlsZUlkIiA6ICJhMWQzNzljMjhhODA0ZTMxYjY3YjcyMDcyNWE0ZjI3NSIsCiAgInByb2ZpbGVOYW1lIiA6ICJDeXNwZWN0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzY4Y2QxYmE3OGU5OTFkYmM4MWNiMDIyOTYyYzA1MzVmYTg5MjEyNzRmMGYzZTJjNDI1NzcxOWE5YWE2MDNiOWUiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==");
    }

    public String getZombieSkinSignature() {
        return getConfig().getString("zombie.skin-signature",
            "Y9cLhQ0f2QwfqjsjCKHHAAWiPtwhVoitGTSiy6zXe9C1aM38fLWin08dD5taRvPmfW+dVDTO9Udt/IXYY9XTZMwvrWDqZ17/vwlncwEFXQg/V9ax5mHQySUWafE/7E7VR7XPBV7QDVIxRZzO8gSzcHQyuo+ONyuz16E+cnaJNKi0Aotw+jsfTMhLUQ1JnjbCwG1mf+WTelNB3JxbykBQ062cp4dEQWfDHCn6bZiWzOwh/ldfYFIY/xVldOYhmNpoHH3BPvjxiEROezMMHY4R/jK2yqP82wWIra73xu6JWSQTFsefImcoTxcBhkrUz7pNu+oZwI7FAZwJpy+EvYBajmSxCGRopvu2xfpjp4GsBrZ4/HmSEZPcGl2AM2b3ITIz2rsaaBs2aWeKsUbXmrefKQXRgy/fAp8mvm5nlevLq3sv3+/auBC2MUmwC+9/fBoP8UpuYTDnrz9Q4Sqt00K4KxwmkwdfTH5vsNwxOpbhHDfNLDAb4ayJoSykTNEF6dwRqZsu9AiayMA7YoKoWUCeNKcVz+4OCKE2RfNK+v1lMgh5l9xeFy+bQfBrwv5pwkxqXg8xVoW6YcUS5avlmpvXNw/KV3cBmyf9JCViCI9t9oK5FqmpLsWodpKcoTOMKhibBE+CK81jPLj3JoXLOEJwB1lPWKFl7UQ13fjQvbDl3yc=");
    }

    public double getWitchHealth() {
        return Math.max(1.0D, getConfig().getDouble("witch.health", 40.0D) * getNonVampireHealthMultiplier());
    }

    public int getWitchMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("witch.max-per-player", 1));
    }

    public int getWitchSpawnRadius() {
        return Math.max(8, getConfig().getInt("witch.spawn-radius", 48));
    }

    public String getWitchSkinName() {
        return getConfig().getString("witch.skin-name", "bloodmoon_witch_9b19c47ec1b068a5");
    }

    public String getWitchSkinTexture() {
        return getConfig().getString("witch.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTY4MzgxNTIwMzQyMiwKICAicHJvZmlsZUlkIiA6ICI1NWQ4NzcyODJmMDc0MWM5ODMyNjBhZDQ3OGE4ODNhMSIsCiAgInByb2ZpbGVOYW1lIiA6ICJUYW1ha2lfSXJvaGExIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzljNWZhYzlkMjE2NGM4NTEwZTYzZDA3NmU5MDBmYWZhZGNjNDcxNWQ4MDQyMTQzNGNjZTJmZGZkMGJiMDE1OCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
    }

    public String getWitchSkinSignature() {
        return getConfig().getString("witch.skin-signature",
            "rhzvhjIEuVPiVeh6n0YPLphl3CDbClnNiKE4QUUX0Zbsm4kxMT0xvjAWC8ELt9y0oFt6IDoP2bBd3Gyrm90Zbn6G4P8fcFI8HPMeDKzjPm1R/vVqtBsy9c4yhQuR0Lc2LaeLMIOp9vXgTTX0/2GraXtRzhP6KjtV+Mt5eZe+zqca66k5ZlJkEuIibPpKtyN3EHiitLhZzUIpJcahy1v3XV5RTkEbAhRs/y5MeyK94t04wLBwAlA9jdgaQZB0Um1jj8n+yB8DninFtJc7kQKjcQlLpnmR7qCj7X03pOVJAlIFfgEfkhmo8w27qwbw+Or1Mbc5/i8fGJmDqQ+HUHEy9UgBDRla87B2gVVp5wdqLTp/fSPHMttBI36QryQDMX8EOIztKnlq3cKv6u8P/mjLEG7NEvAFLDsniPo6D6IkYEENqbdJNvuKmmNtOlNWxVKMH8JbiYV1jgDwD8T6F9AFv9CysYHuel2SP57QM4qjctFiZtztEj4reYE8TbyvpIZqCirqgsg7IjFyHTekiOPqg2L1JKseD44FFgcKFM+27A9i20j3qa+jH6fecd/7uBtV9Ea/mb0O28f9/2vdg0zK9HM7FFsVWiPufrMP8+xGkiSHDPFDlKsqSK2F81WX+795CKxNPcI9aI8aS8y8gVfQQurG+BwcWnJ/f3ZkPfF/0Pw=");
    }

    public double getScarecrowHealth() {
        return Math.max(1.0D, getConfig().getDouble("scarecrow.health", 42.0D) * getNonVampireHealthMultiplier());
    }

    public int getScarecrowMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("scarecrow.max-per-player", 1));
    }

    public int getScarecrowSpawnRadius() {
        return Math.max(8, getConfig().getInt("scarecrow.spawn-radius", 48));
    }

    public String getScarecrowSkinName() {
        return getConfig().getString("scarecrow.skin-name", "bloodmoon_scarecrow_akgdmu");
    }

    public String getScarecrowSkinTexture() {
        return getConfig().getString("scarecrow.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzU3NjAyOTE1NCwKICAicHJvZmlsZUlkIiA6ICIwMmU5OGQ0MzU1MzQ0NWNkOGEwNWIzYWFlYmIzOWJmMSIsCiAgInByb2ZpbGVOYW1lIiA6ICJha2dkbXUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjRkMWVkZGVhYzgzMGQ5ZGVhMTJiODg4OTk3YjI4OWZhODRiNjljOTAxZjJjNTc0NGFmOTc1ZmM0OTRlYmUzIgogICAgfQogIH0KfQ==");
    }

    public String getScarecrowSkinSignature() {
        return getConfig().getString("scarecrow.skin-signature",
            "ScY77wYvapORK2ZC2kRpHKOINuYz3+iAn6qWHnUiIDe/e4oBXCD2jFNqHBL/N/mNYVNOXmyIH+4fMD1WV4EBIySRvwsSdK4KWvUJgnic28rb8c9lsgbPghgyk15ZFi0YTYfJ7NwfPA4RPtI3Zwg51Nm/IBdNbjaN9RV4APxoANTmPLoC206f0inH37RQvfimervD7v5A+rHiZavOQNs2+zrg9E0FefN4EJYl3OllQgn5HFcOiTibSYlAt11lTNvOvvM5gP2H5NnVSng47CHO8yWD1gigJx1Xe0hq9AiaH8JDOl+lCMQxq5sM+kkyn1p0XJ6ruteV5a1KeS2ZcbfZp3+rQ8uwmvvAhQ0kp09KABMSre3BUM5o1iSZy7hKVWi3Ru/Q+HqjRc8UvkkVAwfLFp9UJHFCAKC+/i4Q2lZ1N2LIwHvCKIEEpCEyf1bKdphkgdrOQUzGifXhrd620if61U8Lzijg7KZg5kinEV+gHa6Oh98RQs4o13gG6JMzZtrNMIV/U6iybBG2nUmBS/EhMliGkvuKGPZ86UvQ2c8OeSECJO7K01gECSSdVVf1dcrss2/8kKS/IjsTB5qsr+FS5JZziQY8kQaT3JtIUDleo50vZMOF4SHJoMq10H/ejMFXbVXbqn6GQHIZolnsPOmNb8EHoComq4MNwNRty6hN+o0=");
    }

    public double getGhostHealth() {
        return Math.max(1.0D, getConfig().getDouble("ghost.health", 14.0D) * getNonVampireHealthMultiplier());
    }

    public int getGhostMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("ghost.max-per-player", 1));
    }

    public int getGhostSpawnRadius() {
        return Math.max(8, getConfig().getInt("ghost.spawn-radius", 52));
    }

    public String getGhostSkinName() {
        return getConfig().getString("ghost.skin-name", "bloodmoon_ghost_bugs_bunny__");
    }

    public String getGhostSkinTexture() {
        return getConfig().getString("ghost.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzkxNTU0NDgxMywKICAicHJvZmlsZUlkIiA6ICI2ZDQxYTgyZTk1Y2I0ZjYyODlmYWYwMjU1MzAxMzI0YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJidWdzX2J1bm55X18iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTkxNjdmOGQyNTAwZDZiNDUwN2E3NTI5MmEzMWY4MzQ5YzNkYTI2ZjBiMzRmODQ2YmY4NTBiOGNkYjQxNjI2MCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
    }

    public String getGhostSkinSignature() {
        return getConfig().getString("ghost.skin-signature",
            "Iz3H9evufle1BWDJFWY7tq59essNjvYkszHpO59iEKTjXyJ7nqf5BXnBq17ZjMvZnqA05Tz4nl8NZEf7spsH97+Wb9AUBBeOWdYut4xhbkLgdGrWsihagm22m/WtlM4B/LA4Iuvt6ic1T4hteWoLI5/kEqaGjWlWgOQKENBIPF5QUv3YI0AJ0r71nlPv0ueI1NSmD0J31kVYV/Bw1+Av+AXIOun+tWPheWizCOdrNLnVCJEeBJdDU8wwWTxTRiP2fXK/m+K+sOPR8r68L87uq4ymYqBvqA+n3PxVwRPvI/wuavoWV4e3om4aqo8jZ48xm0G/3/aFcf2LOK8inO8MJsBJcD1U8jbzQ6hdiP1x6x/PCe5wsMzsiUvQBo0TmF1o0Pngqc6dRht4TbzCQHU4Ydd68Zf2JMPc9svctXC242Vyb6MhJxxmxKQRkNFHfimIVI8Qg/6c3ZDxyOLFqY7xGpobh3lNITZN7AeHLk6vaI9knFpH5tGYgZ6pkL2Z6JEoTLoZDqz8d7D3dHIis6w9Wtt8NlZCa4xGukl90tp24pCqURcfp24Jqdb8SaBox13VBZ7zOe7h/Y3aopWBCX3Y+Iq+Do8X9DbO03zXCVbkkVy29/82b161o2/dW1NlLfJFnAhVZbSB479wWbpZTqdGGFedSH9T33nGGUdJH3TAVso=");
    }

    public double getWerewolfHealth() {
        return Math.max(1.0D, getConfig().getDouble("werewolf.health", 58.0D) * getNonVampireHealthMultiplier());
    }

    public int getWerewolfMaxPerPlayer() {
        return Math.max(0, getConfig().getInt("werewolf.max-per-player", 1));
    }

    public int getWerewolfSpawnRadius() {
        return Math.max(8, getConfig().getInt("werewolf.spawn-radius", 52));
    }

    public String getWerewolfSkinName() {
        return getConfig().getString("werewolf.skin-name", "bloodmoon_werewolf_DuskTalon");
    }

    public String getWerewolfSkinTexture() {
        return getConfig().getString("werewolf.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzYzNDQ0OTQwOSwKICAicHJvZmlsZUlkIiA6ICJhODg0MTMzZTdlODc0YjZkYjJmMWIwYjg0ZGU5NmZhYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJEdXNrVGFsb24iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjY1NTVhOTAwOWUyZmZhNjY5Nzg2YWI4YWViZGZlZTM3ZmE1MWY3OWRiODM0MDgwNWZlNjk3MDk0Zjk2YjMwMSIKICAgIH0KICB9Cn0=");
    }

    public String getWerewolfSkinSignature() {
        return getConfig().getString("werewolf.skin-signature",
            "FXw+K2p8JB/vlEOlsSi8Xqw7hSEjgXFyfhkdyGhbMJjtcUJkujL/wMinxqRFZ9J7D45ZibaM2t/r8o/iFIz6iOuvbRkeSJZVMkLF1m/uGL4vXSyF3J+pdq8dqcZVhRqDKusdhiSZskfh8lAeQcUthzYNFjL4vxQdXJc4kEjNug+5HsubZzXlflTnllpvba/uqr+52MlXYukmYNpy9uHjc8Hz9DFTGL3fIma3bke49J41nu8OtbLSrf6KSOtol/2H/FCm0FaJrp458AbVEN4hbfYDTVMIv8g2mZZPYJFhxj7N9iCnTKuX9KmUS+MiMv8d5DIQ9FlYmwCKgqv0K3bn47Wx0n9sl534ylwqJsVPTG96ZlV3S6EqrK1lSNYdur2CwPykfKsLfM33NBEnrIKWl2k8HWXaOh2GMkrQxJbd0/Ogs0phSxqOA5OMnUFNj2omUswBnbzz2tOUxuENQnDnHuv3eEHkrKZ2rggYuxAFRvnyK2ZkxtlRMglOqQ2NzFx5zsOJORl7kw0sX293JHoJfGPGGyMQLsK+KGnxJbAWkDVXlguUxTznlAyILx7wV2qB37UHd/5qOPz/WTg419/3ElXTjpxMn50j77p4PGpAGHF0pZHJ/u+LkxhMzXZv21DyndrZt4ptRMQeD4MHALj935PjW4q0i9brST5BhD2Nr98=");
    }

    public double getBleedChance() {
        return Math.max(0.0D, Math.min(1.0D, getConfig().getDouble("bleed.chance", 0.4D)));
    }

    public double getBleedDamagePerTick() {
        return Math.max(0.0D, getConfig().getDouble("bleed.damage-per-tick", 1.0D));
    }

    public int getBleedIntervalTicks() {
        return Math.max(1, getConfig().getInt("bleed.interval-ticks", 40));
    }

    public int getBleedMaxStacks() {
        return Math.max(1, getConfig().getInt("bleed.max-stacks", 2));
    }

    public String getEventStartMessage() {
        return getConfig().getString("messages.event-start", "§4- THE BLOOD MOON RISES -");
    }

    public String getEventEndMessage() {
        return getConfig().getString("messages.event-end", "§6The Blood Moon fades... for now.");
    }

    private void addDefaults(FileConfiguration config) {
        config.addDefault("bloodmoon.chance", 24);
        config.addDefault("bloodmoon.special-mob-replace-chance", 0.08D);
        config.addDefault("bloodmoon.worlds", List.of("world"));
        config.addDefault("vampire.health", 40.0D);
        config.addDefault("vampire.stalk-ticks-min", 80);
        config.addDefault("vampire.stalk-ticks-max", 140);
        config.addDefault("vampire.spawn-radius", 48);
        config.addDefault("vampire.max-per-player", 1);
        config.addDefault("vampire.skin-name", "bloodmoon_vampire_150ea3e3982a1bd3");
        config.addDefault("vampire.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc2NTIzMzk2OTc2NiwKICAicHJvZmlsZUlkIiA6ICIwNWU0OTczZTk5ZDk0NGY2YjczNjY3ZTEzMzdiY2IzOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNaW5lX1NraW4iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjk4YTliNzdlOWNmM2MxYjE1ZmMzNTlhN2FjZmMxZjYyMWYyYTc1OWVmNzZmYmI5N2I0Nzg3NTQ3MWZkMzI1ZiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
        config.addDefault("vampire.skin-signature",
            "wYc8PrLfnZyMfsRSHyTBP/CeBQRwzDG66MSQVlsUnghqwxvJdF47pwWlNPcI7yhU1+/Vwi+OYCVF/Ur7Pf+PP/Lx1U9z4x2jm0T50os8MzPh5VbRP54n82Qd727p9iSVJE9YUTrAG+nbqJ5joy0O5JLP9fBjZNA9RLsj6fFwnYccTc4zIV2QVamj0MNuYHihNHYiBh4lWTR8pLi+WH+/qS0Ll5And0QraSLLQdKdpsVl1IPJjghjGmqaRtUlQ+KaOhQBzcYnxxR7Dq4/IEOrkJUyq1/y/BmVuPpQldk9l6iQ8YVBEjJNHd9Ji9vkeplEJw+XxtWkh+yTbDz6XeszGUyIZ5CbY28VIDJYMmRWLMfgg37eK46xTrKzk+N9Yw2/qOrJtGHQ4n2cbbi76OAGv92eGsOWbR7Dw36zVBRHuUdn6+YqKChW3sqL3ES8JqA3KSwgs76GQMkDK/WlxR0LdE/ekaTLRYxKcBxbtverWhqCWt4Mt0zGraPfdNyQs9fNE6dqs2zHh0WCY0bPZ9gdcwBnzEQdgFMkNPGtVdFsCdU64hUzA/BgFGPLL4cWSss5IDNgJ3o+VxXS8u3wANIemvZIhHozEt04uNla7X6/VWhdm6D/k8PC1F8IhAwD8byY1wkYx2Q4ocgdVIGT4pq1gEWWtJahQFPfyzHHvP512H0=");
        config.addDefault("vampire.skin-url", "");
        config.addDefault("clown.health", 32.0D);
        config.addDefault("clown.max-per-player", 1);
        config.addDefault("clown.spawn-radius", 48);
        config.addDefault("clown.snap-radius", 6.0D);
        config.addDefault("clown.manic-hp-threshold", 0.4D);
        config.addDefault("clown.manic-cooldown-multiplier", 0.5D);
        config.addDefault("clown.balloon-cap", 6);
        config.addDefault("clown.twisted-teleport-hops", 3);
        config.addDefault("clown.twisted-teleport-interval-ticks", 40);
        config.addDefault("clown.skin-name", "bloodmoon_clown_tdexterious");
        config.addDefault("clown.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzU1NzM5MDEwMSwKICAicHJvZmlsZUlkIiA6ICJjMmYxYWZkZmU3NGU0Zjk4YmFmN2MxNWI2YjVlYzFhYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJ0ZGV4dGVyaW91cyIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS84Njg2YjFlNGRiM2VlZTEzZTMwMmIwMzY2YWIxOTg2ODcwZThiNTcxMTJhYzI2YmMwOWY5NDVkNDc5YzAzMTkyIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=");
        config.addDefault("clown.skin-signature",
            "AqNWXbfduDlOYFxlql5nRs6JxVttd3URTQmjHPvc6N73XNHNWYfc4SaTZ6vWwzp2JHgutcXGrSLAg+U3YDIf4TGSlXMWxLbxR7yL57ca4xo1Vhkvfzkr0j1770TEAAYm23exnkw/g7CaRWU/Y3VN0c3xAG5wmWgXSs+wunLqF0rnKyCeuW//JYSWNDKPRghlOQltxpQ4OJAvT3/3HDIPXgigx1UP6bMJx4YkbcZ1nNlB0Ka06LxXfR7xp21ZKvGYi8XHW35VEpofGhwXfeDIj8TdX41bRVTlFiXuL7FIauHmhrhPS5FqPi97AahTGOvuRvK2BiyGbkLZt5dBB4KRizhz8c5b4LBc/U+sJs/f0FAF84NF+C/hHVTspNY5hj1o7xyyqJKzh4jtzutxyRbrGIkG9MR44TsO6qNPYV2szkEGPWMu8GxC2yw6czPy8Q1EnPvqL3goiTek6isWeba3DDtfkKfKQ7YcFwN/VMgN/0Xr2H/Jlbc3U7U3i2G3EfWB4mfNcq4xdR2GS8gvO0DpFrMNQATitZqWE+W0X430Lr0Ojji6KtaiFwBI3WjL2fwxzwrxbK1UJNPU3Qi28c/+Iz6z0tXCRTjqhNF5vDxBVWfvyysQTkS/yNh2d46/bm/ERqdA2BfXDDqiHuJOLqyhOnoRcBzfa/02aLx4sEeZkIU=");
        config.addDefault("zombie.health", 50.0D);
        config.addDefault("zombie.max-per-player", 1);
        config.addDefault("zombie.spawn-radius", 48);
        config.addDefault("zombie.infection-tick-interval", 60);
        config.addDefault("zombie.infection-damage", 0.5D);
        config.addDefault("zombie.infection-duration-ticks", 400);
        config.addDefault("zombie.infection-jump-radius", 4.0D);
        config.addDefault("zombie.horde-call-radius", 20.0D);
        config.addDefault("zombie.risen-hp-fraction", 0.4D);
        config.addDefault("zombie.plague-burst-radius", 6.0D);
        config.addDefault("zombie.skin-name", "bloodmoon_zombie_977d6645fa698789");
        config.addDefault("zombie.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTcyOTM0MTA2NTk5NSwKICAicHJvZmlsZUlkIiA6ICJhMWQzNzljMjhhODA0ZTMxYjY3YjcyMDcyNWE0ZjI3NSIsCiAgInByb2ZpbGVOYW1lIiA6ICJDeXNwZWN0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzY4Y2QxYmE3OGU5OTFkYmM4MWNiMDIyOTYyYzA1MzVmYTg5MjEyNzRmMGYzZTJjNDI1NzcxOWE5YWE2MDNiOWUiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==");
        config.addDefault("zombie.skin-signature",
            "Y9cLhQ0f2QwfqjsjCKHHAAWiPtwhVoitGTSiy6zXe9C1aM38fLWin08dD5taRvPmfW+dVDTO9Udt/IXYY9XTZMwvrWDqZ17/vwlncwEFXQg/V9ax5mHQySUWafE/7E7VR7XPBV7QDVIxRZzO8gSzcHQyuo+ONyuz16E+cnaJNKi0Aotw+jsfTMhLUQ1JnjbCwG1mf+WTelNB3JxbykBQ062cp4dEQWfDHCn6bZiWzOwh/ldfYFIY/xVldOYhmNpoHH3BPvjxiEROezMMHY4R/jK2yqP82wWIra73xu6JWSQTFsefImcoTxcBhkrUz7pNu+oZwI7FAZwJpy+EvYBajmSxCGRopvu2xfpjp4GsBrZ4/HmSEZPcGl2AM2b3ITIz2rsaaBs2aWeKsUbXmrefKQXRgy/fAp8mvm5nlevLq3sv3+/auBC2MUmwC+9/fBoP8UpuYTDnrz9Q4Sqt00K4KxwmkwdfTH5vsNwxOpbhHDfNLDAb4ayJoSykTNEF6dwRqZsu9AiayMA7YoKoWUCeNKcVz+4OCKE2RfNK+v1lMgh5l9xeFy+bQfBrwv5pwkxqXg8xVoW6YcUS5avlmpvXNw/KV3cBmyf9JCViCI9t9oK5FqmpLsWodpKcoTOMKhibBE+CK81jPLj3JoXLOEJwB1lPWKFl7UQ13fjQvbDl3yc=");
        config.addDefault("witch.health", 40.0D);
        config.addDefault("witch.max-per-player", 1);
        config.addDefault("witch.spawn-radius", 48);
        config.addDefault("witch.skin-name", "bloodmoon_witch_9b19c47ec1b068a5");
        config.addDefault("witch.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTY4MzgxNTIwMzQyMiwKICAicHJvZmlsZUlkIiA6ICI1NWQ4NzcyODJmMDc0MWM5ODMyNjBhZDQ3OGE4ODNhMSIsCiAgInByb2ZpbGVOYW1lIiA6ICJUYW1ha2lfSXJvaGExIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzljNWZhYzlkMjE2NGM4NTEwZTYzZDA3NmU5MDBmYWZhZGNjNDcxNWQ4MDQyMTQzNGNjZTJmZGZkMGJiMDE1OCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
        config.addDefault("witch.skin-signature",
            "rhzvhjIEuVPiVeh6n0YPLphl3CDbClnNiKE4QUUX0Zbsm4kxMT0xvjAWC8ELt9y0oFt6IDoP2bBd3Gyrm90Zbn6G4P8fcFI8HPMeDKzjPm1R/vVqtBsy9c4yhQuR0Lc2LaeLMIOp9vXgTTX0/2GraXtRzhP6KjtV+Mt5eZe+zqca66k5ZlJkEuIibPpKtyN3EHiitLhZzUIpJcahy1v3XV5RTkEbAhRs/y5MeyK94t04wLBwAlA9jdgaQZB0Um1jj8n+yB8DninFtJc7kQKjcQlLpnmR7qCj7X03pOVJAlIFfgEfkhmo8w27qwbw+Or1Mbc5/i8fGJmDqQ+HUHEy9UgBDRla87B2gVVp5wdqLTp/fSPHMttBI36QryQDMX8EOIztKnlq3cKv6u8P/mjLEG7NEvAFLDsniPo6D6IkYEENqbdJNvuKmmNtOlNWxVKMH8JbiYV1jgDwD8T6F9AFv9CysYHuel2SP57QM4qjctFiZtztEj4reYE8TbyvpIZqCirqgsg7IjFyHTekiOPqg2L1JKseD44FFgcKFM+27A9i20j3qa+jH6fecd/7uBtV9Ea/mb0O28f9/2vdg0zK9HM7FFsVWiPufrMP8+xGkiSHDPFDlKsqSK2F81WX+795CKxNPcI9aI8aS8y8gVfQQurG+BwcWnJ/f3ZkPfF/0Pw=");
        config.addDefault("scarecrow.health", 42.0D);
        config.addDefault("scarecrow.max-per-player", 1);
        config.addDefault("scarecrow.spawn-radius", 48);
        config.addDefault("scarecrow.skin-name", "bloodmoon_scarecrow_akgdmu");
        config.addDefault("scarecrow.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzU3NjAyOTE1NCwKICAicHJvZmlsZUlkIiA6ICIwMmU5OGQ0MzU1MzQ0NWNkOGEwNWIzYWFlYmIzOWJmMSIsCiAgInByb2ZpbGVOYW1lIiA6ICJha2dkbXUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjRkMWVkZGVhYzgzMGQ5ZGVhMTJiODg4OTk3YjI4OWZhODRiNjljOTAxZjJjNTc0NGFmOTc1ZmM0OTRlYmUzIgogICAgfQogIH0KfQ==");
        config.addDefault("scarecrow.skin-signature",
            "ScY77wYvapORK2ZC2kRpHKOINuYz3+iAn6qWHnUiIDe/e4oBXCD2jFNqHBL/N/mNYVNOXmyIH+4fMD1WV4EBIySRvwsSdK4KWvUJgnic28rb8c9lsgbPghgyk15ZFi0YTYfJ7NwfPA4RPtI3Zwg51Nm/IBdNbjaN9RV4APxoANTmPLoC206f0inH37RQvfimervD7v5A+rHiZavOQNs2+zrg9E0FefN4EJYl3OllQgn5HFcOiTibSYlAt11lTNvOvvM5gP2H5NnVSng47CHO8yWD1gigJx1Xe0hq9AiaH8JDOl+lCMQxq5sM+kkyn1p0XJ6ruteV5a1KeS2ZcbfZp3+rQ8uwmvvAhQ0kp09KABMSre3BUM5o1iSZy7hKVWi3Ru/Q+HqjRc8UvkkVAwfLFp9UJHFCAKC+/i4Q2lZ1N2LIwHvCKIEEpCEyf1bKdphkgdrOQUzGifXhrd620if61U8Lzijg7KZg5kinEV+gHa6Oh98RQs4o13gG6JMzZtrNMIV/U6iybBG2nUmBS/EhMliGkvuKGPZ86UvQ2c8OeSECJO7K01gECSSdVVf1dcrss2/8kKS/IjsTB5qsr+FS5JZziQY8kQaT3JtIUDleo50vZMOF4SHJoMq10H/ejMFXbVXbqn6GQHIZolnsPOmNb8EHoComq4MNwNRty6hN+o0=");
        config.addDefault("ghost.health", 22.0D);
        config.addDefault("ghost.max-per-player", 1);
        config.addDefault("ghost.spawn-radius", 52);
        config.addDefault("ghost.skin-name", "bloodmoon_ghost_bugs_bunny__");
        config.addDefault("ghost.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzkxNTU0NDgxMywKICAicHJvZmlsZUlkIiA6ICI2ZDQxYTgyZTk1Y2I0ZjYyODlmYWYwMjU1MzAxMzI0YiIsCiAgInByb2ZpbGVOYW1lIiA6ICJidWdzX2J1bm55X18iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYTkxNjdmOGQyNTAwZDZiNDUwN2E3NTI5MmEzMWY4MzQ5YzNkYTI2ZjBiMzRmODQ2YmY4NTBiOGNkYjQxNjI2MCIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9");
        config.addDefault("ghost.skin-signature",
            "Iz3H9evufle1BWDJFWY7tq59essNjvYkszHpO59iEKTjXyJ7nqf5BXnBq17ZjMvZnqA05Tz4nl8NZEf7spsH97+Wb9AUBBeOWdYut4xhbkLgdGrWsihagm22m/WtlM4B/LA4Iuvt6ic1T4hteWoLI5/kEqaGjWlWgOQKENBIPF5QUv3YI0AJ0r71nlPv0ueI1NSmD0J31kVYV/Bw1+Av+AXIOun+tWPheWizCOdrNLnVCJEeBJdDU8wwWTxTRiP2fXK/m+K+sOPR8r68L87uq4ymYqBvqA+n3PxVwRPvI/wuavoWV4e3om4aqo8jZ48xm0G/3/aFcf2LOK8inO8MJsBJcD1U8jbzQ6hdiP1x6x/PCe5wsMzsiUvQBo0TmF1o0Pngqc6dRht4TbzCQHU4Ydd68Zf2JMPc9svctXC242Vyb6MhJxxmxKQRkNFHfimIVI8Qg/6c3ZDxyOLFqY7xGpobh3lNITZN7AeHLk6vaI9knFpH5tGYgZ6pkL2Z6JEoTLoZDqz8d7D3dHIis6w9Wtt8NlZCa4xGukl90tp24pCqURcfp24Jqdb8SaBox13VBZ7zOe7h/Y3aopWBCX3Y+Iq+Do8X9DbO03zXCVbkkVy29/82b161o2/dW1NlLfJFnAhVZbSB479wWbpZTqdGGFedSH9T33nGGUdJH3TAVso=");
        config.addDefault("werewolf.health", 58.0D);
        config.addDefault("werewolf.max-per-player", 1);
        config.addDefault("werewolf.spawn-radius", 52);
        config.addDefault("werewolf.skin-name", "bloodmoon_werewolf_DuskTalon");
        config.addDefault("werewolf.skin-texture",
            "ewogICJ0aW1lc3RhbXAiIDogMTc3NzYzNDQ0OTQwOSwKICAicHJvZmlsZUlkIiA6ICJhODg0MTMzZTdlODc0YjZkYjJmMWIwYjg0ZGU5NmZhYyIsCiAgInByb2ZpbGVOYW1lIiA6ICJEdXNrVGFsb24iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjY1NTVhOTAwOWUyZmZhNjY5Nzg2YWI4YWViZGZlZTM3ZmE1MWY3OWRiODM0MDgwNWZlNjk3MDk0Zjk2YjMwMSIKICAgIH0KICB9Cn0=");
        config.addDefault("werewolf.skin-signature",
            "FXw+K2p8JB/vlEOlsSi8Xqw7hSEjgXFyfhkdyGhbMJjtcUJkujL/wMinxqRFZ9J7D45ZibaM2t/r8o/iFIz6iOuvbRkeSJZVMkLF1m/uGL4vXSyF3J+pdq8dqcZVhRqDKusdhiSZskfh8lAeQcUthzYNFjL4vxQdXJc4kEjNug+5HsubZzXlflTnllpvba/uqr+52MlXYukmYNpy9uHjc8Hz9DFTGL3fIma3bke49J41nu8OtbLSrf6KSOtol/2H/FCm0FaJrp458AbVEN4hbfYDTVMIv8g2mZZPYJFhxj7N9iCnTKuX9KmUS+MiMv8d5DIQ9FlYmwCKgqv0K3bn47Wx0n9sl534ylwqJsVPTG96ZlV3S6EqrK1lSNYdur2CwPykfKsLfM33NBEnrIKWl2k8HWXaOh2GMkrQxJbd0/Ogs0phSxqOA5OMnUFNj2omUswBnbzz2tOUxuENQnDnHuv3eEHkrKZ2rggYuxAFRvnyK2ZkxtlRMglOqQ2NzFx5zsOJORl7kw0sX293JHoJfGPGGyMQLsK+KGnxJbAWkDVXlguUxTznlAyILx7wV2qB37UHd/5qOPz/WTg419/3ElXTjpxMn50j77p4PGpAGHF0pZHJ/u+LkxhMzXZv21DyndrZt4ptRMQeD4MHALj935PjW4q0i9brST5BhD2Nr98=");
        config.addDefault("bleed.chance", 0.4D);
        config.addDefault("bleed.damage-per-tick", 1.0D);
        config.addDefault("bleed.interval-ticks", 40);
        config.addDefault("bleed.max-stacks", 2);
        config.addDefault("messages.event-start", "§4- THE BLOOD MOON RISES -");
        config.addDefault("messages.event-end", "§6The Blood Moon fades... for now.");
        config.options().copyDefaults(true);
    }

    private double getNonVampireHealthMultiplier() {
        if (plugin.getBloodMoonManager() == null) {
            return 1.0D;
        }
        return plugin.getBloodMoonManager().getNonVampireHealthMultiplier();
    }
}


