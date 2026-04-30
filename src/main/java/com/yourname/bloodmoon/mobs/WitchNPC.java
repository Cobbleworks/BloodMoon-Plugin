package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.traits.WitchTrait;
import java.lang.reflect.Method;
import java.util.*;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class WitchNPC {
    private enum WitchState { COMBAT, CASTING, DEAD }
    private enum WitchAbility { MIRROR, HEX, SHACKLE, BLAST, CAULDRON, MIND }

    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<WitchAbility, Integer> cooldowns = new EnumMap<>(WitchAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private WitchState state = WitchState.COMBAT;
    private WitchState beforeCast = WitchState.COMBAT;
    private WitchAbility pending;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private int stateTicks;
    private int castTicks;
    private boolean cleaned;
    private boolean deathStarted;

    public WitchNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

    public boolean isDead() { return state == WitchState.DEAD || cleaned || deathStarted; }
    public Location getCurrentLocation() {
        LivingEntity e = getLivingEntity();
        return e != null ? e.getLocation() : (lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone());
    }
    public void onTraitTick() { LivingEntity e = getLivingEntity(); if (e != null) lastKnownLocation = e.getLocation(); }
    public void onNpcSpawn() { LivingEntity e = getLivingEntity(); if (e != null) applyConfiguredHealth(e); }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        if (!(event.getTarget() instanceof Player player) || state == WitchState.DEAD) return;
        target = player;
        plugin.getDecayPlagueEffect().applyStack(player);
    }

    public void startDeathSequence() {
        if (deathStarted) return;
        deathStarted = true;
        state = WitchState.DEAD;
        cancelControllerOnly();
        cancelTasks();
        Location death = getCurrentLocation();
        World world = death.getWorld();
        if (world != null) {
            world.playSound(death, Sound.ENTITY_WITCH_DEATH, 1.0F, 0.9F);
            world.spawnParticle(Particle.WITCH, death.clone().add(0,1,0), 30, 0.5, 0.6, 0.5, 0.2);
            if (random.nextDouble() <= 0.5) world.dropItemNaturally(death, new ItemStack(Material.REDSTONE, 3));
            if (random.nextDouble() <= 0.2) world.dropItemNaturally(death, new ItemStack(Material.GHAST_TEAR, 1));
            ExperienceOrb orb = world.spawn(death.clone().add(0,0.25,0), ExperienceOrb.class);
            orb.setExperience(45 + random.nextInt(20));
        }
        BukkitRunnable cleanupTask = new BukkitRunnable() { @Override public void run() { cleanup(); } };
        tasks.add(cleanupTask);
        cleanupTask.runTaskLater(plugin, 60L);
    }

    public void cleanup() {
        if (cleaned) return;
        cleaned = true;
        cancelControllerOnly();
        cancelTasks();
        if (npc.isSpawned()) npc.despawn();
        npc.destroy();
        plugin.getNPCManager().unregisterWitch(npc.getId());
    }

    public void handleCloneHit(Entity entity) {
        if (entity instanceof Witch witch) {
            witch.getWorld().spawnParticle(Particle.SMOKE, witch.getLocation().add(0,1,0), 14, 0.2, 0.3, 0.2, 0.03);
            witch.remove();
        }
    }

    private void configureNpc() {
        npc.data().set("bloodmoon-witch", true);
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        WitchTrait trait = npc.getOrAddTrait(WitchTrait.class);
        trait.bind(this);
        configureSkin();
        configureSentinel();
        if (!npc.isSpawned()) npc.spawn(spawnLocation.clone());
        LivingEntity e = getLivingEntity();
        if (e != null) {
            applyConfiguredHealth(e);
            hideNameplate(e);
        }
    }

    private void configureSkin() {
        String skinName = plugin.getConfigManager().getWitchSkinName();
        String texture = plugin.getConfigManager().getWitchSkinTexture();
        String signature = plugin.getConfigManager().getWitchSkinSignature();
        if ((skinName == null || skinName.isBlank()) && (texture == null || texture.isBlank())) return;
        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = npc.getOrAddTrait(skinTraitClass);
            skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setFetchDefaultSkin", boolean.class).invoke(skinTrait, false);
            if (texture != null && !texture.isBlank() && signature != null && !signature.isBlank()) {
                Method setSkinPersistent = skinTraitClass.getMethod("setSkinPersistent", String.class, String.class, String.class);
                setSkinPersistent.invoke(skinTrait, skinName, signature, texture);
                return;
            }
            if (skinName != null && !skinName.isBlank()) {
                skinTraitClass.getMethod("setSkinName", String.class, boolean.class).invoke(skinTrait, skinName, true);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply witch skin: " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
        s.setInvincible(false);
        s.setHealth(plugin.getConfigManager().getWitchHealth());
        s.health = plugin.getConfigManager().getWitchHealth();
        s.damage = 4.5D;
        s.respawnTime = -1;
        s.chaseRange = 30.0D;
        s.armor = 0.1D;
        s.protectFromIgnores = false;
        s.allTargets = new SentinelTargetList();
        s.addTarget("players");
        s.allIgnores = new SentinelTargetList();
    }

    private void hideNameplate(LivingEntity entity) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            if (board == null) return;
            Team team = board.getTeam("bm_hidden_npc");
            if (team == null) {
                team = board.registerNewTeam("bm_hidden_npc");
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            if (entity instanceof Player p) team.addEntry(p.getName());
        } catch (Exception ignored) {}
    }

    private void applyConfiguredHealth(LivingEntity entity) {
        double hp = plugin.getConfigManager().getWitchHealth();
        var attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(hp);
            entity.setHealth(Math.min(hp, entity.getHealth()));
        }
    }

    private void startController() {
        controllerTask = new BukkitRunnable() { @Override public void run() { tick(); } };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick() {
        if (cleaned || deathStarted) return;
        stateTicks++;
        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));
        onTraitTick();

        if (state == WitchState.CASTING) { tickCasting(); return; }
        if (state != WitchState.COMBAT) return;

        Player player = ensureTarget(48.0D);
        if (player == null) {
            player = findNearestPlayer(getCurrentLocation(), 48.0D);
            target = player;
        }
        if (player == null) return;

        npc.getNavigator().setTarget(player, true);
        npc.faceLocation(player.getEyeLocation());
        if (stateTicks % 24 == 0) player.getWorld().playSound(getCurrentLocation(), Sound.ENTITY_WITCH_AMBIENT, 0.8F, 0.8F);
        if (stateTicks % 35 == 0) {
            WitchAbility ability = chooseAbility();
            if (ability != null) startCasting(ability);
        }
    }

    private void tickCasting() {
        if (stateTicks < castTicks) return;
        WitchAbility ability = pending;
        pending = null;
        state = beforeCast;
        stateTicks = 0;
        castTicks = 0;
        if (ability != null) executeAbility(ability);
    }

    private void startCasting(WitchAbility ability) {
        if (state == WitchState.CASTING || state == WitchState.DEAD) return;
        pending = ability;
        beforeCast = state;
        state = WitchState.CASTING;
        stateTicks = 0;
        castTicks = 20;
    }

    private WitchAbility chooseAbility() {
        List<WitchAbility> options = new ArrayList<>();
        for (WitchAbility a : WitchAbility.values()) if (cooldowns.getOrDefault(a, 0) <= 0) options.add(a);
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }

    private void executeAbility(WitchAbility ability) {
        switch (ability) {
            case MIRROR -> castMirrorCurse();
            case HEX -> castHexMark();
            case SHACKLE -> castSoulShackle();
            case BLAST -> castBlasts(false);
            case CAULDRON -> castCauldronSummon();
            case MIND -> castMindHex();
        }
        cooldowns.put(ability, switch (ability) {
            case MIRROR -> 220;
            case HEX -> 260;
            case SHACKLE -> 180;
            case BLAST -> 150;
            case CAULDRON -> 340;
            case MIND -> 220;
        });
    }

    private void castMirrorCurse() {
        LivingEntity e = getLivingEntity();
        if (e == null) return;
        Location b = e.getLocation();
        Location t = b.clone().add(randomDouble(-4, 4), 0, randomDouble(-4, 4));
        t.setY(b.getWorld().getHighestBlockYAt(t) + 1.0D);
        e.teleport(t);
        Location c = b.clone().add(randomDouble(-4, 4), 0, randomDouble(-4, 4));
        c.setY(b.getWorld().getHighestBlockYAt(c) + 1.0D);
        Witch clone = (Witch) b.getWorld().spawnEntity(c, EntityType.WITCH);
        clone.setAI(false);
        clone.setSilent(true);
        clone.setMetadata("bloodmoon-witch-clone", new FixedMetadataValue(plugin, npc.getId()));
        castBlasts(true);
        BukkitRunnable vanish = new BukkitRunnable() { @Override public void run() { if (clone.isValid()) clone.remove(); } };
        tasks.add(vanish);
        vanish.runTaskLater(plugin, 120L);
    }

    private void castHexMark() {
        Player p = ensureTarget(35.0D);
        if (p == null) return;
        Location c = p.getLocation();
        World w = c.getWorld();
        if (w == null) return;
        for (int i = 0; i < 32; i++) {
            double a = (Math.PI * 2.0D) * i / 32.0D;
            Location pt = c.clone().add(Math.cos(a) * 3.0D, 0.1D, Math.sin(a) * 3.0D);
            w.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(70, 240, 240), 1.0F));
        }
        BukkitRunnable drop = new BukkitRunnable() {
            @Override public void run() {
                for (Player x : w.getPlayers()) {
                    if (x.isDead()) continue;
                    if (x.getLocation().distanceSquared(c) <= 9.5D) {
                        x.setVelocity(new Vector(0, -2.0D, 0));
                        x.damage(8.0D);
                        plugin.getDecayPlagueEffect().applyStack(x);
                    }
                }
            }
        };
        tasks.add(drop);
        drop.runTaskLater(plugin, 60L);
    }

    private void castSoulShackle() {
        Player p = ensureTarget(30.0D);
        if (p == null) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 3, true, true, true));
        plugin.getDecayPlagueEffect().applyStack(p);
    }

    private void castBlasts(boolean visualOnly) {
        Player p = ensureTarget(36.0D);
        Location s = getCurrentLocation().add(0, 1.2D, 0);
        World w = s.getWorld();
        if (p == null || w == null) return;
        int shots = 4 + random.nextInt(4);
        BukkitRunnable r = new BukkitRunnable() {
            int left = shots;
            @Override public void run() {
                if (left-- <= 0 || p.isDead() || isDead()) { cancel(); return; }
                Vector dir = p.getLocation().toVector().subtract(s.toVector());
                if (dir.lengthSquared() > 0.001D) dir.normalize();
                Location cur = s.clone();
                for (int i = 0; i < 14; i++) {
                    cur.add(dir.clone().multiply(0.45D));
                    w.spawnParticle(Particle.DUST, cur, 1, 0.05D, 0.05D, 0.05D, 0, new Particle.DustOptions(Color.fromRGB(255, 90, 190), 0.95F));
                }
                if (!visualOnly && p.getLocation().distanceSquared(cur) <= 6.0D) {
                    p.damage(2.2D);
                    plugin.getDecayPlagueEffect().applyStack(p);
                }
            }
        };
        tasks.add(r);
        r.runTaskTimer(plugin, 0L, 8L);
    }

    private void castCauldronSummon() {
        Location c = getCurrentLocation();
        World w = c.getWorld();
        if (w == null) return;
        Vector f = c.getDirection();
        Location cauldron = c.clone().add(f.clone().multiply(2.0D));
        Block b = cauldron.getBlock();
        Material old = b.getType();
        if (old.isAir()) b.setType(Material.CAULDRON, false);
        BukkitRunnable r = new BukkitRunnable() {
            @Override public void run() {
                Location l = c.clone().add(-2, 0, 0);
                Location rr = c.clone().add(2, 0, 0);
                w.strikeLightningEffect(l);
                w.strikeLightningEffect(rr);
                plugin.getNPCManager().spawnShamblingZombie(l, findNearestPlayer(c, 40.0D));
                plugin.getNPCManager().spawnShamblingZombie(rr, findNearestPlayer(c, 40.0D));
                if (b.getType() == Material.CAULDRON) b.setType(old, false);
            }
        };
        tasks.add(r);
        r.runTaskLater(plugin, 40L);
    }

    private void castMindHex() {
        Player p = ensureTarget(30.0D);
        if (p == null) return;
        plugin.getMindHexEffect().apply(p, 80);
        plugin.getDecayPlagueEffect().applyStack(p);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 10, 0.2D, 0.3D, 0.2D, 0,
            new Particle.DustOptions(Color.fromRGB(0, 220, 220), 1.0F));
    }

    private Player ensureTarget(double radius) {
        if (target == null || !target.isOnline() || target.isDead()) { target = null; return null; }
        Location cur = getCurrentLocation();
        if (cur.getWorld() != target.getWorld() || cur.distanceSquared(target.getLocation()) > radius * radius) {
            target = null;
            return null;
        }
        return target;
    }

    private Player findNearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null) return null;
        double rs = radius * radius;
        return location.getWorld().getPlayers().stream()
            .filter(p -> !p.isDead())
            .filter(p -> p.getLocation().distanceSquared(location) <= rs)
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(location)))
            .orElse(null);
    }

    private LivingEntity getLivingEntity() { return npc.isSpawned() && npc.getEntity() instanceof LivingEntity le ? le : null; }
    private double randomDouble(double min, double max) { return min + (random.nextDouble() * (max - min)); }
    private void cancelControllerOnly() { if (controllerTask != null) { controllerTask.cancel(); controllerTask = null; } }
    private void cancelTasks() { for (BukkitRunnable task : tasks) { try { task.cancel(); } catch (Exception ignored) {} } tasks.clear(); }
}
