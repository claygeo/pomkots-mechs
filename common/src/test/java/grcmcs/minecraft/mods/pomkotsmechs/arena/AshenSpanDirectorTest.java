package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AshenSpanDirectorTest {
    @Test
    void containmentUsesCompleteMechFootprint() {
        var bounds = AshenSpanDefinition.PLAYABLE_BOUNDS;
        assertTrue(AshenSpanDirector.containsAabb(bounds,
                new AABB(-168, 60, -56, -167, 66, -55)));
        assertTrue(AshenSpanDirector.containsAabb(bounds,
                new AABB(166, 60, 54, 168, 66, 56)));
        assertFalse(AshenSpanDirector.containsAabb(bounds,
                new AABB(167, 60, 0, 170, 66, 3)));
        assertFalse(AshenSpanDirector.containsAabb(bounds,
                new AABB(0, 60, -59, 3, 66, -55)));
    }

    @Test
    void revealShuttersMatchThePhysicallyProvenAssetContract() {
        assertEquals(List.of(
                        new AshenSpanDefinition.BlockVolume(-151, -151, 83, 89, -17, -11),
                        new AshenSpanDefinition.BlockVolume(-151, -151, 83, 89, 11, 17),
                        new AshenSpanDefinition.BlockVolume(-153, -153, 83, 89, -3, 3)),
                AshenSpanDirector.revealShutters(AshenSpanDefinition.PhaseId.DROP_DECK));
        assertEquals(List.of(
                        new AshenSpanDefinition.BlockVolume(-113, -113, 65, 78, -28, -19),
                        new AshenSpanDefinition.BlockVolume(-113, -113, 65, 78, 19, 28),
                        new AshenSpanDefinition.BlockVolume(-97, -97, 65, 78, -5, 5)),
                AshenSpanDirector.revealShutters(AshenSpanDefinition.PhaseId.FREIGHT_CANYON));
        assertEquals(List.of(
                        new AshenSpanDefinition.BlockVolume(-64, -64, 73, 78, -5, 6)),
                AshenSpanDirector.revealShutters(AshenSpanDefinition.PhaseId.WEST_SPAN));
        assertEquals(List.of(
                        new AshenSpanDefinition.BlockVolume(0, 1, 73, 78, -13, 14)),
                AshenSpanDirector.revealShutters(AshenSpanDefinition.PhaseId.EAST_SPAN));
        assertEquals(List.of(
                        new AshenSpanDefinition.BlockVolume(45, 45, 73, 82, -23, -8),
                        new AshenSpanDefinition.BlockVolume(45, 45, 73, 82, 8, 23)),
                AshenSpanDirector.revealShutters(
                        AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS));
        assertTrue(AshenSpanDirector.revealShutters(
                AshenSpanDefinition.PhaseId.GATEKEEPER).isEmpty());
    }

    @Test
    void exactSocketsNeedOcclusionButRecoveryCanUseOutOfConeAnchors() {
        Vec3 forward = new Vec3(1.0D, 0.0D, 0.0D);
        assertFalse(AshenSpanDirector.rejectNormalPlacement(
                true, forward, new Vec3(20.0D, 0.0D, 0.0D), false));
        assertTrue(AshenSpanDirector.rejectNormalPlacement(
                true, forward, new Vec3(-20.0D, 0.0D, 0.0D), true));
        assertTrue(AshenSpanDirector.rejectNormalPlacement(
                false, forward, new Vec3(20.0D, 0.0D, 0.0D), true));
        assertFalse(AshenSpanDirector.rejectNormalPlacement(
                false, forward, new Vec3(-20.0D, 0.0D, 0.0D), true));
        assertFalse(AshenSpanDirector.rejectNormalPlacement(
                false, forward, new Vec3(20.0D, 0.0D, 0.0D), false));
    }

    @Test
    void phaseSixUsesTheLockedPlayerFallbackOnlyAfterActivation() {
        assertFalse(AshenSpanDirector.requiresPowerDeckFallback(
                AshenSpanMissionModel.Status.WAITING_FOR_TRIGGER,
                AshenSpanDefinition.PhaseId.POWER_DECK, 60.0D, 73.0D, 28.0D));
        assertFalse(AshenSpanDirector.requiresPowerDeckFallback(
                AshenSpanMissionModel.Status.ACTIVE,
                AshenSpanDefinition.PhaseId.POWER_DECK, 92.5D, 73.0D, 0.5D));
        assertTrue(AshenSpanDirector.requiresPowerDeckFallback(
                AshenSpanMissionModel.Status.ACTIVE,
                AshenSpanDefinition.PhaseId.POWER_DECK, 92.5D, 48.0D, 0.5D));
        assertTrue(AshenSpanDirector.requiresPowerDeckFallback(
                AshenSpanMissionModel.Status.ACTIVE,
                AshenSpanDefinition.PhaseId.POWER_DECK, 79.9D, 73.0D, 0.5D));
        assertFalse(AshenSpanDirector.requiresPowerDeckFallback(
                AshenSpanMissionModel.Status.ACTIVE,
                AshenSpanDefinition.PhaseId.GATEKEEPER, 60.0D, 48.0D, 0.0D));
    }

    @Test
    void recoveryExhaustionPreservesTheBossSpecificDefeatReason() {
        assertEquals(AshenSpanMissionModel.DefeatReason.BOSS_RECOVERY_EXHAUSTED,
                AshenSpanDirector.recoveryExhaustionDefeatReason(
                        AshenSpanDefinition.UnitKind.PMB04_SPAN_WARDEN));
        assertEquals(AshenSpanMissionModel.DefeatReason.MISSION_ABORTED,
                AshenSpanDirector.recoveryExhaustionDefeatReason(
                        AshenSpanDefinition.UnitKind.GATEKEEPER_R01));
    }
}
