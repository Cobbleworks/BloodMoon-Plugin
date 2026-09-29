package com.cobbleworks.bloodmoon.npc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Resolves Blockfolk's public transient API without bundling a second copy of Blockfolk. */
public final class BlockfolkBridge {
    private final Plugin owner;
    private final Object service;
    private final Method create;
    private final Map<Integer, BlockfolkNpc> npcs = new HashMap<>();
    private final Map<UUID, BlockfolkNpc> byEntity = new HashMap<>();
    private int nextId;

    public BlockfolkBridge(Plugin owner) {
        this.owner = owner;
        Plugin blockfolk = owner.getServer().getPluginManager().getPlugin("Blockfolk");
        if (blockfolk == null || !blockfolk.isEnabled()) throw new IllegalStateException("Blockfolk is required.");
        service = call(blockfolk, "getTransientNpcService", new Class<?>[0]);
        if (service == null) throw new IllegalStateException("Blockfolk's transient NPC service is unavailable.");
        try { create = service.getClass().getMethod("create", Plugin.class, String.class); }
        catch (ReflectiveOperationException ex) { throw new IllegalStateException("Install Blockfolk 1.3.0 or newer.", ex); }
        owner.getServer().getScheduler().runTaskTimer(owner,
                () -> java.util.List.copyOf(npcs.values()).forEach(BlockfolkNpc::tick), 1L, 1L);
    }

    public BlockfolkNpc createNpc(String name) {
        Object handle = invoke(create, service, owner, name);
        BlockfolkNpc npc = new BlockfolkNpc(this, ++nextId, handle);
        npcs.put(npc.getId(), npc);
        return npc;
    }

    public BlockfolkNpc getNPC(Entity entity) { return entity == null ? null : byEntity.get(entity.getUniqueId()); }
    public boolean isNPC(Entity entity) { return getNPC(entity) != null; }
    void index(BlockfolkNpc npc, Entity entity) { if (entity != null) byEntity.put(entity.getUniqueId(), npc); }
    void unindex(BlockfolkNpc npc) { byEntity.values().removeIf(value -> value == npc); }
    void remove(BlockfolkNpc npc) { unindex(npc); npcs.remove(npc.getId()); }
    public void shutdown() { java.util.List.copyOf(npcs.values()).forEach(BlockfolkNpc::destroy); }

    static Object call(Object target, String name, Class<?>[] parameters, Object... args) {
        try { return invoke(target.getClass().getMethod(name, parameters), target, args); }
        catch (NoSuchMethodException ex) { throw new IllegalStateException("Install Blockfolk 1.3.0 or newer: missing " + name, ex); }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Blockfolk operation failed: " + method.getName(), ex.getCause());
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Cannot access Blockfolk API.", ex); }
    }
}
