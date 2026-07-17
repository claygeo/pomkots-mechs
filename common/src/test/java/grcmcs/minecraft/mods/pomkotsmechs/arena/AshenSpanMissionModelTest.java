package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AshenSpanMissionModelTest {
    private static final long SEED = 0x5A17E5EEDL;

    @Test
    void buildIndexIsInternalZeroBasedAndMountStillRequiresExactAdmission() {
        assertThrows(IllegalArgumentException.class, () -> new AshenSpanMissionModel(SEED, -1));
        assertThrows(IllegalArgumentException.class, () -> new AshenSpanMissionModel(SEED, 6));

        for (int build = 0; build < AshenSpanMissionModel.GARAGE_BUILD_COUNT; build++) {
            AshenSpanMissionModel model = new AshenSpanMissionModel(SEED, build);
            assertEquals(AshenSpanMissionModel.Status.GARAGE, model.status());
            assertTrue(model.currentPhase().isEmpty());
            assertEquals(AshenSpanMissionModel.Status.STAGING, model.onBuildMounted().status());
            assertEquals(AshenSpanDefinition.PhaseId.DROP_DECK,
                    model.currentPhase().orElseThrow().id());
            assertThrows(IllegalArgumentException.class, () -> model.admitCurrentPhase(2));
            assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                    model.admitCurrentPhase(3).status());
        }
    }

    @Test
    void phaseOrderRequiresExactZeroThenAuthoredTriggerAndOpensOnlyAuthoredGates() {
        AshenSpanMissionModel model = new AshenSpanMissionModel(SEED, 4);
        model.onBuildMounted();
        model.admitCurrentPhase(3);

        assertEquals(AshenSpanMissionModel.Status.ACTIVE, model.status());
        assertEquals(AshenSpanDefinition.PhaseId.DROP_DECK,
                model.currentPhase().orElseThrow().id());
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.observe(healthy(-150, new AshenSpanMissionModel.HostileCounts(0, 1, 0, 0)))
                        .status());
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.observe(healthy(-150, new AshenSpanMissionModel.HostileCounts(0, 0, 1, 0)))
                        .status());
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.observe(healthy(-150, new AshenSpanMissionModel.HostileCounts(0, 0, 0, 1)))
                        .status());

        AshenSpanMissionModel.Snapshot phase2 = model.observe(healthy(-150,
                AshenSpanMissionModel.HostileCounts.ZERO));
        assertEquals(AshenSpanMissionModel.Status.STAGING, phase2.status());
        assertEquals(AshenSpanDefinition.PhaseId.FREIGHT_CANYON, phase2.currentPhase());
        assertEquals(EnumSet.of(AshenSpanDefinition.GateId.G1), phase2.openedGates());

        assertEquals(AshenSpanMissionModel.Status.WAITING_FOR_TRIGGER,
                model.admitCurrentPhase(3).status());
        assertEquals(AshenSpanMissionModel.Status.WAITING_FOR_TRIGGER,
                model.observe(healthy(-132.001, roots(3))).status());
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.observe(healthy(-132, roots(3))).status());
        clearActive(model, -132);
        assertPhase(model, AshenSpanDefinition.PhaseId.WEST_SPAN,
                AshenSpanMissionModel.Status.STAGING);
        assertEquals(EnumSet.of(AshenSpanDefinition.GateId.G1, AshenSpanDefinition.GateId.G2),
                model.openedGates());

        stageTriggerAndClear(model, 3, -84);
        assertPhase(model, AshenSpanDefinition.PhaseId.EAST_SPAN,
                AshenSpanMissionModel.Status.STAGING);
        stageTriggerAndClear(model, 4, -28);
        assertPhase(model, AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS,
                AshenSpanMissionModel.Status.STAGING);
        stageTriggerAndClear(model, 2, 28);

        assertPhase(model, AshenSpanDefinition.PhaseId.GATEKEEPER,
                AshenSpanMissionModel.Status.STAGING);
        assertTrue(model.openedGates().contains(AshenSpanDefinition.GateId.INTERNAL_SHUTTER));
        assertFalse(model.openedGates().contains(AshenSpanDefinition.GateId.G5));

        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.admitCurrentPhase(1).status(),
                "5B starts from exact-zero 5A cleanup without a positional trigger");
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.observe(healthy(44,
                        new AshenSpanMissionModel.HostileCounts(0, 1, 0, 0))).status(),
                "rival projectile lineage blocks service");
        assertEquals(AshenSpanMissionModel.Status.SERVICE,
                model.observe(healthy(44, AshenSpanMissionModel.HostileCounts.ZERO)).status());
        assertPhase(model, AshenSpanDefinition.PhaseId.POWER_DECK,
                AshenSpanMissionModel.Status.SERVICE);
        assertFalse(model.openedGates().contains(AshenSpanDefinition.GateId.G5));

        assertTrue(model.completeService());
        assertFalse(model.completeService(), "service cannot duplicate a template refill");
        assertTrue(model.serviceCompleted());
        assertTrue(model.openedGates().contains(AshenSpanDefinition.GateId.G5));
        assertEquals(AshenSpanMissionModel.Status.WAITING_FOR_TRIGGER,
                model.admitCurrentPhase(1).status());
        assertEquals(AshenSpanMissionModel.Status.WAITING_FOR_TRIGGER,
                model.observe(healthy(87.999, roots(1))).status());
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.observe(healthy(88, roots(1))).status());
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.observe(healthy(88,
                        new AshenSpanMissionModel.HostileCounts(0, 0, 0, 1))).status(),
                "PMB04 supplemental hitbox must reach zero");
        AshenSpanMissionModel.Snapshot victory =
                model.observe(healthy(88, AshenSpanMissionModel.HostileCounts.ZERO));
        assertEquals(AshenSpanMissionModel.Status.VICTORY, victory.status());
        assertEquals(AshenSpanMissionModel.Outcome.VICTORY, victory.outcome());
        assertEquals(AshenSpanMissionModel.DefeatReason.NONE, victory.defeatReason());
        assertEquals(EnumSet.allOf(AshenSpanDefinition.GateId.class), victory.openedGates());
    }

    @Test
    void crossingTriggerWhileStagingIsRememberedButCannotRunBeforeAdmission() {
        AshenSpanMissionModel model = new AshenSpanMissionModel(SEED, 0);
        model.onBuildMounted();
        model.admitCurrentPhase(3);
        clearActive(model, -150);
        assertEquals(AshenSpanMissionModel.Status.STAGING, model.status());

        AshenSpanMissionModel.Snapshot crossed = model.observe(healthy(-120, roots(3)));
        assertEquals(AshenSpanMissionModel.Status.STAGING, crossed.status());
        assertTrue(crossed.triggerSatisfied());
        assertEquals(AshenSpanMissionModel.Status.ACTIVE,
                model.admitCurrentPhase(3).status());
    }

    @Test
    void serviceIsUnavailableUntilRivalAndAllDescendantsAreExactlyZero() {
        AshenSpanMissionModel model = reachGatekeeperActive();
        assertThrows(IllegalStateException.class, model::completeService);

        model.observe(healthy(44, new AshenSpanMissionModel.HostileCounts(0, 0, 1, 0)));
        assertEquals(AshenSpanMissionModel.Status.ACTIVE, model.status());
        assertThrows(IllegalStateException.class, model::completeService);

        model.observe(healthy(44, AshenSpanMissionModel.HostileCounts.ZERO));
        assertEquals(AshenSpanMissionModel.Status.SERVICE, model.status());
        assertTrue(model.completeService());
    }

    @Test
    void doubleKoIsDefeatBecauseLossIsEvaluatedBeforeBossClear() {
        AshenSpanMissionModel model = reachPowerDeckActive();
        AshenSpanMissionModel.Snapshot result = model.observe(new AshenSpanMissionModel.TickInput(
                100,
                AshenSpanMissionModel.HostileCounts.ZERO,
                true,
                false,
                true,
                false,
                false));
        assertEquals(AshenSpanMissionModel.Status.DEFEAT, result.status());
        assertEquals(AshenSpanMissionModel.Outcome.DEFEAT, result.outcome());
        assertEquals(AshenSpanMissionModel.DefeatReason.MECH_DESTROYED, result.defeatReason());
    }

    @Test
    void allRequiredLossPathsTerminateAndBossRecoveryOnlyAppliesToBossPhase() {
        assertDefeat(new AshenSpanMissionModel.TickInput(-160, roots(3),
                        false, true, true, false, false),
                AshenSpanMissionModel.DefeatReason.PLAYER_DESTROYED);
        assertDefeat(new AshenSpanMissionModel.TickInput(-160, roots(3),
                        true, false, true, false, false),
                AshenSpanMissionModel.DefeatReason.MECH_DESTROYED);
        assertDefeat(new AshenSpanMissionModel.TickInput(-160, roots(3),
                        true, true, false, false, false),
                AshenSpanMissionModel.DefeatReason.DISCONNECTED);
        assertDefeat(new AshenSpanMissionModel.TickInput(-160, roots(3),
                        true, true, true, true, false),
                AshenSpanMissionModel.DefeatReason.STOPPED);

        AshenSpanMissionModel early = new AshenSpanMissionModel(SEED, 0);
        early.onBuildMounted();
        early.admitCurrentPhase(3);
        early.observe(new AshenSpanMissionModel.TickInput(-160, roots(3),
                true, true, true, false, true));
        assertEquals(AshenSpanMissionModel.Status.ACTIVE, early.status(),
                "boss recovery exhaustion is irrelevant before the boss phase");

        AshenSpanMissionModel boss = reachPowerDeckActive();
        AshenSpanMissionModel.Snapshot exhausted = boss.observe(
                new AshenSpanMissionModel.TickInput(100, roots(1),
                        true, true, true, false, true));
        assertEquals(AshenSpanMissionModel.Status.DEFEAT, exhausted.status());
        assertEquals(AshenSpanMissionModel.DefeatReason.BOSS_RECOVERY_EXHAUSTED,
                exhausted.defeatReason());
    }

    @Test
    void retryRetainsExactSeedAndBuildButResetsAllRunState() {
        AshenSpanMissionModel model = new AshenSpanMissionModel(SEED, 5);
        assertThrows(IllegalStateException.class, model::retry);
        model.onBuildMounted();
        model.admitCurrentPhase(3);
        model.observe(new AshenSpanMissionModel.TickInput(-160, roots(3),
                true, true, true, true, false));

        AshenSpanMissionModel retry = model.retry();
        assertEquals(SEED, retry.seed());
        assertEquals(5, retry.selectedBuild());
        assertEquals(AshenSpanMissionModel.Status.GARAGE, retry.status());
        assertEquals(AshenSpanMissionModel.Outcome.RUNNING, retry.outcome());
        assertEquals(AshenSpanMissionModel.DefeatReason.NONE, retry.defeatReason());
        assertTrue(retry.currentPhase().isEmpty());
        assertTrue(retry.openedGates().isEmpty());
        assertFalse(retry.serviceCompleted());
    }

    @Test
    void arenaSideTerminalConditionsProduceARealDefeatForSummaryAndRetry() {
        AshenSpanMissionModel model = new AshenSpanMissionModel(SEED, 3);
        model.onBuildMounted();
        model.admitCurrentPhase(3);
        AshenSpanMissionModel.Snapshot stopped = model.forceDefeat(
                AshenSpanMissionModel.DefeatReason.STOPPED);
        assertEquals(AshenSpanMissionModel.Status.DEFEAT, stopped.status());
        assertEquals(AshenSpanMissionModel.Outcome.DEFEAT, stopped.outcome());
        assertEquals(AshenSpanMissionModel.DefeatReason.STOPPED, stopped.defeatReason());
        assertEquals(SEED, model.retry().seed());
        assertThrows(IllegalArgumentException.class,
                () -> new AshenSpanMissionModel(SEED, 0).forceDefeat(
                        AshenSpanMissionModel.DefeatReason.NONE));
    }

    @Test
    void sameSeedBuildAndInputsProduceIdenticalSnapshots() {
        AshenSpanMissionModel first = new AshenSpanMissionModel(SEED, 2);
        AshenSpanMissionModel replay = new AshenSpanMissionModel(SEED, 2);
        assertEquals(first.onBuildMounted(), replay.onBuildMounted());
        assertEquals(first.admitCurrentPhase(3), replay.admitCurrentPhase(3));
        assertEquals(first.observe(healthy(-150, roots(1))),
                replay.observe(healthy(-150, roots(1))));
        assertEquals(first.observe(healthy(-150, AshenSpanMissionModel.HostileCounts.ZERO)),
                replay.observe(healthy(-150, AshenSpanMissionModel.HostileCounts.ZERO)));
        assertEquals(first.observe(healthy(-120, roots(3))),
                replay.observe(healthy(-120, roots(3))));
        assertEquals(first.admitCurrentPhase(3), replay.admitCurrentPhase(3));
    }

    @Test
    void hostileCountsRejectNegativeValuesAndExposeDescendantTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> new AshenSpanMissionModel.HostileCounts(-1, 0, 0, 0));
        AshenSpanMissionModel.HostileCounts counts =
                new AshenSpanMissionModel.HostileCounts(2, 3, 4, 5);
        assertEquals(12L, counts.descendants());
        assertFalse(counts.exactZero());
        assertTrue(AshenSpanMissionModel.HostileCounts.ZERO.exactZero());
    }

    private static AshenSpanMissionModel reachGatekeeperActive() {
        AshenSpanMissionModel model = new AshenSpanMissionModel(SEED, 1);
        model.onBuildMounted();
        model.admitCurrentPhase(3);
        clearActive(model, -150);
        stageTriggerAndClear(model, 3, -132);
        stageTriggerAndClear(model, 3, -84);
        stageTriggerAndClear(model, 4, -28);
        stageTriggerAndClear(model, 2, 28);
        model.admitCurrentPhase(1);
        assertPhase(model, AshenSpanDefinition.PhaseId.GATEKEEPER,
                AshenSpanMissionModel.Status.ACTIVE);
        return model;
    }

    private static AshenSpanMissionModel reachPowerDeckActive() {
        AshenSpanMissionModel model = reachGatekeeperActive();
        clearActive(model, 44);
        assertEquals(AshenSpanMissionModel.Status.SERVICE, model.status());
        model.completeService();
        model.admitCurrentPhase(1);
        model.observe(healthy(88, roots(1)));
        assertPhase(model, AshenSpanDefinition.PhaseId.POWER_DECK,
                AshenSpanMissionModel.Status.ACTIVE);
        return model;
    }

    private static void stageTriggerAndClear(AshenSpanMissionModel model,
                                             int expectedRoots,
                                             double triggerX) {
        assertEquals(AshenSpanMissionModel.Status.STAGING, model.status());
        model.admitCurrentPhase(expectedRoots);
        if (model.status() == AshenSpanMissionModel.Status.WAITING_FOR_TRIGGER) {
            model.observe(healthy(triggerX, roots(expectedRoots)));
        }
        assertEquals(AshenSpanMissionModel.Status.ACTIVE, model.status());
        clearActive(model, triggerX);
    }

    private static void clearActive(AshenSpanMissionModel model, double playerX) {
        assertEquals(AshenSpanMissionModel.Status.ACTIVE, model.status());
        model.observe(healthy(playerX, AshenSpanMissionModel.HostileCounts.ZERO));
    }

    private static void assertPhase(AshenSpanMissionModel model,
                                    AshenSpanDefinition.PhaseId phase,
                                    AshenSpanMissionModel.Status status) {
        assertEquals(phase, model.currentPhase().orElseThrow().id());
        assertEquals(status, model.status());
    }

    private static void assertDefeat(AshenSpanMissionModel.TickInput input,
                                     AshenSpanMissionModel.DefeatReason reason) {
        AshenSpanMissionModel model = new AshenSpanMissionModel(SEED, 0);
        model.onBuildMounted();
        model.admitCurrentPhase(3);
        AshenSpanMissionModel.Snapshot snapshot = model.observe(input);
        assertEquals(AshenSpanMissionModel.Status.DEFEAT, snapshot.status());
        assertEquals(reason, snapshot.defeatReason());
    }

    private static AshenSpanMissionModel.TickInput healthy(
            double playerX, AshenSpanMissionModel.HostileCounts hostiles) {
        return AshenSpanMissionModel.TickInput.healthy(playerX, hostiles);
    }

    private static AshenSpanMissionModel.HostileCounts roots(int roots) {
        return new AshenSpanMissionModel.HostileCounts(roots, 0, 0, 0);
    }
}
