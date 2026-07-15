package grcmcs.minecraft.mods.pomkotsmechs.arena;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnDirectorTest {
    @Test
    void bundledCatalogMatchesLockedPerformanceEnvelope() throws IOException {
        SpawnDirector.Catalog catalog = bundledCatalog();

        assertEquals(36.0, catalog.minSpawnDistance());
        assertEquals(5, catalog.unitCap());
        assertEquals(6, catalog.threatCap());
        assertEquals(176.0, catalog.leashDistance());
        assertEquals(32, catalog.maxCandidateAttempts());
        assertEquals(20, catalog.reconcileTicks());
        assertEquals(200, catalog.unloadedRecoveryTicks());
        assertEquals(100, catalog.stuckAuditTicks());
        assertEquals(300, catalog.stuckWindowTicks());
        assertTrue(catalog.initialWarningTicks() > 0);
        assertTrue(catalog.waveWarningTicks() > 0);
        assertEquals(3, catalog.waves().size());
        assertTrue(catalog.boss().warningTicks() > 0);
        assertEquals("pomkotsmechs:pmb01mk2", catalog.boss().unit().entityId());
        assertEquals(6, catalog.boss().unit().cost());

        Set<String> normalRoster = catalog.waves().stream()
                .flatMap(wave -> wave.roster().stream())
                .map(SpawnDirector.UnitSpec::entityId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("pomkotsmechs:pms01", "pomkotsmechs:pms03", "pomkotsmechs:pms02"),
                normalRoster);
    }

    @Test
    void weightedWavePlanIsSeededAndExactlyFillsBudget() throws IOException {
        SpawnDirector.WaveSpec wave = bundledCatalog().waves().get(2);

        List<SpawnDirector.UnitSpec> first = SpawnDirector.planWave(wave, wave.budget(), new Random(991L));
        List<SpawnDirector.UnitSpec> replay = SpawnDirector.planWave(wave, wave.budget(), new Random(991L));
        List<SpawnDirector.UnitSpec> otherSeed = SpawnDirector.planWave(wave, wave.budget(), new Random(992L));

        assertEquals(first, replay);
        assertNotEquals(first, otherSeed);
        assertEquals(wave.budget(), first.stream().mapToInt(SpawnDirector.UnitSpec::cost).sum());
        assertTrue(first.stream().allMatch(unit -> unit.cost() <= 2));
    }

    @Test
    void lowHealthReducesOnlyTheNextNormalWaveByOne() {
        assertEquals(6, SpawnDirector.adjustedBudget(6, 1.0, false));
        assertEquals(6, SpawnDirector.adjustedBudget(6, 0.51, false));
        assertEquals(5, SpawnDirector.adjustedBudget(6, 0.50, false));
        assertEquals(1, SpawnDirector.adjustedBudget(1, 0.01, false));
        assertEquals(6, SpawnDirector.adjustedBudget(6, 0.01, true));
    }

    @Test
    void activeCapsRejectUnitAndThreatOverflow() {
        assertTrue(SpawnDirector.withinActiveCaps(4, 4, 2, 5, 5, 6));
        assertFalse(SpawnDirector.withinActiveCaps(5, 4, 1, 5, 5, 6));
        assertFalse(SpawnDirector.withinActiveCaps(4, 5, 2, 5, 5, 6));
        assertFalse(SpawnDirector.withinActiveCaps(3, 3, 1, 5, 3, 6));
        assertFalse(SpawnDirector.withinActiveCaps(-1, 0, 1, 5, 5, 6));
    }

    @Test
    void recoveryPolicyIsRelocateThenReplaceThenRetire() {
        SpawnDirector.RecoveryLineage initial = SpawnDirector.RecoveryLineage.initial(7L);
        assertEquals(SpawnDirector.RecoveryAction.RELOCATE, initial.nextAction());

        SpawnDirector.RecoveryLineage relocated = initial.afterRelocation();
        assertEquals(7L, relocated.slotOrdinal());
        assertEquals(1, relocated.relocationsUsed());
        assertEquals(SpawnDirector.RecoveryAction.REPLACE, relocated.nextAction());

        SpawnDirector.RecoveryLineage replacement = relocated.afterReplacement();
        assertEquals(7L, replacement.slotOrdinal());
        assertEquals(1, replacement.relocationsUsed());
        assertEquals(1, replacement.replacementsUsed());
        assertEquals(SpawnDirector.RecoveryAction.RETIRE, replacement.nextAction());
    }

    @Test
    void earlyReplacementRetainsTheSlotsRemainingRelocationBudget() {
        SpawnDirector.RecoveryLineage replacement =
                SpawnDirector.RecoveryLineage.initial(3L).afterReplacement();

        assertEquals(3L, replacement.slotOrdinal());
        assertEquals(0, replacement.relocationsUsed());
        assertEquals(1, replacement.replacementsUsed());
        assertEquals(SpawnDirector.RecoveryAction.RELOCATE, replacement.nextAction());
        assertEquals(SpawnDirector.RecoveryAction.RETIRE,
                replacement.afterRelocation().nextAction());
    }

    @Test
    void recoveryBudgetsAreIndependentAndFreshLineagesReset() {
        SpawnDirector.RecoveryLineage first = SpawnDirector.RecoveryLineage.initial(0L);
        SpawnDirector.RecoveryLineage second = SpawnDirector.RecoveryLineage.initial(1L);
        SpawnDirector.RecoveryLineage spent = first.afterRelocation().afterReplacement();

        assertEquals(SpawnDirector.RecoveryAction.RETIRE, spent.nextAction());
        assertEquals(SpawnDirector.RecoveryAction.RELOCATE, second.nextAction());
        assertEquals(first, SpawnDirector.RecoveryLineage.initial(0L));
    }

    @Test
    void recoveryLineageRejectsImpossibleOrOverspentStates() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnDirector.RecoveryLineage(-1L, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnDirector.RecoveryLineage(0L, 2, 0));
        assertThrows(IllegalStateException.class,
                () -> SpawnDirector.RecoveryLineage.initial(0L)
                        .afterRelocation().afterRelocation());
        assertThrows(IllegalStateException.class,
                () -> SpawnDirector.RecoveryLineage.initial(0L)
                        .afterReplacement().afterReplacement());
    }

    @Test
    void exhaustedNormalSlotsMayRetireButBossesMustFailTheRun() throws IOException {
        SpawnDirector.Catalog catalog = bundledCatalog();
        SpawnDirector.UnitSpec normal = catalog.waves().get(0).roster().get(0);
        SpawnDirector.UnitSpec boss = catalog.boss().unit();

        assertFalse(SpawnDirector.retirementFailsRun(normal));
        assertTrue(SpawnDirector.retirementFailsRun(boss));
        assertEquals(SpawnDirector.TickResult.FAILED, SpawnDirector.bossClearResult(false));
        assertEquals(SpawnDirector.TickResult.VICTORY, SpawnDirector.bossClearResult(true));
    }

    @Test
    void cancellableDeathMustBeConfirmedOnTheFollowingTick() {
        assertFalse(SpawnDirector.deathWasConfirmed(true, null),
                "a later listener kept the entity alive");
        assertFalse(SpawnDirector.deathWasConfirmed(false, null),
                "the death animation is not final removal");
        assertFalse(SpawnDirector.deathWasConfirmed(false, Entity.RemovalReason.DISCARDED));
        assertTrue(SpawnDirector.deathWasConfirmed(false, Entity.RemovalReason.KILLED));
        assertEquals(SpawnDirector.TickResult.FAILED,
                SpawnDirector.bossClearResult(
                        SpawnDirector.deathWasConfirmed(true, null)));
    }

    @Test
    void phaseProgressionStopsExactlyAtTheBossBoundary() {
        int waveCount = 3;

        assertTrue(SpawnDirector.canAdvancePhase(-1, waveCount),
                "warmup may advance to wave one");
        assertTrue(SpawnDirector.canAdvancePhase(0, waveCount));
        assertTrue(SpawnDirector.canAdvancePhase(waveCount - 1, waveCount),
                "the final wave may advance to the boss");
        assertFalse(SpawnDirector.canAdvancePhase(waveCount, waveCount),
                "the boss phase must not advance past itself");
        assertFalse(SpawnDirector.canAdvancePhase(waveCount + 1, waveCount));
        assertFalse(SpawnDirector.canAdvancePhase(-2, waveCount));
        assertFalse(SpawnDirector.canAdvancePhase(-1, 0));

        assertTrue(SpawnDirector.isWavePhase(waveCount - 1, waveCount));
        assertTrue(SpawnDirector.isBossPhase(waveCount, waveCount));
        assertFalse(SpawnDirector.isWavePhase(waveCount, waveCount));
        assertFalse(SpawnDirector.isBossPhase(waveCount + 1, waveCount));
    }

    @Test
    void victoryRequiresAConfirmedBossDeathAtTheExactBossPhase() {
        int waveCount = 3;

        assertFalse(SpawnDirector.confirmedBossVictory(waveCount, waveCount, false));
        assertTrue(SpawnDirector.confirmedBossVictory(waveCount, waveCount, true));
        assertFalse(SpawnDirector.confirmedBossVictory(waveCount - 1, waveCount, true));
        assertFalse(SpawnDirector.confirmedBossVictory(waveCount + 1, waveCount, true),
                "phase overshoot must fail closed even with stale defeat state");
    }

    @Test
    void duplicateConfirmedBossDeathsAreIdempotent() throws IOException {
        SpawnDirector.Catalog catalog = bundledCatalog();
        SpawnDirector.UnitSpec boss = catalog.boss().unit();
        SpawnDirector.UnitSpec normal = catalog.waves().get(0).roster().get(0);

        assertFalse(SpawnDirector.mergeBossDefeat(false, boss, false));
        assertFalse(SpawnDirector.mergeBossDefeat(false, normal, true));

        boolean defeated = SpawnDirector.mergeBossDefeat(false, boss, true);
        assertTrue(defeated);
        assertTrue(SpawnDirector.mergeBossDefeat(defeated, boss, true),
                "a duplicate callback must leave the terminal fact unchanged");
        assertTrue(SpawnDirector.mergeBossDefeat(defeated, normal, false));
    }

    @Test
    void resetLeavesTheSecondRunWithPristineLifecycleState() throws Exception {
        SpawnDirector director = new SpawnDirector();
        UUID firstFighter = UUID.randomUUID();

        assertFalse(director.prepare(null, 17, 91L, firstFighter,
                4.0, 5.0, 6.0, 160.0));
        assertTrue(director.status().startsWith("FAILED"));

        setField(director, "prepared", true);
        setField(director, "phaseStarted", true);
        setField(director, "forceNextRequested", true);
        setField(director, "replacementQueuedThisTick", true);
        setField(director, "bossDefeated", true);
        setField(director, "victory", true);
        setField(director, "phaseIndex", 9);
        setField(director, "nextSpawnOrdinal", 23L);

        director.reset();

        assertEquals("IDLE", director.status());
        assertEquals("", director.failureReason());
        assertTrue(director.drainMessages().isEmpty());
        assertNull(field(director, "catalog"));
        assertNull(field(director, "fighterId"));
        assertEquals(0L, field(director, "seed"));
        assertEquals(0, field(director, "matchId"));
        assertEquals(0, field(director, "tickCount"));
        assertEquals(-1, field(director, "phaseIndex"));
        assertEquals(0L, field(director, "nextSpawnOrdinal"));
        assertEquals(0, field(director, "warningRemaining"));
        assertEquals(false, field(director, "prepared"));
        assertEquals(false, field(director, "phaseStarted"));
        assertEquals(false, field(director, "forceNextRequested"));
        assertEquals(false, field(director, "replacementQueuedThisTick"));
        assertEquals(false, field(director, "bossDefeated"));
        assertEquals(false, field(director, "victory"));

        assertFalse(director.prepare(null, 18, 92L, UUID.randomUUID(),
                7.0, 8.0, 9.0, 160.0));
        List<String> secondRunMessages = director.drainMessages();
        assertEquals(1, secondRunMessages.size(),
                "the second prepare must not inherit first-run messages");
        assertTrue(secondRunMessages.get(0).contains("server level is unavailable"));
    }

    @Test
    void candidateSequenceIsDeterministicAndInsideDistanceBand() {
        Random first = new Random(77L);
        Random replay = new Random(77L);

        for (int i = 0; i < 32; i++) {
            SpawnDirector.SpawnOffset a = SpawnDirector.nextOffset(first, 36.0, 64.0);
            SpawnDirector.SpawnOffset b = SpawnDirector.nextOffset(replay, 36.0, 64.0);
            assertEquals(a, b);
            double distance = Math.hypot(a.x(), a.z());
            assertTrue(distance >= 36.0);
            assertTrue(distance < 64.0);
        }
    }

    @Test
    void candidateDistanceBandFollowsTheMovingPlayerOrigin() {
        double playerX = 87.25;
        double playerZ = -143.75;
        SpawnDirector.SpawnOffset candidate = SpawnDirector.nextCandidate(
                new Random(12L), playerX, playerZ, 36.0, 64.0);

        double fromPlayer = Math.hypot(candidate.x() - playerX, candidate.z() - playerZ);
        double fromArenaCenter = Math.hypot(candidate.x(), candidate.z());
        assertTrue(fromPlayer >= 36.0 && fromPlayer < 64.0);
        assertTrue(fromArenaCenter > 64.0, "test origin must prove the ring is not arena-centered");
    }

    @Test
    void arenaEdgeRetainsSpawnRingFeasibilityMargin() {
        assertTrue(SpawnDirector.spawnRingCanReachLeash(160.0, 176.0, 36.0, 4.0));
        assertFalse(SpawnDirector.spawnRingCanReachLeash(160.0, 120.0, 36.0, 4.0));
        assertTrue(SpawnDirector.leashCoversPlayerBounds(160.0, 176.0, 8.0));
        assertFalse(SpawnDirector.leashCoversPlayerBounds(160.0, 167.99, 8.0));
    }

    @Test
    void priorityCandidatesCoverSafeAnglesAtWorstCaseArenaEdge() {
        Random random = new Random(55L);
        int acceptable = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            SpawnDirector.SpawnOffset candidate = SpawnDirector.candidateForAttempt(
                    random, attempt, 160.0, 0.0, -1.0, 0.0,
                    -160.0, 0.0, 36.0, 64.0);
            double fromPlayer = Math.hypot(candidate.x() - 160.0, candidate.z());
            boolean insideLeash = Math.hypot(candidate.x(), candidate.z()) <= 176.0;
            boolean visible = SpawnDirector.rejectVisibleCandidate(
                    -1.0, 0.0, 0.0,
                    candidate.x() - 160.0, 0.0, candidate.z(), 100.0, true);
            assertEquals(36.0, fromPlayer, 1.0E-8);
            if (insideLeash && !visible) {
                acceptable++;
            }
        }
        assertTrue(acceptable >= 2, "priority coverage must preserve bounded edge spawns");
    }

    @Test
    void visibleCandidateUsesFullThreeDimensionalCameraCone() {
        double sin30 = 0.5;
        double cos30 = Math.sqrt(3.0) / 2.0;
        double sin60 = cos30;
        double cos60 = sin30;

        assertTrue(SpawnDirector.rejectVisibleCandidate(
                0.0, 0.0, 1.0, 0.0, 0.0, 10.0, 100.0, true));
        assertTrue(SpawnDirector.rejectVisibleCandidate(
                0.0, sin30, cos30, 0.0, 0.0, 10.0, 100.0, true));
        assertFalse(SpawnDirector.rejectVisibleCandidate(
                0.0, sin60, cos60, 0.0, 0.0, 10.0, 100.0, true));
        assertFalse(SpawnDirector.rejectVisibleCandidate(
                0.0, 1.0, 0.0, 0.0, 0.0, 10.0, 100.0, true));
        assertTrue(SpawnDirector.rejectVisibleCandidate(
                0.0, 1.0, 0.0, 0.0, 10.0, 0.0, 100.0, true));
        assertFalse(SpawnDirector.rejectVisibleCandidate(
                0.0, 0.0, 1.0, 0.0, 0.0, -10.0, 100.0, true));
        assertFalse(SpawnDirector.rejectVisibleCandidate(
                0.0, 0.0, 1.0, 0.0, 0.0, 10.0, 100.0, false));
    }

    @Test
    void validationRejectsCapsAndCandidateLoopsAboveHardLimits() throws IOException {
        String json = bundledJson();

        assertThrows(IllegalArgumentException.class,
                () -> SpawnDirector.parseCatalog(json.replace("\"unit_cap\": 5", "\"unit_cap\": 6")));
        assertThrows(IllegalArgumentException.class,
                () -> SpawnDirector.parseCatalog(json.replace(
                        "\"max_candidate_attempts\": 32", "\"max_candidate_attempts\": 33")));
    }

    @Test
    void validationRejectsInvalidWeightsAndMissingDistinctBoss() throws IOException {
        String json = bundledJson();

        assertThrows(IllegalArgumentException.class,
                () -> SpawnDirector.parseCatalog(json.replaceFirst("\"weight\": 3", "\"weight\": 0")));
        assertThrows(IllegalArgumentException.class,
                () -> SpawnDirector.parseCatalog(json.replaceFirst("\"weight\": 3", "\"weight\": 10001")));
        assertThrows(IllegalArgumentException.class,
                () -> SpawnDirector.parseCatalog(json.replaceFirst("\"budget\": 3", "\"budget\": 13")));
        assertThrows(IllegalArgumentException.class,
                () -> SpawnDirector.parseCatalog(json.replace("\"boss\": true", "\"boss\": false")));
    }

    @Test
    void parserAllowsWaveCountToRemainDataOnly() throws IOException {
        JsonObject root = JsonParser.parseString(bundledJson()).getAsJsonObject();
        JsonArray waves = root.getAsJsonArray("waves");
        while (waves.size() > 1) {
            waves.remove(waves.size() - 1);
        }

        SpawnDirector.Catalog oneWave = SpawnDirector.parseCatalog(root.toString());
        assertEquals(1, oneWave.waves().size());

        waves.remove(0);
        assertThrows(IllegalArgumentException.class,
                () -> SpawnDirector.parseCatalog(root.toString()));
    }

    private static SpawnDirector.Catalog bundledCatalog() throws IOException {
        return SpawnDirector.parseCatalog(bundledJson());
    }

    private static String bundledJson() throws IOException {
        try (InputStream input = SpawnDirectorTest.class.getResourceAsStream(
                "/data/pomkotsmechs/arena/solo_encounters.json")) {
            if (input == null) {
                throw new IOException("bundled solo encounter catalog is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
