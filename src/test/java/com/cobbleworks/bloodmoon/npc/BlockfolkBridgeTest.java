package com.cobbleworks.bloodmoon.npc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockfolkBridgeTest {
    @Test
    void movementFixRequiresBlockfolkOneFourOrLater() {
        assertFalse(BlockfolkBridge.supportsMovementFix("1.3.0"));
        assertFalse(BlockfolkBridge.supportsMovementFix("invalid"));
        assertFalse(BlockfolkBridge.supportsMovementFix(null));
        assertTrue(BlockfolkBridge.supportsMovementFix("1.4.0"));
        assertTrue(BlockfolkBridge.supportsMovementFix("1.10.0"));
        assertTrue(BlockfolkBridge.supportsMovementFix("2.0.0"));
    }
    @Test
    void missingApiGivesActionableVersionRequirement() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BlockfolkBridge.call(new TestHandle(), "missing", new Class<?>[0]));
        assertTrue(error.getMessage().contains("Blockfolk 1.4.0"));
    }

    @Test
    void propagatesRuntimeFailuresFromBlockfolkWithoutHidingTheirCause() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> BlockfolkBridge.call(new TestHandle(), "fail", new Class<?>[0]));
        assertEquals("NPC owner disabled", error.getMessage());
    }

    @Test
    void skinAndCastingAnimationsReachTheExternalNpcHandle() {
        TestHandle handle = new TestHandle();
        BlockfolkNpc npc = new BlockfolkNpc(null, 1, handle);
        npc.setSkin("Vampire", "texture", "signature");
        npc.animate("START_USE_OFFHAND_ITEM");
        assertEquals("Vampire:texture:signature", handle.skin);
        assertEquals("START_USE_OFFHAND_ITEM", handle.animation);
    }

    public static final class TestHandle {
        private String skin;
        private String animation;
        public void fail() { throw new IllegalArgumentException("NPC owner disabled"); }
        public void setSkin(String name, String texture, String signature) { skin = name + ":" + texture + ":" + signature; }
        public void animate(String animation) { this.animation = animation; }
    }
}
