package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaManagerTest {
    @Test
    void soloProjectileSweepRequiresCurrentTrackedIdentity() {
        assertTrue(ArenaManager.isTrackedSoloProjectile(true, true, true));
        assertFalse(ArenaManager.isTrackedSoloProjectile(true, true, false));
        assertFalse(ArenaManager.isTrackedSoloProjectile(true, false, true));
        assertFalse(ArenaManager.isTrackedSoloProjectile(false, true, true));
    }

    @Test
    void deathScreenKeepsRestoreJournalForRespawn() {
        assertTrue(ArenaManager.canRestorePlayerImmediately(true));
        assertFalse(ArenaManager.canRestorePlayerImmediately(false));
    }
}
