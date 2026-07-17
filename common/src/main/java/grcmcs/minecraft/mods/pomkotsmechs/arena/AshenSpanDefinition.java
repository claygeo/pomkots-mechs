package grcmcs.minecraft.mods.pomkotsmechs.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable authored contract for Cold Ruin Sector 01. This is deliberately a
 * mission definition, not a configurable wave catalog: roster, order, volumes,
 * sockets, and recovery points are fixed by Operation Ashen Span.
 */
public final class AshenSpanDefinition {
    public static final String MAP_ID = "cold_ruin_sector_01";
    public static final int MAP_VERSION = 1;
    public static final String MISSION_ID = "operation_ashen_span";
    public static final String ASSET_MOD_ID = "mecharena_sector01";

    public static final int ORIGIN_X = 0;
    public static final int ORIGIN_Z = 0;
    public static final int REQUIRED_VIEW_DISTANCE = 6;
    public static final int REQUIRED_SIMULATION_DISTANCE = 6;
    public static final int MAX_SHIPPED_WORLD_BYTES = 32 * 1024 * 1024;
    public static final int MAX_ACTIVE_NORMALS = 4;
    public static final int MAX_TRACKED_PROJECTILES = 48;
    public static final int MAX_TRACKED_EFFECTS = 5;
    public static final int EXPECTED_NORMAL_ROOTS = 15;

    public static final HorizontalBounds CONTENT_BOUNDS =
            new HorizontalBounds(-176, 175, -112, 111);
    public static final HorizontalBounds PLAYABLE_BOUNDS =
            new HorizontalBounds(-168, 167, -56, 55);
    public static final ChunkBounds CONTENT_CHUNKS =
            new ChunkBounds(-11, 10, -7, 6);
    public static final ChunkBounds SAFETY_CHUNKS =
            new ChunkBounds(-17, 16, -10, 9);
    public static final HorizontalBounds SAFETY_BLOCK_BOUNDS =
            new HorizontalBounds(-272, 271, -160, 159);

    public static final HorizontalBounds GARAGE_DROP_DECK =
            new HorizontalBounds(-168, -137, -23, 24);
    public static final int GARAGE_ROOF_Y = 82;
    public static final BlockPoint PLAYER_PAD = new BlockPoint(-160, 83, 0);
    public static final Facing PLAYER_PAD_FACING = Facing.EAST;

    public static final HorizontalBounds FREIGHT_CANYON =
            new HorizontalBounds(-136, -89, -39, 40);
    public static final int FREIGHT_GROUND_Y = 64;

    public static final HorizontalBounds ASHEN_SPAN =
            new HorizontalBounds(-88, 23, -13, 14);
    public static final int SPAN_DECK_Y = 72;
    public static final int TRENCH_FLOOR_Y = 48;

    public static final HorizontalBounds GATEHOUSE_APRON =
            new HorizontalBounds(24, 71, -31, 32);
    public static final int GATEHOUSE_DECK_Y = 72;
    public static final BlockPoint SERVICE_GANTRY = new BlockPoint(60, 73, 28);
    public static final HorizontalBounds REVEAL_CORRIDOR =
            new HorizontalBounds(72, 79, -7, 8);

    public static final HorizontalBounds EAST_POWER_DECK =
            new HorizontalBounds(80, 175, -47, 48);
    public static final HorizontalBounds BOSS_CLEAR_CENTER =
            new HorizontalBounds(84, 171, -43, 44);
    public static final int POWER_DECK_Y = 72;

    public static final BlockPoint COOLING_STACK_ROOT = new BlockPoint(-48, 64, -78);
    public static final int COOLING_STACK_RADIUS = 10;
    public static final int COOLING_STACK_RISE = 42;
    public static final BlockPoint RELAY_MAST_ROOT = new BlockPoint(128, 72, 80);
    public static final int RELAY_MAST_RISE = 48;

    private static final Map<GateId, GateSpec> GATES = createGates();
    private static final List<PhaseSpec> PHASES = createPhases();
    private static final Map<PhaseId, PhaseSpec> PHASE_BY_ID = indexPhases();

    static {
        validateLockedContract();
    }

    private AshenSpanDefinition() {
    }

    public enum Facing {
        EAST,
        WEST
    }

    public enum PhaseId {
        DROP_DECK("1"),
        FREIGHT_CANYON("2"),
        WEST_SPAN("3"),
        EAST_SPAN("4"),
        GATEHOUSE_ESCORTS("5A"),
        GATEKEEPER("5B"),
        POWER_DECK("6");

        private final String label;

        PhaseId(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum TriggerKind {
        BUILD_MOUNTED,
        X_AT_LEAST,
        PRIOR_PHASE_EXACT_ZERO,
        SERVICE_THEN_X_AT_LEAST
    }

    public enum GateId {
        G1,
        G2,
        G3,
        G4,
        G5,
        INTERNAL_SHUTTER
    }

    public enum UnitKind {
        PMS01_RAMMER("pomkotsmechs:pms01", "Rammer", true),
        PMS02_WASP("pomkotsmechs:pms02", "Wasp", true),
        PMS03_RIFLE_MT("pomkotsmechs:pms03", "Rifle MT", true),
        PMS04_MISSILE_MT("pomkotsmechs:pms04", "Missile MT", true),
        PMS05_BOMBARD("pomkotsmechs:pms05", "Bombard", true),
        PMS07_SAW_ROLLER("pomkotsmechs:pms07", "Saw Roller", true),
        GATEKEEPER_R01("pomkotsmechs:arena_rival_pmvc01", "Gatekeeper R-01", false),
        PMB04_SPAN_WARDEN("pomkotsmechs:pmb04", "SPAN WARDEN", false);

        private final String entityId;
        private final String role;
        private final boolean normal;

        UnitKind(String entityId, String role, boolean normal) {
            this.entityId = entityId;
            this.role = role;
            this.normal = normal;
        }

        public String entityId() {
            return entityId;
        }

        public String role() {
            return role;
        }

        public boolean normal() {
            return normal;
        }
    }

    public enum StagingBeat {
        LAUNCH_BAY_SHUTTER,
        CARGO_POD,
        TRENCH_RISE,
        WEST_WINDBREAK,
        EAST_BARRICADE,
        GATEHOUSE_SHUTTER_NICHE,
        RIVAL_INTERNAL_SHUTTER,
        BOSS_BOOT_REVEAL
    }

    public record BlockPoint(int x, int y, int z) {
    }

    public record HorizontalBounds(int minX, int maxX, int minZ, int maxZ) {
        public HorizontalBounds {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("invalid horizontal bounds");
            }
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public boolean contains(double x, double z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    public record ChunkBounds(int minX, int maxX, int minZ, int maxZ) {
        public ChunkBounds {
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("invalid chunk bounds");
            }
        }

        public int width() {
            return maxX - minX + 1;
        }

        public int depth() {
            return maxZ - minZ + 1;
        }

        public int count() {
            return width() * depth();
        }

        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
        }
    }

    public record BlockVolume(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public BlockVolume {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("invalid block volume");
            }
        }

        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    public record Trigger(TriggerKind kind, Integer xThreshold) {
        public Trigger {
            Objects.requireNonNull(kind, "kind");
            boolean needsThreshold = kind == TriggerKind.X_AT_LEAST
                    || kind == TriggerKind.SERVICE_THEN_X_AT_LEAST;
            if (needsThreshold != (xThreshold != null)) {
                throw new IllegalArgumentException("trigger threshold does not match trigger kind");
            }
        }

        public static Trigger buildMounted() {
            return new Trigger(TriggerKind.BUILD_MOUNTED, null);
        }

        public static Trigger xAtLeast(int x) {
            return new Trigger(TriggerKind.X_AT_LEAST, x);
        }

        public static Trigger priorPhaseExactZero() {
            return new Trigger(TriggerKind.PRIOR_PHASE_EXACT_ZERO, null);
        }

        public static Trigger serviceThenXAtLeast(int x) {
            return new Trigger(TriggerKind.SERVICE_THEN_X_AT_LEAST, x);
        }

        public boolean positionSatisfied(double x) {
            return xThreshold != null && x >= xThreshold;
        }
    }

    public record GateSpec(GateId id, BlockVolume volume) {
        public GateSpec {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(volume, "volume");
        }
    }

    public record UnitSlot(
            String slotId,
            UnitKind unit,
            BlockPoint socket,
            Facing facing,
            StagingBeat stagingBeat,
            boolean preplacedInactive,
            boolean cameraConeExemptOnActivation) {
        public UnitSlot {
            if (slotId == null || slotId.isBlank()) {
                throw new IllegalArgumentException("slot id is required");
            }
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(socket, "socket");
            Objects.requireNonNull(facing, "facing");
            Objects.requireNonNull(stagingBeat, "stagingBeat");
            if (cameraConeExemptOnActivation && !preplacedInactive) {
                throw new IllegalArgumentException("camera exemption requires an inactive reveal");
            }
            if (unit.normal() && cameraConeExemptOnActivation) {
                throw new IllegalArgumentException("normal units never receive a camera-cone exemption");
            }
        }
    }

    public record PhaseSpec(
            PhaseId id,
            String objective,
            Trigger trigger,
            GateId boundaryGate,
            GateId opensAfterClear,
            List<UnitSlot> slots,
            BlockPoint recoveryAnchor,
            BlockPoint playerFallback) {
        public PhaseSpec {
            Objects.requireNonNull(id, "id");
            if (objective == null || objective.isBlank()) {
                throw new IllegalArgumentException("objective is required");
            }
            Objects.requireNonNull(trigger, "trigger");
            slots = List.copyOf(slots);
            if (slots.isEmpty()) {
                throw new IllegalArgumentException("an authored phase needs at least one slot");
            }
            Objects.requireNonNull(recoveryAnchor, "recoveryAnchor");
        }

        public int expectedRootCount() {
            return slots.size();
        }

        public long normalRootCount() {
            return slots.stream().filter(slot -> slot.unit().normal()).count();
        }

        public boolean exclusiveDuel() {
            return slots.size() == 1 && !slots.get(0).unit().normal();
        }
    }

    public static List<PhaseSpec> phases() {
        return PHASES;
    }

    public static PhaseSpec phase(PhaseId id) {
        PhaseSpec phase = PHASE_BY_ID.get(Objects.requireNonNull(id, "id"));
        if (phase == null) {
            throw new IllegalArgumentException("unknown Ashen Span phase: " + id);
        }
        return phase;
    }

    public static Map<GateId, GateSpec> gates() {
        return GATES;
    }

    public static GateSpec gate(GateId id) {
        GateSpec gate = GATES.get(Objects.requireNonNull(id, "id"));
        if (gate == null) {
            throw new IllegalArgumentException("unknown Ashen Span gate: " + id);
        }
        return gate;
    }

    public static int normalRootCount() {
        return PHASES.stream().mapToInt(phase -> (int) phase.normalRootCount()).sum();
    }

    public static Map<UnitKind, Integer> normalRosterCounts() {
        EnumMap<UnitKind, Integer> counts = new EnumMap<>(UnitKind.class);
        for (PhaseSpec phase : PHASES) {
            for (UnitSlot slot : phase.slots()) {
                if (slot.unit().normal()) {
                    counts.merge(slot.unit(), 1, Integer::sum);
                }
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private static Map<GateId, GateSpec> createGates() {
        LinkedHashMap<GateId, GateSpec> gates = new LinkedHashMap<>();
        addGate(gates, GateId.G1, -137, -137, 82, 89, -8, 8);
        addGate(gates, GateId.G2, -89, -89, 64, 78, -14, 14);
        addGate(gates, GateId.G3, -32, -32, 72, 82, -13, 14);
        addGate(gates, GateId.G4, 24, 24, 72, 82, -13, 14);
        addGate(gates, GateId.G5, 72, 72, 72, 84, -8, 8);
        addGate(gates, GateId.INTERNAL_SHUTTER, 58, 58, 72, 84, -8, 8);
        return Collections.unmodifiableMap(gates);
    }

    private static void addGate(Map<GateId, GateSpec> gates, GateId id,
                                int minX, int maxX, int minY, int maxY,
                                int minZ, int maxZ) {
        gates.put(id, new GateSpec(id,
                new BlockVolume(minX, maxX, minY, maxY, minZ, maxZ)));
    }

    private static List<PhaseSpec> createPhases() {
        List<PhaseSpec> phases = new ArrayList<>();

        phases.add(new PhaseSpec(
                PhaseId.DROP_DECK,
                "BREACH WEST GATE",
                Trigger.buildMounted(),
                GateId.G1,
                GateId.G1,
                List.of(
                        slot("p1-rammer-north", UnitKind.PMS01_RAMMER, -150, 83, -14,
                                StagingBeat.LAUNCH_BAY_SHUTTER, true, false),
                        slot("p1-rammer-south", UnitKind.PMS01_RAMMER, -150, 83, 14,
                                StagingBeat.LAUNCH_BAY_SHUTTER, true, false),
                        slot("p1-rifle", UnitKind.PMS03_RIFLE_MT, -141, 83, 0,
                                StagingBeat.LAUNCH_BAY_SHUTTER, true, false)),
                new BlockPoint(-152, 83, 0),
                null));

        phases.add(new PhaseSpec(
                PhaseId.FREIGHT_CANYON,
                "BREAK THE FIRE LANE",
                Trigger.xAtLeast(-132),
                GateId.G2,
                GateId.G2,
                List.of(
                        slot("p2-rifle-north", UnitKind.PMS03_RIFLE_MT, -112, 65, -24,
                                StagingBeat.CARGO_POD, true, false),
                        slot("p2-rifle-south", UnitKind.PMS03_RIFLE_MT, -112, 65, 24,
                                StagingBeat.CARGO_POD, true, false),
                        slot("p2-missile", UnitKind.PMS04_MISSILE_MT, -96, 65, 0,
                                StagingBeat.CARGO_POD, true, false)),
                new BlockPoint(-116, 65, 0),
                null));

        phases.add(new PhaseSpec(
                PhaseId.WEST_SPAN,
                "CLEAR THE AIRSPACE",
                Trigger.xAtLeast(-84),
                GateId.G3,
                GateId.G3,
                List.of(
                        slot("p3-wasp", UnitKind.PMS02_WASP, -48, 54, 0,
                                StagingBeat.TRENCH_RISE, true, false),
                        slot("p3-missile", UnitKind.PMS04_MISSILE_MT, -42, 73, -9,
                                StagingBeat.WEST_WINDBREAK, true, false),
                        slot("p3-rifle", UnitKind.PMS03_RIFLE_MT, -42, 73, 9,
                                StagingBeat.WEST_WINDBREAK, true, false)),
                new BlockPoint(-56, 73, 0),
                null));

        phases.add(new PhaseSpec(
                PhaseId.EAST_SPAN,
                "CRACK THE BARRICADE",
                Trigger.xAtLeast(-28),
                GateId.G4,
                GateId.G4,
                List.of(
                        slot("p4-bombard", UnitKind.PMS05_BOMBARD, 16, 73, 0,
                                StagingBeat.EAST_BARRICADE, true, false),
                        slot("p4-rammer-north", UnitKind.PMS01_RAMMER, 4, 73, -8,
                                StagingBeat.EAST_BARRICADE, true, false),
                        slot("p4-rammer-south", UnitKind.PMS01_RAMMER, 4, 73, 8,
                                StagingBeat.EAST_BARRICADE, true, false),
                        slot("p4-rifle", UnitKind.PMS03_RIFLE_MT, 18, 73, 9,
                                StagingBeat.EAST_BARRICADE, true, false)),
                new BlockPoint(-4, 73, 0),
                null));

        phases.add(new PhaseSpec(
                PhaseId.GATEHOUSE_ESCORTS,
                "ACE ESCORTS INBOUND",
                Trigger.xAtLeast(28),
                GateId.G5,
                GateId.INTERNAL_SHUTTER,
                List.of(
                        slot("p5a-roller-north", UnitKind.PMS07_SAW_ROLLER, 48, 73, -18,
                                StagingBeat.GATEHOUSE_SHUTTER_NICHE, true, false),
                        slot("p5a-roller-south", UnitKind.PMS07_SAW_ROLLER, 48, 73, 18,
                                StagingBeat.GATEHOUSE_SHUTTER_NICHE, true, false)),
                new BlockPoint(44, 73, 0),
                null));

        phases.add(new PhaseSpec(
                PhaseId.GATEKEEPER,
                "RIVAL SIGNAL: GATEKEEPER R-01",
                Trigger.priorPhaseExactZero(),
                GateId.INTERNAL_SHUTTER,
                null,
                List.of(slot("p5b-gatekeeper", UnitKind.GATEKEEPER_R01, 64, 73, 0,
                        StagingBeat.RIVAL_INTERNAL_SHUTTER, true, true)),
                new BlockPoint(56, 73, 0),
                null));

        phases.add(new PhaseSpec(
                PhaseId.POWER_DECK,
                "SPAN WARDEN ONLINE",
                Trigger.serviceThenXAtLeast(88),
                null,
                null,
                List.of(slot("p6-span-warden", UnitKind.PMB04_SPAN_WARDEN, 144, 73, 0,
                        StagingBeat.BOSS_BOOT_REVEAL, true, true)),
                new BlockPoint(128, 73, 0),
                new BlockPoint(92, 73, 0)));

        return List.copyOf(phases);
    }

    private static UnitSlot slot(String id, UnitKind unit, int x, int y, int z,
                                 StagingBeat stagingBeat, boolean inactive,
                                 boolean cameraExempt) {
        return new UnitSlot(id, unit, new BlockPoint(x, y, z), Facing.WEST,
                stagingBeat, inactive, cameraExempt);
    }

    private static Map<PhaseId, PhaseSpec> indexPhases() {
        EnumMap<PhaseId, PhaseSpec> index = new EnumMap<>(PhaseId.class);
        for (PhaseSpec phase : PHASES) {
            if (index.put(phase.id(), phase) != null) {
                throw new IllegalStateException("duplicate Ashen Span phase " + phase.id());
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static void validateLockedContract() {
        if (CONTENT_BOUNDS.width() != 352 || CONTENT_BOUNDS.depth() != 224
                || CONTENT_CHUNKS.count() != 308) {
            throw new IllegalStateException("authored content bounds drifted");
        }
        if (SAFETY_BLOCK_BOUNDS.width() != 544 || SAFETY_BLOCK_BOUNDS.depth() != 320
                || SAFETY_CHUNKS.count() != 680) {
            throw new IllegalStateException("safety envelope drifted");
        }
        if (PHASES.size() != PhaseId.values().length || normalRootCount() != EXPECTED_NORMAL_ROOTS) {
            throw new IllegalStateException("authored phase roster drifted");
        }
        for (PhaseSpec phase : PHASES) {
            if (phase.normalRootCount() > MAX_ACTIVE_NORMALS) {
                throw new IllegalStateException("normal phase exceeds authored active cap: " + phase.id());
            }
        }
        if (!phase(PhaseId.GATEKEEPER).exclusiveDuel()
                || phase(PhaseId.GATEKEEPER).slots().get(0).unit() != UnitKind.GATEKEEPER_R01
                || !phase(PhaseId.POWER_DECK).exclusiveDuel()
                || phase(PhaseId.POWER_DECK).slots().get(0).unit() != UnitKind.PMB04_SPAN_WARDEN) {
            throw new IllegalStateException("authored duel phase drifted");
        }
    }
}
