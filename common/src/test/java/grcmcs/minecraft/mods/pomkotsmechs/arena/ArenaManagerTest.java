package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void mountHelpExplainsArenaMechLoss() {
        assertTrue(ArenaManager.MOUNT_HELP_TEXT.contains(
                "In SOLO and DUEL, losing it means defeat."));
        assertTrue(ArenaManager.MOUNT_HELP_TEXT.contains(
                "Outside those modes, mech destruction ejects you."));
        assertFalse(ArenaManager.MOUNT_HELP_TEXT.contains(
                "You only lose when YOU die"));
    }

    @Test
    void garageBuildNumbersArePublicOneThroughSix() {
        assertEquals(0, ArenaManager.normalizeGarageBuild(1));
        assertEquals(5, ArenaManager.normalizeGarageBuild(6));
        assertEquals(-1, ArenaManager.normalizeGarageBuild(0));
        assertEquals(-1, ArenaManager.normalizeGarageBuild(7));
    }

    @Test
    void soloGarageReadinessFailsClosedWithoutACustomMech() {
        assertFalse(ArenaManager.prepareSoloGarageBuild(null));
    }

    @Test
    void soloDeploymentCollisionGateRejectsBlocksAndNonPlayerObstructions() {
        assertTrue(ArenaManager.soloDeploymentCollisionFree(false, false));
        assertFalse(ArenaManager.soloDeploymentCollisionFree(true, false));
        assertFalse(ArenaManager.soloDeploymentCollisionFree(false, true));
        assertFalse(ArenaManager.soloDeploymentCollisionFree(true, true));
    }

    @Test
    void soloDeathClassificationRetainsCauseWithoutChangingDuelOrRoyale() {
        assertEquals(AshenSpanMissionModel.DefeatReason.PLAYER_DESTROYED,
                ArenaManager.classifySoloFighterDeath(Mode.SOLO, false, false, true));
        assertEquals(AshenSpanMissionModel.DefeatReason.MECH_DESTROYED,
                ArenaManager.classifySoloFighterDeath(Mode.SOLO, false, true, false));

        assertNull(ArenaManager.classifySoloFighterDeath(Mode.SOLO, true, false, false));
        assertNull(ArenaManager.classifySoloFighterDeath(Mode.DUEL, false, true, false));
        assertNull(ArenaManager.classifySoloFighterDeath(Mode.ROYALE, false, true, false));
    }

    @Test
    void stoppingDuringEndingCannotOverwriteVictoryOrDefeat() {
        assertTrue(ArenaManager.soloStopChangesOutcome(ArenaState.ACTIVE));
        assertTrue(ArenaManager.soloStopChangesOutcome(ArenaState.COUNTDOWN));
        assertFalse(ArenaManager.soloStopChangesOutcome(ArenaState.ENDING));
        assertFalse(ArenaManager.soloStopChangesOutcome(ArenaState.IDLE));
    }
}
