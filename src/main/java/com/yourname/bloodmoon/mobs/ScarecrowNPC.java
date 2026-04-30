package com.yourname.bloodmoon.mobs;

import com.yourname.bloodmoon.BloodMoonPlugin;
import com.yourname.bloodmoon.traits.ScarecrowTrait;
import java.util.*;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.mcmonkey.sentinel.SentinelTrait;
import org.mcmonkey.sentinel.events.SentinelAttackEvent;
import org.mcmonkey.sentinel.targeting.SentinelTargetList;

public final class ScarecrowNPC {

    // ─── Appearance constants ─────────────────────────────────────────────────
    private static final Particle.DustOptions DUST_CYAN  = new Particle.DustOptions(Color.fromRGB(  0, 200, 200), 1.1F);
    private static final Particle.DustOptions DUST_BLACK = new Particle.DustOptions(Color.fromRGB( 20,   0,  20), 1.3F);
    private static final Particle.DustOptions DUST_DARK  = new Particle.DustOptions(Color.fromRGB( 20,  70,  70), 1.0F);
    private static final Particle.DustOptions DUST_RED   = new Particle.DustOptions(Color.fromRGB(200,  10,  10), 1.0F);

    // ─── Enums ────────────────────────────────────────────────────────────────
    private enum ScarecrowState { COMBAT, CASTING, DEAD }
    private enum ScarecrowAbility {
        FEAR, DRAIN, BLOOM, REAP, FIREBALLS, PHANTOM, CROWSTORM, DARK_WIND, HIGH_JUMP
    }

    // ─── Fields ───────────────────────────────────────────────────────────────
    private final BloodMoonPlugin plugin;
    private final NPC npc;
    private final Location spawnLocation;
    private final Random random = new Random();
    private final Map<ScarecrowAbility, Integer> cooldowns = new EnumMap<>(ScarecrowAbility.class);
    private final List<BukkitRunnable> tasks = new ArrayList<>();
    private final List<Parrot> crows = new ArrayList<>();
    private final List<Location> witherRoseLocations = new ArrayList<>();

    private ScarecrowState state = ScarecrowState.COMBAT;
    private ScarecrowState beforeCast = ScarecrowState.COMBAT;
    private ScarecrowAbility pending;
    private BukkitRunnable controllerTask;
    private Player target;
    private Location lastKnownLocation;
    private int stateTicks;
    private int castTicks;
    private boolean cleaned;
    private boolean deathStarted;

    // Fearmonger: each wheat crop consumed grants +10% damage
    private int fearmongerStacks = 0;

    // ─── Constructor ──────────────────────────────────────────────────────────
    public ScarecrowNPC(BloodMoonPlugin plugin, NPC npc, Location spawnLocation, Player initialTarget) {
        this.plugin = plugin;
        this.npc = npc;
        this.spawnLocation = spawnLocation.clone();
        this.target = initialTarget;
        this.lastKnownLocation = spawnLocation.clone();
        configureNpc();
        startController();
    }

    // ─── Public API ───────────────────────────────────────────────────────────
    public boolean isDead() { return state == ScarecrowState.DEAD || cleaned || deathStarted; }

    public Location getCurrentLocation() {
        LivingEntity e = getLivingEntity();
        return e != null ? e.getLocation() : (lastKnownLocation == null ? spawnLocation.clone() : lastKnownLocation.clone());
    }

    public void onTraitTick() {
        LivingEntity e = getLivingEntity();
        if (e != null) lastKnownLocation = e.getLocation();
    }

    public void onNpcSpawn() {
        LivingEntity e = getLivingEntity();
        if (e != null) applyConfiguredHealth(e);
    }

    public void handleSentinelAttack(SentinelAttackEvent event) {
        if (!(event.getTarget() instanceof Player player) || state == ScarecrowState.DEAD) return;
        target = player;
    }

    public void startDeathSequence() {
        if (deathStarted) return;
        deathStarted = true;
        state = ScarecrowState.DEAD;
        cancelControllerOnly();
        cancelTasks();
        crows.forEach(c -> { if (c.isValid()) c.remove(); });
        crows.clear();
        cleanupWitherRoses();
        Location death = getCurrentLocation();
        World world = death.getWorld();
        if (world != null) {
            world.playSound(death, Sound.ENTITY_SKELETON_DEATH, 1.0F, 0.8F);
            world.spawnParticle(Particle.DUST, death.clone().add(0, 1, 0), 30, 0.6, 0.7, 0.6, 0, DUST_CYAN);
            world.spawnParticle(Particle.SMOKE, death.clone().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.04);
            if (random.nextDouble() <= 0.4) world.dropItemNaturally(death, new ItemStack(Material.WHEAT_SEEDS, 2 + random.nextInt(3)));
            if (random.nextDouble() <= 0.15) world.dropItemNaturally(death, new ItemStack(Material.CARVED_PUMPKIN, 1));
            ExperienceOrb orb = world.spawn(death.clone().add(0, 0.25, 0), ExperienceOrb.class);
            orb.setExperience(40 + random.nextInt(20));
        }
        BukkitRunnable cleanupTask = new BukkitRunnable() {
            @Override public void run() { cleanup(); }
        };
        tasks.add(cleanupTask);
        cleanupTask.runTaskLater(plugin, 60L);
    }

    public void cleanup() {
        if (cleaned) return;
        cleaned = true;
        cancelControllerOnly();
        cancelTasks();
        crows.forEach(c -> { if (c.isValid()) c.remove(); });
        crows.clear();
        cleanupWitherRoses();
        if (npc.isSpawned()) npc.despawn();
        npc.destroy();
        plugin.getNPCManager().unregisterScarecrow(npc.getId());
    }

    // ─── NPC Setup ────────────────────────────────────────────────────────────
    private void configureNpc() {
        npc.data().set("bloodmoon-scarecrow", true);
        npc.data().set("nameplate-visible", false);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        npc.setProtected(false);
        ScarecrowTrait trait = npc.getOrAddTrait(ScarecrowTrait.class);
        trait.bind(this);
        configureSkin();
        configureSentinel();
        if (!npc.isSpawned()) npc.spawn(spawnLocation.clone());
        LivingEntity e = getLivingEntity();
        if (e != null) { applyConfiguredHealth(e); hideNameplate(e); }
    }

    private void configureSkin() {
        String skinName  = plugin.getConfigManager().getScarecrowSkinName();
        String texture   = plugin.getConfigManager().getScarecrowSkinTexture();
        String signature = plugin.getConfigManager().getScarecrowSkinSignature();
        if ((skinName == null || skinName.isBlank()) && (texture == null || texture.isBlank())) return;
        try {
            Class<? extends Trait> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait").asSubclass(Trait.class);
            Trait skinTrait = npc.getOrAddTrait(skinTraitClass);
            skinTraitClass.getMethod("setShouldUpdateSkins", boolean.class).invoke(skinTrait, false);
            skinTraitClass.getMethod("setFetchDefaultSkin",  boolean.class).invoke(skinTrait, false);
            if (texture != null && !texture.isBlank() && signature != null && !signature.isBlank()) {
                skinTraitClass.getMethod("setSkinPersistent", String.class, String.class, String.class)
                              .invoke(skinTrait, skinName, signature, texture);
                return;
            }
            if (skinName != null && !skinName.isBlank()) {
                skinTraitClass.getMethod("setSkinName", String.class, boolean.class).invoke(skinTrait, skinName, true);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("Could not apply scarecrow skin: " + ex.getMessage());
        }
    }

    private void configureSentinel() {
        SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
        s.setInvincible(false);
        s.setHealth(plugin.getConfigManager().getScarecrowHealth());
        s.health = plugin.getConfigManager().getScarecrowHealth();
        s.damage = 4.0D * getFearmongerMultiplier();
        s.respawnTime = -1;
        s.chaseRange = 32.0D;
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
        double hp   = plugin.getConfigManager().getScarecrowHealth();
        var    attr = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) { attr.setBaseValue(hp); entity.setHealth(Math.min(hp, entity.getHealth())); }
    }

    // ─── Combat loop ──────────────────────────────────────────────────────────
    private void startController() {
        controllerTask = new BukkitRunnable() { @Override public void run() { tick(); } };
        controllerTask.runTaskTimer(plugin, 1L, 1L);
    }

    private void tick() {
        if (cleaned || deathStarted) return;
        stateTicks++;
        cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));
        onTraitTick();

        if (state == ScarecrowState.CASTING) { tickCasting(); return; }
        if (state != ScarecrowState.COMBAT) return;

        Player player = ensureTarget(48.0D);
        if (player == null) { player = findNearestPlayer(getCurrentLocation(), 48.0D); target = player; }
        if (player == null) return;

        // Passive: Fearmonger – consume nearby mature wheat crops
        if (stateTicks % 40 == 0) tickFearmonger();

        npc.getNavigator().setTarget(player, true);
        npc.faceLocation(player.getEyeLocation());

        // Ambient skeleton sound
        if (stateTicks % 60 == 0) {
            Location loc = getCurrentLocation();
            if (loc.getWorld() != null) loc.getWorld().playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.6F, 0.9F + random.nextFloat() * 0.2F);
        }

        if (stateTicks % 28 == 0) {
            ScarecrowAbility ability = chooseAbility();
            if (ability != null) startCasting(ability);
        }
    }

    private void tickCasting() {
        if (stateTicks < castTicks) return;
        ScarecrowAbility ability = pending;
        pending = null;
        state = beforeCast;
        stateTicks = 0;
        castTicks = 0;
        if (ability != null) executeAbility(ability);
    }

    private void startCasting(ScarecrowAbility ability) {
        if (state == ScarecrowState.CASTING || state == ScarecrowState.DEAD) return;
        pending    = ability;
        beforeCast = state;
        state      = ScarecrowState.CASTING;
        stateTicks = 0;
        castTicks  = switch (ability) {
            case DRAIN, CROWSTORM -> 30;
            case HIGH_JUMP        -> 10;
            default               -> 20;
        };
        Location loc = getCurrentLocation();
        if (loc.getWorld() != null) loc.getWorld().playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 0.7F, 0.65F + random.nextFloat() * 0.3F);
    }

    private ScarecrowAbility chooseAbility() {
        List<ScarecrowAbility> pool = new ArrayList<>();
        for (ScarecrowAbility a : ScarecrowAbility.values()) {
            if (cooldowns.getOrDefault(a, 0) > 0) continue;
            int weight = switch (a) {
                case FEAR, DRAIN, CROWSTORM -> 1;
                case BLOOM, REAP            -> 2;
                case FIREBALLS, DARK_WIND   -> 3;
                case PHANTOM, HIGH_JUMP     -> 2;
            };
            for (int i = 0; i < weight; i++) pool.add(a);
        }
        return pool.isEmpty() ? null : pool.get(random.nextInt(pool.size()));
    }

    private void executeAbility(ScarecrowAbility ability) {
        switch (ability) {
            case FEAR      -> castFear();
            case DRAIN     -> castDrain();
            case BLOOM     -> castBloom();
            case REAP      -> castReap();
            case FIREBALLS -> castFireballs();
            case PHANTOM   -> castPhantom();
            case CROWSTORM -> castCrowstorm();
            case DARK_WIND -> castDarkWind();
            case HIGH_JUMP -> castHighJump();
        }
        cooldowns.put(ability, switch (ability) {
            case FEAR      -> 300;
            case DRAIN     -> 260;
            case BLOOM     -> 180;
            case REAP      -> 200;
            case FIREBALLS -> 120;
            case PHANTOM   -> 220;
            case CROWSTORM -> 500;
            case DARK_WIND -> 140;
            case HIGH_JUMP -> 100;
        });
    }

    // ─── ABILITIES ────────────────────────────────────────────────────────────

    /**
     * Fear: fires a slow cyan/black orb. On contact: Blindness, Darkness, Slowness V,
     * Mining Fatigue, and places a carved pumpkin on the target's head for 4 seconds.
     */
    private void castFear() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        Location start = caster.getEyeLocation();
        world.playSound(start, Sound.ENTITY_SKELETON_HURT, 0.6F, 0.5F);
        world.spawnParticle(Particle.DUST, start, 10, 0.2, 0.2, 0.2, 0, DUST_CYAN);

        Vector dir = tgt.getEyeLocation().toVector().subtract(start.toVector()).normalize().multiply(0.28D);
        final Location[] pos = { start.clone() };

        BukkitRunnable orb = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 110 || isDead()) { cancel(); return; }
                pos[0].add(dir);
                world.spawnParticle(Particle.DUST, pos[0], 5, 0.15, 0.15, 0.15, 0, DUST_CYAN);
                world.spawnParticle(Particle.DUST, pos[0], 3, 0.10, 0.10, 0.10, 0, DUST_BLACK);
                world.spawnParticle(Particle.SMOKE, pos[0], 2, 0.04, 0.04, 0.04, 0.01);
                for (Player p : world.getPlayers()) {
                    if (p.isDead() || p.getLocation().distanceSquared(pos[0]) > 1.2D) continue;
                    applyFear(p);
                    world.playSound(pos[0], Sound.ENTITY_WITHER_AMBIENT, 0.5F, 1.8F);
                    cancel();
                    return;
                }
            }
        };
        tasks.add(orb);
        orb.runTaskTimer(plugin, 0L, 1L);
    }

    private void applyFear(Player player) {
        World world = player.getWorld();
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,    80, 1, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,     80, 1, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,     80, 4, false, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 80, 2, false, true, true));

        // Pumpkin head – save old helmet and restore after 4 seconds
        ItemStack oldHelmet = player.getInventory().getHelmet();
        player.getInventory().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        world.playSound(player.getLocation(), Sound.BLOCK_PUMPKIN_CARVE, 0.8F, 1.0F);
        world.spawnParticle(Particle.DUST, player.getLocation().add(0, 2, 0), 14, 0.3, 0.2, 0.3, 0, DUST_CYAN);

        BukkitRunnable restore = new BukkitRunnable() {
            @Override public void run() {
                if (player.isOnline()) player.getInventory().setHelmet(oldHelmet);
            }
        };
        tasks.add(restore);
        restore.runTaskLater(plugin, 80L);
    }

    /**
     * Drain: leap toward target, then connect up to 7 nearby players with cyan/black lines.
     * Each connected player is drained for half a heart constantly. Scarecrow heals from it.
     * Lasts 5 seconds; connection breaks when a player leaves 7-block range.
     */
    private void castDrain() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();

        // Leap toward target
        Vector toTarget = tgt.getLocation().toVector().subtract(caster.getLocation().toVector()).normalize();
        caster.setVelocity(toTarget.multiply(0.6D).setY(0.7D));
        world.playSound(caster.getLocation(), Sound.ENTITY_SKELETON_STEP, 1.0F, 0.55F);
        world.spawnParticle(Particle.DUST, caster.getLocation().add(0, 1, 0), 12, 0.4, 0.4, 0.4, 0, DUST_CYAN);

        BukkitRunnable drain = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 100 || isDead()) { cancel(); return; }

                Location casterLoc = getCurrentLocation().add(0, 1.2, 0);
                List<Player> connected = new ArrayList<>();
                for (Player p : world.getPlayers()) {
                    if (!p.isDead() && p.isOnline()
                            && p.getLocation().distanceSquared(getCurrentLocation()) <= 49.0D) {
                        connected.add(p);
                        if (connected.size() >= 7) break;
                    }
                }
                if (connected.isEmpty()) { cancel(); return; }

                double totalHeal = 0;
                for (Player p : connected) {
                    // Draw both dust colors for a layered cyan/black beam
                    drawLine(p.getEyeLocation(), casterLoc, DUST_CYAN);
                    drawLine(p.getEyeLocation(), casterLoc, DUST_BLACK);
                    // Drain half a heart every 4 ticks (~2.5 per second)
                    if (t % 4 == 0) {
                        p.damage(1.0D);
                        totalHeal += 0.5D;
                    }
                }

                if (totalHeal > 0) {
                    LivingEntity e = getLivingEntity();
                    if (e != null) {
                        var hpAttr = e.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                        double maxHp = hpAttr != null ? hpAttr.getValue() : 40.0D;
                        e.setHealth(Math.min(maxHp, e.getHealth() + totalHeal));
                    }
                }

                if (t == 1) world.playSound(casterLoc, Sound.ENTITY_WITHER_AMBIENT, 0.6F, 1.4F);
            }
        };
        tasks.add(drain);
        drain.runTaskTimer(plugin, 6L, 1L);
    }

    /**
     * Bloom: scatter wither roses in random positions around the target.
     * Roses disappear after 7 seconds.
     */
    private void castBloom() {
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = tgt.getWorld();
        world.playSound(tgt.getLocation(), Sound.BLOCK_GRASS_PLACE, 0.8F, 0.5F);
        world.spawnParticle(Particle.DUST, tgt.getLocation().add(0, 0.5, 0), 22, 1.6, 0.3, 1.6, 0, DUST_BLACK);

        List<Location> placed = new ArrayList<>();
        for (int i = 0; i < 8 + random.nextInt(4); i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double dist  = 1.2D + random.nextDouble() * 3.8D;
            int bx = tgt.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int bz = tgt.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int by = world.getHighestBlockYAt(bx, bz);
            Block ground = world.getBlockAt(bx, by - 1, bz);
            Block rose   = world.getBlockAt(bx, by, bz);
            if (ground.getType().isSolid() && rose.getType() == Material.AIR) {
                rose.setType(Material.WITHER_ROSE);
                placed.add(rose.getLocation());
                witherRoseLocations.add(rose.getLocation());
                world.spawnParticle(Particle.DUST, rose.getLocation().add(0.5, 0.6, 0.5), 4, 0.2, 0.2, 0.2, 0, DUST_BLACK);
            }
        }

        BukkitRunnable remove = new BukkitRunnable() {
            @Override public void run() {
                for (Location l : placed) {
                    if (l.getBlock().getType() == Material.WITHER_ROSE) l.getBlock().setType(Material.AIR);
                    witherRoseLocations.remove(l);
                }
            }
        };
        tasks.add(remove);
        remove.runTaskLater(plugin, 140L); // 7 seconds
    }

    /**
     * Reap: half-moon sweep in front of the scarecrow (5-block radius).
     * Targets below 15% HP are instantly killed. Others are slowed by 50% for 2 seconds.
     */
    private void castReap() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        World world = caster.getWorld();
        Location center = caster.getEyeLocation();

        world.playSound(center, Sound.ITEM_TRIDENT_THROW, 0.9F, 0.6F);
        world.playSound(center, Sound.ENTITY_SKELETON_AMBIENT, 0.7F, 0.7F);

        // Forward direction (horizontal)
        Vector forward = caster.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.001D) forward = new Vector(1, 0, 0);
        forward.normalize();
        Vector perp = new Vector(-forward.getZ(), 0, forward.getX());

        // Draw the half-moon arc with particles
        for (double t = -Math.PI / 2; t <= Math.PI / 2; t += Math.PI / 20) {
            Vector arcDir = forward.clone().multiply(Math.cos(t)).add(perp.clone().multiply(Math.sin(t)));
            for (double r = 0.8D; r <= 5.0D; r += 0.6D) {
                Location tip = center.clone().add(arcDir.clone().multiply(r)).add(0, -0.3, 0);
                world.spawnParticle(Particle.CRIT, tip, 1, 0, 0, 0, 0.02);
                if (r > 3.5D) world.spawnParticle(Particle.SWEEP_ATTACK, tip, 1, 0, 0, 0, 0);
            }
        }

        for (Player p : world.getPlayers()) {
            if (p.isDead()) continue;
            Location pLoc = p.getLocation().add(0, 1, 0);
            if (pLoc.distanceSquared(center) > 25.0D) continue; // 5 block radius

            // Half-moon: must be in front hemisphere
            Vector toPlayer = pLoc.toVector().subtract(center.toVector()).setY(0).normalize();
            if (forward.dot(toPlayer) < 0.0D) continue;

            var hpAttr = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            double maxHp = hpAttr != null ? hpAttr.getValue() : 20.0D;

            if (p.getHealth() / maxHp < 0.15D) {
                // Finishing blow
                p.setHealth(0);
                world.playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.9F, 0.5F);
                world.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0, DUST_RED);
            } else {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));
                p.damage(5.0D + fearmongerStacks, caster);
                world.spawnParticle(Particle.CRIT, p.getLocation().add(0, 1, 0), 8, 0.3, 0.3, 0.3, 0.08);
            }
        }
    }

    /**
     * Fireballs: spit 1–4 small fireballs at the target, burning for 3 seconds each.
     */
    private void castFireballs() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        int count = 1 + random.nextInt(4);
        world.playSound(caster.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.8F, 0.9F);

        BukkitRunnable shoot = new BukkitRunnable() {
            int fired = 0;
            @Override public void run() {
                if (fired >= count || isDead()) { cancel(); return; }
                fired++;
                Location from = getCurrentLocation().add(0, 1.5, 0);
                if (from.getWorld() == null) return;

                Vector dir = tgt.getEyeLocation().toVector().subtract(from.toVector()).normalize();
                dir.add(new Vector(
                    (random.nextDouble() - 0.5D) * 0.10D,
                    (random.nextDouble() - 0.5D) * 0.06D,
                    (random.nextDouble() - 0.5D) * 0.10D));

                SmallFireball fb = from.getWorld().spawn(from, SmallFireball.class);
                fb.setShooter(caster);
                fb.setDirection(dir);
                fb.setYield(0.5F);
                from.getWorld().playSound(from, Sound.ENTITY_BLAZE_SHOOT, 0.5F, 1.2F + random.nextFloat() * 0.3F);
            }
        };
        tasks.add(shoot);
        shoot.runTaskTimer(plugin, 0L, 9L);
    }

    /**
     * Phantom: teleport behind the player; make step sounds. If the player turns to look
     * at the scarecrow it vanishes instantly. After 3 seconds unseen, delivers a surprise strike.
     */
    private void castPhantom() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        Location original = caster.getLocation().clone();

        // Position directly behind the player
        Vector behind = tgt.getLocation().getDirection().normalize().multiply(-2.8D);
        Location behindLoc = tgt.getLocation().clone().add(behind);
        behindLoc.setY(world.getHighestBlockYAt(behindLoc) + 1.0D);
        behindLoc.setYaw(tgt.getLocation().getYaw() + 180);
        behindLoc.setPitch(0);
        npc.teleport(behindLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        world.playSound(behindLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.3F, 1.8F);

        BukkitRunnable phantom = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 65 || isDead()) { cancel(); return; }
                Location sc = getCurrentLocation();

                // Occasional creepy step sounds
                if (t % 12 == 0) world.playSound(sc, Sound.ENTITY_SKELETON_STEP, 0.7F, 0.85F + random.nextFloat() * 0.2F);

                if (!tgt.isOnline() || tgt.isDead()) { cancel(); return; }

                // Check if player is looking at the scarecrow (dot product > 0.80 = ~37° cone)
                Vector toScarecrow = sc.toVector().subtract(tgt.getEyeLocation().toVector()).normalize();
                Vector playerDir   = tgt.getEyeLocation().getDirection().normalize();
                double dist2       = tgt.getLocation().distanceSquared(sc);

                if (playerDir.dot(toScarecrow) > 0.80D && dist2 < 100.0D) {
                    // Player spots the scarecrow – vanish
                    world.spawnParticle(Particle.SMOKE, sc.clone().add(0, 1, 0), 20, 0.3, 0.4, 0.3, 0.03);
                    world.spawnParticle(Particle.DUST, sc.clone().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0, DUST_CYAN);
                    world.playSound(sc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.4F, 1.6F);
                    npc.teleport(original, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    cancel();
                    return;
                }

                // Not spotted after 3 seconds → surprise attack
                if (t >= 60 && tgt.isOnline() && !tgt.isDead()) {
                    tgt.damage(8.0D + fearmongerStacks * 0.8D, caster);
                    world.playSound(sc, Sound.ENTITY_SKELETON_HURT, 0.9F, 0.7F);
                    world.spawnParticle(Particle.CRIT, tgt.getLocation().add(0, 1, 0), 14, 0.3, 0.3, 0.3, 0.08);
                    npc.teleport(original, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    cancel();
                }
            }
        };
        tasks.add(phantom);
        phantom.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Crowstorm: 30 gray parrots orbit the scarecrow. Players inside the 6-block radius
     * take heavy constant damage (similar to Fiddlesticks' Crowstorm).
     */
    private void castCrowstorm() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        World world = caster.getWorld();
        Location center = caster.getLocation().clone();
        world.playSound(center, Sound.ENTITY_PARROT_FLY, 1.0F, 0.6F);
        world.spawnParticle(Particle.DUST, center.clone().add(0, 1, 0), 30, 1.2, 0.5, 1.2, 0, DUST_BLACK);

        List<Parrot> storm = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            double a      = (Math.PI * 2.0D * i) / 30.0D;
            double radius = 4.5D + random.nextDouble() * 1.5D;
            Location spawnLoc = center.clone().add(
                Math.cos(a) * radius,
                1.5D + random.nextDouble() * 3.0D,
                Math.sin(a) * radius);
            try {
                Parrot parrot = world.spawn(spawnLoc, Parrot.class, p -> {
                    p.setVariant(Parrot.Variant.GRAY);
                    p.setInvulnerable(true);
                    p.setSilent(true);
                    p.setAI(false);
                });
                storm.add(parrot);
                crows.add(parrot);
            } catch (Exception ignored) {}
        }

        BukkitRunnable crowTask = new BukkitRunnable() {
            int t = 0;
            double angle = 0;
            @Override public void run() {
                t++;
                if (t > 200 || isDead()) {
                    storm.forEach(p -> { if (p.isValid()) p.remove(); });
                    crows.removeAll(storm);
                    cancel();
                    return;
                }
                angle += 0.07D;
                Location current = getCurrentLocation();

                for (int i = 0; i < storm.size(); i++) {
                    Parrot parrot = storm.get(i);
                    if (!parrot.isValid()) continue;
                    double a      = angle + (Math.PI * 2.0D * i) / storm.size();
                    double height = 2.2D + Math.sin(angle * 2.5D + i * 0.4D) * 1.2D;
                    parrot.teleport(current.clone().add(Math.cos(a) * 5.0D, height, Math.sin(a) * 5.0D));
                }

                // Damage players inside the 6-block radius every 5 ticks
                if (t % 5 == 0) {
                    for (Player p : world.getPlayers()) {
                        if (p.isDead() || p.getLocation().distanceSquared(current) > 36.0D) continue;
                        p.damage(2.5D + fearmongerStacks * 0.2D);
                        world.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 4, 0.3, 0.3, 0.3, 0, DUST_BLACK);
                    }
                }

                if (t % 20 == 0) world.playSound(current, Sound.ENTITY_PARROT_FLY, 0.5F, 0.65F + random.nextFloat() * 0.35F);
            }
        };
        tasks.add(crowTask);
        crowTask.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Dark Wind: cyan/black bouncing projectile. Targets nearest enemy on each bounce;
     * up to 5 bounces. Can re-target the same player.
     */
    private void castDarkWind() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();
        world.playSound(caster.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.6F, 1.6F);

        final double speed = 0.55D;
        final int[] bouncesLeft = { 5 };
        Location startPos = caster.getEyeLocation().clone();
        Vector startVel = tgt.getEyeLocation().toVector().subtract(startPos.toVector()).normalize().multiply(speed);

        BukkitRunnable proj = new BukkitRunnable() {
            final Location pos = startPos.clone();
            Vector v = startVel.clone();
            int t = 0;
            @Override public void run() {
                t++;
                if (t > 130 || isDead() || bouncesLeft[0] < 0) { cancel(); return; }
                pos.add(v);

                world.spawnParticle(Particle.DUST,  pos, 5, 0.12, 0.12, 0.12, 0, DUST_CYAN);
                world.spawnParticle(Particle.DUST,  pos, 3, 0.08, 0.08, 0.08, 0, DUST_BLACK);
                world.spawnParticle(Particle.SMOKE, pos, 1, 0.03, 0.03, 0.03, 0.01);

                for (Player p : world.getPlayers()) {
                    if (p.isDead() || p.getLocation().distanceSquared(pos) > 1.5D) continue;

                    p.damage(4.5D + fearmongerStacks * 0.5D, caster);
                    world.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0, DUST_CYAN);
                    world.playSound(pos, Sound.ENTITY_WITHER_HURT, 0.5F, 1.8F);

                    bouncesLeft[0]--;
                    if (bouncesLeft[0] < 0) { cancel(); return; }

                    // Find nearest player to bounce to (re-targeting same player allowed)
                    Player nearest = null;
                    double nearestDist = 16.0D; // 4 blocks
                    for (Player other : world.getPlayers()) {
                        if (other.isDead()) continue;
                        double d = other.getLocation().distanceSquared(pos);
                        if (d < nearestDist) { nearestDist = d; nearest = other; }
                    }
                    if (nearest != null) {
                        v = nearest.getEyeLocation().toVector().subtract(pos.toVector()).normalize().multiply(speed);
                    } else {
                        cancel();
                        return;
                    }
                    break;
                }
            }
        };
        tasks.add(proj);
        proj.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * High Jump: launches the scarecrow toward the target.
     * If Drain is available, queues it automatically after landing.
     */
    private void castHighJump() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Player tgt = target;
        if (tgt == null || !tgt.isOnline()) return;
        World world = caster.getWorld();

        world.playSound(caster.getLocation(), Sound.ENTITY_SKELETON_STEP, 0.9F, 0.5F);
        world.spawnParticle(Particle.DUST, caster.getLocation().add(0, 0.5, 0), 14, 0.35, 0.2, 0.35, 0, DUST_CYAN);

        Vector toTarget = tgt.getLocation().toVector().subtract(caster.getLocation().toVector());
        toTarget.setY(0);
        if (toTarget.lengthSquared() > 0.001D) toTarget.normalize();
        caster.setVelocity(toTarget.multiply(0.75D).setY(1.1D));

        // Auto-chain into Drain if off cooldown
        if (cooldowns.getOrDefault(ScarecrowAbility.DRAIN, 0) == 0) {
            BukkitRunnable landing = new BukkitRunnable() {
                int waited = 0;
                @Override public void run() {
                    waited++;
                    if (waited > 50 || isDead()) { cancel(); return; }
                    LivingEntity e = getLivingEntity();
                    if (e != null && e.isOnGround()) {
                        startCasting(ScarecrowAbility.DRAIN);
                        cancel();
                    }
                }
            };
            tasks.add(landing);
            landing.runTaskTimer(plugin, 6L, 2L);
        }
    }

    /**
     * Fearmonger (passive): every 2 seconds, search nearby blocks for fully grown wheat.
     * Each consumed crop heals 1 HP and adds 10% damage via Sentinel damage scaling.
     */
    private void tickFearmonger() {
        LivingEntity caster = getLivingEntity();
        if (caster == null) return;
        Location loc  = caster.getLocation();
        World world   = loc.getWorld();
        if (world == null) return;

        int radius = 6;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block block = world.getBlockAt(loc.getBlockX() + dx, loc.getBlockY() + dy, loc.getBlockZ() + dz);
                    if (block.getType() != Material.WHEAT) continue;
                    if (!(block.getBlockData() instanceof Ageable ageable)) continue;
                    if (ageable.getAge() < ageable.getMaximumAge()) continue;

                    block.setType(Material.AIR);
                    fearmongerStacks++;

                    // Scale sentinel damage
                    SentinelTrait s = npc.getOrAddTrait(SentinelTrait.class);
                    s.damage = 4.0D * getFearmongerMultiplier();

                    var hpAttr = caster.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    double maxHp = hpAttr != null ? hpAttr.getValue() : 40.0D;
                    caster.setHealth(Math.min(maxHp, caster.getHealth() + 2.0D));

                    world.spawnParticle(Particle.DUST, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, DUST_CYAN);
                    world.playSound(block.getLocation(), Sound.BLOCK_CROP_BREAK, 0.7F, 1.2F);
                    return; // one crop per check
                }
            }
        }
    }

    /** Returns the total damage multiplier from Fearmonger stacks. */
    public double getFearmongerMultiplier() {
        return 1.0D + fearmongerStacks * 0.10D;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private LivingEntity getLivingEntity() {
        if (!npc.isSpawned() || npc.getEntity() == null) return null;
        return npc.getEntity() instanceof LivingEntity le ? le : null;
    }

    private Player ensureTarget(double radius) {
        if (target != null && target.isOnline() && !target.isDead()
                && target.getWorld() == getCurrentLocation().getWorld()
                && target.getLocation().distanceSquared(getCurrentLocation()) <= radius * radius) {
            return target;
        }
        target = findNearestPlayer(getCurrentLocation(), radius);
        return target;
    }

    private Player findNearestPlayer(Location from, double radius) {
        Player nearest = null;
        double best = radius * radius;
        if (from.getWorld() == null) return null;
        for (Player p : from.getWorld().getPlayers()) {
            if (p.isDead() || !p.isOnline()) continue;
            double dist = p.getLocation().distanceSquared(from);
            if (dist < best) { best = dist; nearest = p; }
        }
        return nearest;
    }

    private void cancelControllerOnly() {
        if (controllerTask != null) {
            try { controllerTask.cancel(); } catch (Exception ignored) {}
            controllerTask = null;
        }
    }

    private void cancelTasks() {
        for (BukkitRunnable t : tasks) { try { t.cancel(); } catch (Exception ignored) {} }
        tasks.clear();
    }

    private void cleanupWitherRoses() {
        for (Location l : new ArrayList<>(witherRoseLocations)) {
            if (l.getBlock().getType() == Material.WITHER_ROSE) l.getBlock().setType(Material.AIR);
        }
        witherRoseLocations.clear();
    }

    /** Draws a particle line between two locations. */
    private void drawLine(Location a, Location b, Particle.DustOptions dust) {
        if (a.getWorld() == null || a.getWorld() != b.getWorld()) return;
        Vector step = b.toVector().subtract(a.toVector());
        double len  = step.length();
        if (len < 0.01D) return;
        step.normalize().multiply(0.4D);
        Location pos = a.clone();
        for (double d = 0; d < len; d += 0.4D) {
            pos.getWorld().spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, dust);
            pos.add(step);
        }
    }
}
