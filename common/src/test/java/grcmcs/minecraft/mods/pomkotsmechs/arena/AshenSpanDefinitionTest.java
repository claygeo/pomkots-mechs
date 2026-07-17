package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AshenSpanDefinitionTest {
    @Test
    void mapAndRouteBoundsAreTheLockedAuthoredContract() {
        assertEquals("cold_ruin_sector_01", AshenSpanDefinition.MAP_ID);
        assertEquals(1, AshenSpanDefinition.MAP_VERSION);
        assertEquals("operation_ashen_span", AshenSpanDefinition.MISSION_ID);
        assertEquals("mecharena_sector01", AshenSpanDefinition.ASSET_MOD_ID);
        assertEquals(0, AshenSpanDefinition.ORIGIN_X);
        assertEquals(0, AshenSpanDefinition.ORIGIN_Z);

        assertEquals(new AshenSpanDefinition.HorizontalBounds(-176, 175, -112, 111),
                AshenSpanDefinition.CONTENT_BOUNDS);
        assertEquals(352, AshenSpanDefinition.CONTENT_BOUNDS.width());
        assertEquals(224, AshenSpanDefinition.CONTENT_BOUNDS.depth());
        assertEquals(new AshenSpanDefinition.HorizontalBounds(-168, 167, -56, 55),
                AshenSpanDefinition.PLAYABLE_BOUNDS);
        assertEquals(new AshenSpanDefinition.ChunkBounds(-11, 10, -7, 6),
                AshenSpanDefinition.CONTENT_CHUNKS);
        assertEquals(308, AshenSpanDefinition.CONTENT_CHUNKS.count());
        assertEquals(new AshenSpanDefinition.ChunkBounds(-17, 16, -10, 9),
                AshenSpanDefinition.SAFETY_CHUNKS);
        assertEquals(680, AshenSpanDefinition.SAFETY_CHUNKS.count());
        assertEquals(544, AshenSpanDefinition.SAFETY_BLOCK_BOUNDS.width());
        assertEquals(320, AshenSpanDefinition.SAFETY_BLOCK_BOUNDS.depth());
        assertEquals(6, AshenSpanDefinition.REQUIRED_VIEW_DISTANCE);
        assertEquals(6, AshenSpanDefinition.REQUIRED_SIMULATION_DISTANCE);
        assertEquals(33_554_432, AshenSpanDefinition.MAX_SHIPPED_WORLD_BYTES);

        assertEquals(new AshenSpanDefinition.HorizontalBounds(-168, -137, -23, 24),
                AshenSpanDefinition.GARAGE_DROP_DECK);
        assertEquals(82, AshenSpanDefinition.GARAGE_ROOF_Y);
        assertEquals(new AshenSpanDefinition.BlockPoint(-160, 83, 0),
                AshenSpanDefinition.PLAYER_PAD);
        assertEquals(AshenSpanDefinition.Facing.EAST, AshenSpanDefinition.PLAYER_PAD_FACING);
        assertEquals(new AshenSpanDefinition.HorizontalBounds(-136, -89, -39, 40),
                AshenSpanDefinition.FREIGHT_CANYON);
        assertEquals(64, AshenSpanDefinition.FREIGHT_GROUND_Y);
        assertEquals(new AshenSpanDefinition.HorizontalBounds(-88, 23, -13, 14),
                AshenSpanDefinition.ASHEN_SPAN);
        assertEquals(72, AshenSpanDefinition.SPAN_DECK_Y);
        assertEquals(48, AshenSpanDefinition.TRENCH_FLOOR_Y);
        assertEquals(new AshenSpanDefinition.HorizontalBounds(24, 71, -31, 32),
                AshenSpanDefinition.GATEHOUSE_APRON);
        assertEquals(new AshenSpanDefinition.BlockPoint(60, 73, 28),
                AshenSpanDefinition.SERVICE_GANTRY);
        assertEquals(new AshenSpanDefinition.HorizontalBounds(72, 79, -7, 8),
                AshenSpanDefinition.REVEAL_CORRIDOR);
        assertEquals(new AshenSpanDefinition.HorizontalBounds(80, 175, -47, 48),
                AshenSpanDefinition.EAST_POWER_DECK);
        assertEquals(new AshenSpanDefinition.HorizontalBounds(84, 171, -43, 44),
                AshenSpanDefinition.BOSS_CLEAR_CENTER);
        assertEquals(88, AshenSpanDefinition.BOSS_CLEAR_CENTER.width());
        assertEquals(88, AshenSpanDefinition.BOSS_CLEAR_CENTER.depth());
        assertEquals(new AshenSpanDefinition.BlockPoint(-48, 64, -78),
                AshenSpanDefinition.COOLING_STACK_ROOT);
        assertEquals(10, AshenSpanDefinition.COOLING_STACK_RADIUS);
        assertEquals(42, AshenSpanDefinition.COOLING_STACK_RISE);
        assertEquals(new AshenSpanDefinition.BlockPoint(128, 72, 80),
                AshenSpanDefinition.RELAY_MAST_ROOT);
        assertEquals(48, AshenSpanDefinition.RELAY_MAST_RISE);

        assertTrue(AshenSpanDefinition.PLAYABLE_BOUNDS.contains(-168, -56));
        assertTrue(AshenSpanDefinition.PLAYABLE_BOUNDS.contains(167, 55));
        assertFalse(AshenSpanDefinition.PLAYABLE_BOUNDS.contains(168, 0));
        assertTrue(AshenSpanDefinition.SAFETY_CHUNKS.contains(-17, -10));
        assertFalse(AshenSpanDefinition.SAFETY_CHUNKS.contains(17, 0));
    }

    @Test
    void gatePlanesRetainEveryLockedCoordinate() {
        assertEquals(Map.of(
                        AshenSpanDefinition.GateId.G1,
                        new AshenSpanDefinition.BlockVolume(-137, -137, 82, 89, -8, 8),
                        AshenSpanDefinition.GateId.G2,
                        new AshenSpanDefinition.BlockVolume(-89, -89, 64, 78, -14, 14),
                        AshenSpanDefinition.GateId.G3,
                        new AshenSpanDefinition.BlockVolume(-32, -32, 72, 82, -13, 14),
                        AshenSpanDefinition.GateId.G4,
                        new AshenSpanDefinition.BlockVolume(24, 24, 72, 82, -13, 14),
                        AshenSpanDefinition.GateId.G5,
                        new AshenSpanDefinition.BlockVolume(72, 72, 72, 84, -8, 8),
                        AshenSpanDefinition.GateId.INTERNAL_SHUTTER,
                        new AshenSpanDefinition.BlockVolume(58, 58, 72, 84, -8, 8)),
                AshenSpanDefinition.gates().entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                entry -> entry.getValue().volume())));
        assertTrue(AshenSpanDefinition.gate(AshenSpanDefinition.GateId.G3)
                .volume().contains(-32, 72, -13));
        assertTrue(AshenSpanDefinition.gate(AshenSpanDefinition.GateId.G3)
                .volume().contains(-32, 82, 14));
        assertFalse(AshenSpanDefinition.gate(AshenSpanDefinition.GateId.G3)
                .volume().contains(-31, 72, 0));
    }

    @Test
    void phaseOrderObjectivesTriggersAndGateActionsAreFixed() {
        assertEquals(List.of(
                        AshenSpanDefinition.PhaseId.DROP_DECK,
                        AshenSpanDefinition.PhaseId.FREIGHT_CANYON,
                        AshenSpanDefinition.PhaseId.WEST_SPAN,
                        AshenSpanDefinition.PhaseId.EAST_SPAN,
                        AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS,
                        AshenSpanDefinition.PhaseId.GATEKEEPER,
                        AshenSpanDefinition.PhaseId.POWER_DECK),
                AshenSpanDefinition.phases().stream().map(AshenSpanDefinition.PhaseSpec::id).toList());
        assertEquals(List.of(
                        "BREACH WEST GATE",
                        "BREAK THE FIRE LANE",
                        "CLEAR THE AIRSPACE",
                        "CRACK THE BARRICADE",
                        "ACE ESCORTS INBOUND",
                        "RIVAL SIGNAL: GATEKEEPER R-01",
                        "SPAN WARDEN ONLINE"),
                AshenSpanDefinition.phases().stream()
                        .map(AshenSpanDefinition.PhaseSpec::objective).toList());
        assertEquals(List.of(
                        AshenSpanDefinition.TriggerKind.BUILD_MOUNTED,
                        AshenSpanDefinition.TriggerKind.X_AT_LEAST,
                        AshenSpanDefinition.TriggerKind.X_AT_LEAST,
                        AshenSpanDefinition.TriggerKind.X_AT_LEAST,
                        AshenSpanDefinition.TriggerKind.X_AT_LEAST,
                        AshenSpanDefinition.TriggerKind.PRIOR_PHASE_EXACT_ZERO,
                        AshenSpanDefinition.TriggerKind.SERVICE_THEN_X_AT_LEAST),
                AshenSpanDefinition.phases().stream()
                        .map(phase -> phase.trigger().kind()).toList());
        assertEquals(java.util.Arrays.asList(null, -132, -84, -28, 28, null, 88),
                AshenSpanDefinition.phases().stream()
                        .map(phase -> phase.trigger().xThreshold()).toList());
        assertEquals(java.util.Arrays.asList(
                        AshenSpanDefinition.GateId.G1,
                        AshenSpanDefinition.GateId.G2,
                        AshenSpanDefinition.GateId.G3,
                        AshenSpanDefinition.GateId.G4,
                        AshenSpanDefinition.GateId.INTERNAL_SHUTTER,
                        null,
                        null),
                AshenSpanDefinition.phases().stream()
                        .map(AshenSpanDefinition.PhaseSpec::opensAfterClear).toList());
        assertEquals(AshenSpanDefinition.GateId.G5,
                AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS)
                        .boundaryGate());
        assertEquals(AshenSpanDefinition.GateId.INTERNAL_SHUTTER,
                AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.GATEKEEPER)
                        .boundaryGate());
    }

    @Test
    void fixedRosterSocketsAndRecoveryAnchorsMatchTheMissionSpec() {
        assertPhase(
                AshenSpanDefinition.PhaseId.DROP_DECK,
                List.of(AshenSpanDefinition.UnitKind.PMS01_RAMMER,
                        AshenSpanDefinition.UnitKind.PMS01_RAMMER,
                        AshenSpanDefinition.UnitKind.PMS03_RIFLE_MT),
                List.of(point(-150, 83, -14), point(-150, 83, 14), point(-141, 83, 0)),
                point(-152, 83, 0));
        assertPhase(
                AshenSpanDefinition.PhaseId.FREIGHT_CANYON,
                List.of(AshenSpanDefinition.UnitKind.PMS03_RIFLE_MT,
                        AshenSpanDefinition.UnitKind.PMS03_RIFLE_MT,
                        AshenSpanDefinition.UnitKind.PMS04_MISSILE_MT),
                List.of(point(-112, 65, -24), point(-112, 65, 24), point(-96, 65, 0)),
                point(-116, 65, 0));
        assertPhase(
                AshenSpanDefinition.PhaseId.WEST_SPAN,
                List.of(AshenSpanDefinition.UnitKind.PMS02_WASP,
                        AshenSpanDefinition.UnitKind.PMS04_MISSILE_MT,
                        AshenSpanDefinition.UnitKind.PMS03_RIFLE_MT),
                List.of(point(-48, 54, 0), point(-42, 73, -9), point(-42, 73, 9)),
                point(-56, 73, 0));
        assertPhase(
                AshenSpanDefinition.PhaseId.EAST_SPAN,
                List.of(AshenSpanDefinition.UnitKind.PMS05_BOMBARD,
                        AshenSpanDefinition.UnitKind.PMS01_RAMMER,
                        AshenSpanDefinition.UnitKind.PMS01_RAMMER,
                        AshenSpanDefinition.UnitKind.PMS03_RIFLE_MT),
                List.of(point(16, 73, 0), point(4, 73, -8), point(4, 73, 8), point(18, 73, 9)),
                point(-4, 73, 0));
        assertPhase(
                AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS,
                List.of(AshenSpanDefinition.UnitKind.PMS07_SAW_ROLLER,
                        AshenSpanDefinition.UnitKind.PMS07_SAW_ROLLER),
                List.of(point(48, 73, -18), point(48, 73, 18)),
                point(44, 73, 0));
        assertPhase(
                AshenSpanDefinition.PhaseId.GATEKEEPER,
                List.of(AshenSpanDefinition.UnitKind.GATEKEEPER_R01),
                List.of(point(64, 73, 0)),
                point(56, 73, 0));
        assertPhase(
                AshenSpanDefinition.PhaseId.POWER_DECK,
                List.of(AshenSpanDefinition.UnitKind.PMB04_SPAN_WARDEN),
                List.of(point(144, 73, 0)),
                point(128, 73, 0));
        assertEquals(point(92, 73, 0),
                AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.POWER_DECK).playerFallback());
        for (AshenSpanDefinition.PhaseSpec phase : AshenSpanDefinition.phases()) {
            assertTrue(phase.slots().stream()
                    .allMatch(slot -> slot.facing() == AshenSpanDefinition.Facing.WEST));
        }
    }

    @Test
    void normalRosterIsFifteenAcrossExactlySixRolesAndDuelsAreExclusive() {
        assertEquals(15, AshenSpanDefinition.normalRootCount());
        assertEquals(4, AshenSpanDefinition.MAX_ACTIVE_NORMALS);
        assertEquals(Map.of(
                        AshenSpanDefinition.UnitKind.PMS01_RAMMER, 4,
                        AshenSpanDefinition.UnitKind.PMS02_WASP, 1,
                        AshenSpanDefinition.UnitKind.PMS03_RIFLE_MT, 5,
                        AshenSpanDefinition.UnitKind.PMS04_MISSILE_MT, 2,
                        AshenSpanDefinition.UnitKind.PMS05_BOMBARD, 1,
                        AshenSpanDefinition.UnitKind.PMS07_SAW_ROLLER, 2),
                AshenSpanDefinition.normalRosterCounts());
        assertEquals(6, AshenSpanDefinition.normalRosterCounts().size());
        assertTrue(AshenSpanDefinition.phases().stream()
                .filter(phase -> phase.id().ordinal()
                        < AshenSpanDefinition.PhaseId.GATEKEEPER.ordinal())
                .allMatch(phase -> phase.normalRootCount() <= 4));

        AshenSpanDefinition.PhaseSpec rival =
                AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.GATEKEEPER);
        AshenSpanDefinition.PhaseSpec boss =
                AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.POWER_DECK);
        assertTrue(rival.exclusiveDuel());
        assertTrue(boss.exclusiveDuel());
        assertTrue(rival.slots().get(0).preplacedInactive());
        assertTrue(rival.slots().get(0).cameraConeExemptOnActivation());
        assertTrue(boss.slots().get(0).preplacedInactive());
        assertTrue(boss.slots().get(0).cameraConeExemptOnActivation());
        assertTrue(AshenSpanDefinition.phases().stream()
                .flatMap(phase -> phase.slots().stream())
                .filter(slot -> slot.unit().normal())
                .noneMatch(AshenSpanDefinition.UnitSlot::cameraConeExemptOnActivation));
        assertTrue(AshenSpanDefinition.phases().stream()
                .flatMap(phase -> phase.slots().stream())
                .allMatch(AshenSpanDefinition.UnitSlot::preplacedInactive));
    }

    @Test
    void definitionCollectionsAndTriggerShapesAreImmutableAndFailClosed() {
        assertThrows(UnsupportedOperationException.class,
                () -> AshenSpanDefinition.phases().add(
                        AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.DROP_DECK)));
        assertThrows(UnsupportedOperationException.class,
                () -> AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.DROP_DECK)
                        .slots().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> AshenSpanDefinition.gates().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> AshenSpanDefinition.normalRosterCounts()
                        .put(AshenSpanDefinition.UnitKind.PMS01_RAMMER, 99));
        assertThrows(IllegalArgumentException.class,
                () -> new AshenSpanDefinition.Trigger(
                        AshenSpanDefinition.TriggerKind.X_AT_LEAST, null));
        assertThrows(IllegalArgumentException.class,
                () -> new AshenSpanDefinition.Trigger(
                        AshenSpanDefinition.TriggerKind.BUILD_MOUNTED, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AshenSpanDefinition.UnitSlot(
                        "bad-normal-exemption",
                        AshenSpanDefinition.UnitKind.PMS01_RAMMER,
                        point(0, 0, 0),
                        AshenSpanDefinition.Facing.WEST,
                        AshenSpanDefinition.StagingBeat.CARGO_POD,
                        true,
                        true));
        assertNull(AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.GATEKEEPER)
                .opensAfterClear());
    }

    private static void assertPhase(AshenSpanDefinition.PhaseId id,
                                    List<AshenSpanDefinition.UnitKind> units,
                                    List<AshenSpanDefinition.BlockPoint> sockets,
                                    AshenSpanDefinition.BlockPoint recovery) {
        AshenSpanDefinition.PhaseSpec phase = AshenSpanDefinition.phase(id);
        assertEquals(units, phase.slots().stream().map(AshenSpanDefinition.UnitSlot::unit).toList());
        assertEquals(sockets, phase.slots().stream().map(AshenSpanDefinition.UnitSlot::socket).toList());
        assertEquals(recovery, phase.recoveryAnchor());
    }

    private static AshenSpanDefinition.BlockPoint point(int x, int y, int z) {
        return new AshenSpanDefinition.BlockPoint(x, y, z);
    }
}
