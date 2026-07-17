package grcmcs.minecraft.mods.pomkotsmechs.arena;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure deterministic state machine for the authored Ashen Span encounter order.
 * World mutation, entity admission, and gate ledgers remain director concerns;
 * this model decides only when an already-admitted phase is allowed to activate
 * or advance.
 */
public final class AshenSpanMissionModel {
    public static final int GARAGE_BUILD_COUNT = 6;

    public enum Status {
        GARAGE,
        STAGING,
        WAITING_FOR_TRIGGER,
        ACTIVE,
        SERVICE,
        VICTORY,
        DEFEAT
    }

    public enum Outcome {
        RUNNING,
        VICTORY,
        DEFEAT
    }

    public enum DefeatReason {
        NONE,
        PLAYER_DESTROYED,
        MECH_DESTROYED,
        DISCONNECTED,
        STOPPED,
        BOSS_RECOVERY_EXHAUSTED,
        TIME_LIMIT,
        MISSION_ABORTED,
        PILOT_SEPARATED
    }

    /**
     * Arena-owned hostile population for the current phase. A phase is clear
     * only when roots and every tracked descendant category are exactly zero.
     */
    public record HostileCounts(
            int roots,
            int projectiles,
            int effects,
            int supplementalHitboxes) {
        public static final HostileCounts ZERO = new HostileCounts(0, 0, 0, 0);

        public HostileCounts {
            if (roots < 0 || projectiles < 0 || effects < 0 || supplementalHitboxes < 0) {
                throw new IllegalArgumentException("hostile counts cannot be negative");
            }
        }

        public long descendants() {
            return (long) projectiles + effects + supplementalHitboxes;
        }

        public boolean exactZero() {
            return roots == 0 && descendants() == 0L;
        }
    }

    /**
     * One server-tick observation. Loss flags are intentionally colocated with
     * hostile counts so double-KO ordering is decided in one deterministic step.
     */
    public record TickInput(
            double playerX,
            HostileCounts hostiles,
            boolean playerAlive,
            boolean mechAlive,
            boolean connected,
            boolean stopRequested,
            boolean bossRecoveryExhausted) {
        public TickInput {
            if (!Double.isFinite(playerX)) {
                throw new IllegalArgumentException("player x must be finite");
            }
            Objects.requireNonNull(hostiles, "hostiles");
        }

        public static TickInput healthy(double playerX, HostileCounts hostiles) {
            return new TickInput(playerX, hostiles, true, true, true, false, false);
        }
    }

    public record Snapshot(
            long seed,
            int selectedBuild,
            Status status,
            Outcome outcome,
            DefeatReason defeatReason,
            AshenSpanDefinition.PhaseId currentPhase,
            String objective,
            int expectedRootCount,
            int admittedRootCount,
            boolean triggerSatisfied,
            boolean serviceCompleted,
            Set<AshenSpanDefinition.GateId> openedGates) {
        public Snapshot {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(defeatReason, "defeatReason");
            Objects.requireNonNull(objective, "objective");
            openedGates = Set.copyOf(openedGates);
        }
    }

    private final long seed;
    private final int selectedBuild;
    private final EnumSet<AshenSpanDefinition.GateId> openedGates =
            EnumSet.noneOf(AshenSpanDefinition.GateId.class);

    private Status status = Status.GARAGE;
    private Outcome outcome = Outcome.RUNNING;
    private DefeatReason defeatReason = DefeatReason.NONE;
    private int currentPhaseIndex = -1;
    private int admittedRootCount;
    private boolean triggerSatisfied;
    private boolean serviceCompleted;

    /**
     * @param selectedBuild zero-based Garage Fleet build index (0..5)
     */
    public AshenSpanMissionModel(long seed, int selectedBuild) {
        if (selectedBuild < 0 || selectedBuild >= GARAGE_BUILD_COUNT) {
            throw new IllegalArgumentException("selected Garage Fleet build must be in 0..5");
        }
        this.seed = seed;
        this.selectedBuild = selectedBuild;
    }

    public long seed() {
        return seed;
    }

    public int selectedBuild() {
        return selectedBuild;
    }

    public Status status() {
        return status;
    }

    public Outcome outcome() {
        return outcome;
    }

    public DefeatReason defeatReason() {
        return defeatReason;
    }

    public Optional<AshenSpanDefinition.PhaseSpec> currentPhase() {
        if (currentPhaseIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(AshenSpanDefinition.phases().get(currentPhaseIndex));
    }

    public Set<AshenSpanDefinition.GateId> openedGates() {
        return Collections.unmodifiableSet(EnumSet.copyOf(openedGates));
    }

    public boolean serviceCompleted() {
        return serviceCompleted;
    }

    public boolean isTerminal() {
        return status == Status.VICTORY || status == Status.DEFEAT;
    }

    /**
     * Records the already-selected build's successful spawn and mount. The
     * phase still cannot run until its exact authored root count is admitted.
     */
    public Snapshot onBuildMounted() {
        requireStatus(Status.GARAGE, "build can only mount from the garage");
        currentPhaseIndex = 0;
        triggerSatisfied = true;
        status = Status.STAGING;
        return snapshot();
    }

    /**
     * Confirms that every authored root for the current phase was registered
     * before entity ADD. Partial or additional admissions fail closed.
     */
    public Snapshot admitCurrentPhase(int admittedRoots) {
        requireStatus(Status.STAGING, "phase roots can only be admitted while staging");
        AshenSpanDefinition.PhaseSpec phase = requiredPhase();
        if (admittedRoots != phase.expectedRootCount()) {
            throw new IllegalArgumentException("phase " + phase.id() + " requires exactly "
                    + phase.expectedRootCount() + " admitted roots, got " + admittedRoots);
        }
        admittedRootCount = admittedRoots;
        status = triggerSatisfied ? Status.ACTIVE : Status.WAITING_FOR_TRIGGER;
        return snapshot();
    }

    /**
     * Applies a single ordered mission observation. Player/mech loss is always
     * evaluated before boss clear, so a same-tick double KO is defeat.
     */
    public Snapshot observe(TickInput input) {
        Objects.requireNonNull(input, "input");
        if (isTerminal()) {
            return snapshot();
        }

        DefeatReason loss = lossReason(input);
        if (loss != DefeatReason.NONE) {
            defeat(loss);
            return snapshot();
        }

        if (currentPhaseIndex < 0 || status == Status.SERVICE) {
            return snapshot();
        }

        AshenSpanDefinition.PhaseSpec phase = requiredPhase();
        if ((status == Status.STAGING || status == Status.WAITING_FOR_TRIGGER)
                && phase.trigger().positionSatisfied(input.playerX())) {
            triggerSatisfied = true;
        }
        if (status == Status.WAITING_FOR_TRIGGER && triggerSatisfied) {
            status = Status.ACTIVE;
        }

        if (status == Status.ACTIVE
                && admittedRootCount == phase.expectedRootCount()
                && input.hostiles().exactZero()) {
            advanceAfterExactZero(phase);
        }
        return snapshot();
    }

    /**
     * Completes the one authored service. Repeated calls after completion are a
     * deterministic no-op and cannot duplicate a template inventory refill.
     */
    public boolean completeService() {
        if (serviceCompleted) {
            return false;
        }
        requireStatus(Status.SERVICE, "service is only available after Gatekeeper cleanup");
        if (requiredPhase().id() != AshenSpanDefinition.PhaseId.POWER_DECK) {
            throw new IllegalStateException("service did not stage the Power Deck phase");
        }
        serviceCompleted = true;
        openedGates.add(AshenSpanDefinition.GateId.G5);
        status = Status.STAGING;
        triggerSatisfied = false;
        return true;
    }

    /**
     * Creates a clean retry with the identical internal build index and seed.
     */
    public AshenSpanMissionModel retry() {
        if (!isTerminal()) {
            throw new IllegalStateException("retry requires a terminal mission outcome");
        }
        return new AshenSpanMissionModel(seed, selectedBuild);
    }

    /** Records an Arena-side terminal condition that happened outside director tick. */
    public Snapshot forceDefeat(DefeatReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason == DefeatReason.NONE) {
            throw new IllegalArgumentException("a concrete defeat reason is required");
        }
        if (!isTerminal()) {
            defeat(reason);
        }
        return snapshot();
    }

    public Snapshot snapshot() {
        AshenSpanDefinition.PhaseSpec phase = currentPhaseIndex < 0
                ? null
                : AshenSpanDefinition.phases().get(currentPhaseIndex);
        return new Snapshot(
                seed,
                selectedBuild,
                status,
                outcome,
                defeatReason,
                phase == null ? null : phase.id(),
                phase == null ? "" : phase.objective(),
                phase == null ? 0 : phase.expectedRootCount(),
                admittedRootCount,
                triggerSatisfied,
                serviceCompleted,
                openedGates);
    }

    private DefeatReason lossReason(TickInput input) {
        if (!input.playerAlive()) {
            return DefeatReason.PLAYER_DESTROYED;
        }
        if (!input.mechAlive()) {
            return DefeatReason.MECH_DESTROYED;
        }
        if (!input.connected()) {
            return DefeatReason.DISCONNECTED;
        }
        if (input.stopRequested()) {
            return DefeatReason.STOPPED;
        }
        if (input.bossRecoveryExhausted()
                && currentPhaseIndex >= 0
                && requiredPhase().id() == AshenSpanDefinition.PhaseId.POWER_DECK) {
            return DefeatReason.BOSS_RECOVERY_EXHAUSTED;
        }
        return DefeatReason.NONE;
    }

    private void advanceAfterExactZero(AshenSpanDefinition.PhaseSpec phase) {
        if (phase.opensAfterClear() != null) {
            openedGates.add(phase.opensAfterClear());
        }
        admittedRootCount = 0;

        if (phase.id() == AshenSpanDefinition.PhaseId.POWER_DECK) {
            status = Status.VICTORY;
            outcome = Outcome.VICTORY;
            defeatReason = DefeatReason.NONE;
            return;
        }

        currentPhaseIndex++;
        AshenSpanDefinition.PhaseSpec next = requiredPhase();
        triggerSatisfied = next.trigger().kind()
                == AshenSpanDefinition.TriggerKind.PRIOR_PHASE_EXACT_ZERO;

        if (phase.id() == AshenSpanDefinition.PhaseId.GATEKEEPER) {
            status = Status.SERVICE;
        } else {
            status = Status.STAGING;
        }
    }

    private void defeat(DefeatReason reason) {
        status = Status.DEFEAT;
        outcome = Outcome.DEFEAT;
        defeatReason = reason;
        admittedRootCount = 0;
        triggerSatisfied = false;
    }

    private AshenSpanDefinition.PhaseSpec requiredPhase() {
        if (currentPhaseIndex < 0 || currentPhaseIndex >= AshenSpanDefinition.phases().size()) {
            throw new IllegalStateException("Ashen Span has no current authored phase");
        }
        return AshenSpanDefinition.phases().get(currentPhaseIndex);
    }

    private void requireStatus(Status expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + " (status=" + status + ")");
        }
    }
}
