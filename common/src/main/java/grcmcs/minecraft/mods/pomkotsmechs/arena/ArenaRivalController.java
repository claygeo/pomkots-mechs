package grcmcs.minecraft.mods.pomkotsmechs.arena;

/**
 * Bounded, deterministic combat policy for Gatekeeper R-01. World queries and motion
 * application stay in the entity; this class contains only the authored decision state
 * so it can be replayed and unit-tested without a running client or navigation system.
 */
public final class ArenaRivalController {
    public static final double MAX_TARGET_DISTANCE = 150.0D;
    public static final double MAX_TARGET_DISTANCE_SQUARED = MAX_TARGET_DISTANCE * MAX_TARGET_DISTANCE;

    public enum Tactic {
        INACTIVE,
        HOLD,
        CLOSE_RIFLE,
        STRAFE_RIFLE,
        SMG_BURST,
        RETREAT
    }

    public enum Weapon {
        NONE,
        RIGHT_HAND_RIFLE,
        LEFT_HAND_SMG,
        RIGHT_SHOULDER_SUWA
    }

    public record Decision(Tactic tactic, float forward, float strafe, Weapon weapon,
                           boolean evade, boolean releasedForTransition) {
        public Decision withSafety(boolean pathSupported, boolean evasionPathSupported) {
            return new Decision(tactic,
                    pathSupported ? forward : 0.0F,
                    pathSupported ? strafe : 0.0F,
                    weapon,
                    evade && pathSupported && evasionPathSupported,
                    releasedForTransition);
        }

        public static Decision released(Tactic tactic, boolean transition) {
            return new Decision(tactic, 0.0F, 0.0F, Weapon.NONE, false, transition);
        }
    }

    private long seed;
    private int tick;
    private int nextRifleTick;
    private int smgBurstRemaining;
    private int smgCooldownRemaining;
    private int suwaBurstRemaining;
    private int nextSuwaTick;
    private int nextEvadeTick;
    private long decisionOrdinal;
    private Tactic tactic = Tactic.INACTIVE;

    public void activate(long missionSeed) {
        this.seed = missionSeed;
        this.tick = 0;
        this.decisionOrdinal = 0;
        this.tactic = Tactic.INACTIVE;
        this.smgBurstRemaining = 0;
        this.smgCooldownRemaining = 0;
        this.suwaBurstRemaining = 0;
        this.nextRifleTick = range(18, 30);
        this.nextSuwaTick = range(70, 110);
        this.nextEvadeTick = range(80, 120);
    }

    public void reset() {
        this.tick = 0;
        this.tactic = Tactic.INACTIVE;
        this.smgBurstRemaining = 0;
        this.smgCooldownRemaining = 0;
        this.suwaBurstRemaining = 0;
        this.nextRifleTick = Integer.MAX_VALUE;
        this.nextSuwaTick = Integer.MAX_VALUE;
        this.nextEvadeTick = Integer.MAX_VALUE;
    }

    public Tactic tactic() {
        return tactic;
    }

    public Decision tick(double distance, double healthRatio, boolean validTarget) {
        tick++;

        if (!validTarget) {
            boolean changed = tactic != Tactic.HOLD;
            tactic = Tactic.HOLD;
            clearContinuousWindows();
            return Decision.released(Tactic.HOLD, changed);
        }

        boolean coolingSmgThisTick = smgCooldownRemaining > 0;
        if (coolingSmgThisTick) {
            smgCooldownRemaining--;
        }

        Tactic requested = tacticForDistance(distance);
        if (requested != tactic) {
            tactic = requested;
            clearContinuousWindows();
            return movementDecision(requested, Weapon.NONE, false, true);
        }

        Weapon weapon = Weapon.NONE;

        // Suwa pressure is an occasional short window, never concurrent with a hand
        // weapon. It is unavailable at exactly 50%, as required by "below 50%".
        if (healthRatio < 0.5D && smgBurstRemaining == 0) {
            if (suwaBurstRemaining == 0 && tick >= nextSuwaTick) {
                suwaBurstRemaining = range(8, 12);
            }
            if (suwaBurstRemaining > 0) {
                weapon = Weapon.RIGHT_SHOULDER_SUWA;
                suwaBurstRemaining--;
                if (suwaBurstRemaining == 0) {
                    nextSuwaTick = tick + range(75, 110);
                }
            }
        }

        if (weapon == Weapon.NONE && tactic == Tactic.SMG_BURST) {
            if (smgBurstRemaining == 0 && smgCooldownRemaining == 0 && !coolingSmgThisTick) {
                smgBurstRemaining = range(10, 14);
            }
            if (smgBurstRemaining > 0) {
                weapon = Weapon.LEFT_HAND_SMG;
                smgBurstRemaining--;
                if (smgBurstRemaining == 0) {
                    smgCooldownRemaining = range(45, 70);
                }
            }
        }

        if (weapon == Weapon.NONE
                && (tactic == Tactic.CLOSE_RIFLE || tactic == Tactic.STRAFE_RIFLE)
                && tick >= nextRifleTick) {
            weapon = Weapon.RIGHT_HAND_RIFLE;
            nextRifleTick = tick + range(30, 45);
        }

        boolean evade = tactic == Tactic.RETREAT && tick >= nextEvadeTick;
        if (evade) {
            nextEvadeTick = tick + range(80, 120);
        }

        return movementDecision(tactic, weapon, evade, false);
    }

    private Decision movementDecision(Tactic current, Weapon weapon, boolean evade, boolean transition) {
        float forward = 0.0F;
        float strafe = 0.0F;
        switch (current) {
            case CLOSE_RIFLE -> forward = 0.60F;
            case STRAFE_RIFLE -> strafe = strafeDirection() * 0.60F;
            case SMG_BURST -> strafe = strafeDirection() * 0.40F;
            case RETREAT -> forward = -0.60F;
            default -> {
            }
        }
        return new Decision(current, forward, strafe, weapon, evade, transition);
    }

    private int strafeDirection() {
        long epoch = tick / 80L;
        return deterministicRange(seed, 10_000L + epoch, 0, 1) == 0 ? -1 : 1;
    }

    private void clearContinuousWindows() {
        smgBurstRemaining = 0;
        suwaBurstRemaining = 0;
    }

    private int range(int minInclusive, int maxInclusive) {
        return deterministicRange(seed, decisionOrdinal++, minInclusive, maxInclusive);
    }

    public static Tactic tacticForDistance(double distance) {
        if (distance < 14.0D) {
            return Tactic.RETREAT;
        }
        // The authored 14-28 SMG band wins the intentional overlap with the
        // 22-48 rifle band; rifle strafing resumes above 28.
        if (distance < 28.0D) {
            return Tactic.SMG_BURST;
        }
        if (distance <= 48.0D) {
            return Tactic.STRAFE_RIFLE;
        }
        return Tactic.CLOSE_RIFLE;
    }

    public static boolean targetContract(boolean alive, boolean sameDimension,
                                         boolean activePlayerMech, double distanceSquared,
                                         boolean lineOfSight) {
        return alive && sameDimension && activePlayerMech && lineOfSight
                && distanceSquared <= MAX_TARGET_DISTANCE_SQUARED;
    }

    public static int deterministicRange(long seed, long ordinal,
                                         int minInclusive, int maxInclusive) {
        if (maxInclusive < minInclusive) {
            throw new IllegalArgumentException("max must be >= min");
        }
        long mixed = seed + 0x9E3779B97F4A7C15L * (ordinal + 1L);
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        int bound = maxInclusive - minInclusive + 1;
        return minInclusive + (int) Math.floorMod(mixed, (long) bound);
    }
}
