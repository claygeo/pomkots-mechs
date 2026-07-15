package grcmcs.minecraft.mods.pomkotsmechs.arena;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss.BaseBossEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.mob.BaseSmallMonsterEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic encounter controller for SOLO. ArenaManager owns the match,
 * fighter, and cleanup; this class owns only encounter pacing and enemy UUIDs.
 *
 * <pre>
 * catalog -> WARMUP -> WAVE 1 -> warning -> WAVE 2 -> warning -> WAVE 3
 *                                                               |
 *                                                        boss warning
 *                                                               v
 *                                                        BOSS -> VICTORY
 *
 * spawn request = seed + phase + ordinal -> weighted unit + <=32 candidates
 * </pre>
 */
final class SpawnDirector {
    enum TickResult {
        RUNNING,
        VICTORY,
        FAILED
    }

    enum Placement {
        GROUND,
        AIR
    }

    record UnitSpec(
            String entityId,
            String role,
            int cost,
            int weight,
            Placement placement,
            int verticalOffset,
            boolean boss) {
    }

    record WaveSpec(String id, int budget, int unitCap, List<UnitSpec> roster) {
        WaveSpec {
            roster = List.copyOf(roster);
        }
    }

    record BossSpec(
            String id,
            int warningTicks,
            double minSpawnDistance,
            double maxSpawnDistance,
            UnitSpec unit) {
    }

    record Catalog(
            int schema,
            double minSpawnDistance,
            double maxSpawnDistance,
            double forwardConeDegrees,
            double clusterSeparation,
            double leashDistance,
            double movementThreshold,
            int initialWarningTicks,
            int waveWarningTicks,
            int reconcileTicks,
            int unloadedRecoveryTicks,
            int stuckAuditTicks,
            int stuckWindowTicks,
            int maxCandidateAttempts,
            int candidateAttemptsPerTick,
            int maxCommitRetries,
            int unitCap,
            int threatCap,
            List<WaveSpec> waves,
            BossSpec boss) {
        Catalog {
            waves = List.copyOf(waves);
        }
    }

    record SpawnOffset(double x, double z) {
    }

    enum RecoveryAction {
        RELOCATE,
        REPLACE,
        RETIRE
    }

    /**
     * Immutable recovery budget for one planned roster slot. Replacements keep
     * the same slot ordinal, so bad terrain can never create an unbounded chain
     * of fresh enemies.
     */
    record RecoveryLineage(long slotOrdinal, int relocationsUsed, int replacementsUsed) {
        RecoveryLineage {
            if (slotOrdinal < 0L || relocationsUsed < 0 || relocationsUsed > 1
                    || replacementsUsed < 0 || replacementsUsed > 1) {
                throw new IllegalArgumentException("invalid recovery lineage");
            }
        }

        static RecoveryLineage initial(long slotOrdinal) {
            return new RecoveryLineage(slotOrdinal, 0, 0);
        }

        RecoveryAction nextAction() {
            if (relocationsUsed == 0) {
                return RecoveryAction.RELOCATE;
            }
            if (replacementsUsed == 0) {
                return RecoveryAction.REPLACE;
            }
            return RecoveryAction.RETIRE;
        }

        RecoveryLineage afterRelocation() {
            if (relocationsUsed != 0) {
                throw new IllegalStateException("slot relocation budget is exhausted");
            }
            return new RecoveryLineage(slotOrdinal, 1, replacementsUsed);
        }

        RecoveryLineage afterReplacement() {
            if (replacementsUsed != 0) {
                throw new IllegalStateException("slot replacement budget is exhausted");
            }
            return new RecoveryLineage(slotOrdinal, relocationsUsed, 1);
        }
    }

    private record SpawnPoint(double x, double y, double z) {
    }

    private static final class SpawnRequest {
        final UnitSpec spec;
        final int phaseIndex;
        final RecoveryLineage lineage;
        final double minDistance;
        final double maxDistance;
        final Random candidates;
        int candidateAttempts;
        int commitFailures;

        SpawnRequest(UnitSpec spec, int phaseIndex, RecoveryLineage lineage,
                     double minDistance, double maxDistance, long runSeed, long salt) {
            this.spec = spec;
            this.phaseIndex = phaseIndex;
            this.lineage = lineage;
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
            long identity = runSeed ^ ((long) phaseIndex << 32) ^ lineage.slotOrdinal();
            this.candidates = new Random(mixSeed(identity, salt));
        }
    }

    private static final class TrackedEnemy {
        final UnitSpec spec;
        final int phaseIndex;
        final double minDistance;
        final double maxDistance;
        RecoveryLineage lineage;
        BlockPos lastBlock;
        double auditX;
        double auditZ;
        int stationaryTicks;
        int unresolvedTicks;
        SpawnRequest recovery;
        Entity deathCandidate;

        TrackedEnemy(SpawnRequest request, Entity entity) {
            spec = request.spec;
            phaseIndex = request.phaseIndex;
            minDistance = request.minDistance;
            maxDistance = request.maxDistance;
            lineage = request.lineage;
            observe(entity);
        }

        void observe(Entity entity) {
            lastBlock = entity.blockPosition();
            auditX = entity.getX();
            auditZ = entity.getZ();
        }
    }

    private static final ResourceLocation CATALOG_ID =
            new ResourceLocation("pomkotsmechs", "arena/solo_encounters.json");
    private static final String BUNDLED_CATALOG = "/data/pomkotsmechs/arena/solo_encounters.json";
    private static final String TAG_ARENA = "mecharena";
    private static final String TAG_MATCH_PREFIX = "mecharena_match_";
    private static final String TAG_PVE = "mecharena_pve";
    private static final String TAG_SOLO = "mecharena_solo";
    private static final double MIN_STUCK_TARGET_DISTANCE_SQR = 24.0 * 24.0;
    private static final int RECENT_SPAWN_LIMIT = 8;
    private static final int MAX_ROSTER_ENTRIES = 32;
    private static final int MAX_UNIT_WEIGHT = 10_000;
    private static final int MAX_PLANNED_UNITS = 64;
    private static final double SPAWN_FEASIBILITY_MARGIN = 4.0;
    private static final double PLAYER_PURSUIT_MARGIN = 8.0;
    private static final int PRIORITY_CANDIDATE_COUNT = 5;
    private static final long ROSTER_SALT = 0x6A09E667F3BCC909L;
    private static final long CANDIDATE_SALT = 0xBB67AE8584CAA73BL;
    private static final long RECOVERY_SALT = 0x3C6EF372FE94F82BL;
    private static final long REPLACEMENT_SALT = 0xA54FF53A5F1D36F1L;

    private final Deque<SpawnRequest> pending = new ArrayDeque<>();
    private final LinkedHashMap<UUID, TrackedEnemy> owned = new LinkedHashMap<>();
    private final Deque<Vec3> recentSpawns = new ArrayDeque<>();
    private final List<String> messages = new ArrayList<>();

    private Catalog catalog;
    private UUID fighterId;
    private long seed;
    private int matchId;
    private double centerX;
    private double centerY;
    private double centerZ;
    private double boundsRadius;
    private int tickCount;
    private int phaseIndex;
    private long nextSpawnOrdinal;
    private int warningRemaining;
    private boolean prepared;
    private boolean phaseStarted;
    private boolean forceNextRequested;
    private boolean replacementQueuedThisTick;
    private boolean bossDefeated;
    private boolean victory;
    private String failureReason = "";

    boolean prepare(ServerLevel level, int matchId, long seed, UUID fighterId,
                    double centerX, double centerY, double centerZ, double boundsRadius) {
        reset();
        if (level == null) {
            return failPrepare("server level is unavailable");
        }
        if (fighterId == null) {
            return failPrepare("fighter UUID is missing");
        }
        if (!finite(centerX) || !finite(centerY) || !finite(centerZ)
                || !finite(boundsRadius) || boundsRadius <= 0.0) {
            return failPrepare("arena center or bounds are invalid");
        }

        Exception overrideFailure = null;
        try (Reader reader = level.getServer().getResourceManager()
                .getResource(CATALOG_ID)
                .orElseThrow(() -> new IllegalArgumentException("missing " + CATALOG_ID))
                .openAsReader()) {
            catalog = parseCatalog(reader);
            validateEntityTypes(level, catalog);
        } catch (Exception ex) {
            overrideFailure = ex;
            try (InputStream input = SpawnDirector.class.getResourceAsStream(BUNDLED_CATALOG)) {
                if (input == null) {
                    throw new IllegalArgumentException("bundled catalog is missing");
                }
                catalog = parseCatalog(new InputStreamReader(input, StandardCharsets.UTF_8));
                validateEntityTypes(level, catalog);
            } catch (Exception bundledFailure) {
                return failPrepare("encounter catalog rejected: " + concise(bundledFailure));
            }
        }

        if (overrideFailure != null) {
            messages.add("SOLO catalog override rejected; using built-in: " + concise(overrideFailure) + ".");
        }
        if (!leashCoversPlayerBounds(boundsRadius, catalog.leashDistance(),
                PLAYER_PURSUIT_MARGIN)) {
            return failPrepare("enemy leash must extend beyond the legal player bounds");
        }
        if (!spawnRingCanReachLeash(boundsRadius, catalog.leashDistance(),
                catalog.minSpawnDistance(), SPAWN_FEASIBILITY_MARGIN)) {
            return failPrepare("arena edge cannot reach the configured spawn leash");
        }

        this.matchId = matchId;
        this.seed = seed;
        this.fighterId = fighterId;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.boundsRadius = boundsRadius;
        this.phaseIndex = -1;
        this.warningRemaining = catalog.initialWarningTicks();
        this.prepared = true;
        messages.add("SOLO encounter ready — seed " + seed + ", "
                + catalog.waves().size() + " waves plus boss.");
        messages.add("INITIAL CONTACT IN " + seconds(catalog.initialWarningTicks()) + "s.");
        return true;
    }

    TickResult tick(ServerLevel level, ServerPlayer player, LivingEntity playerMech) {
        if (!prepared || level == null || player == null) {
            failRuntime("director ticked before a valid prepare");
            return TickResult.FAILED;
        }
        if (!failureReason.isEmpty()) {
            return TickResult.FAILED;
        }
        if (victory) {
            return TickResult.VICTORY;
        }
        if (!player.getUUID().equals(fighterId)) {
            failRuntime("fighter identity changed during the run");
            return TickResult.FAILED;
        }

        tickCount++;
        replacementQueuedThisTick = false;
        LivingEntity target = playerMech != null && playerMech.isAlive() ? playerMech : player;

        if (forceNextRequested) {
            applyForceNext(level);
            forceNextRequested = false;
        }
        if (victory) {
            return TickResult.VICTORY;
        }

        confirmDeathCandidates();

        if (tickCount % catalog.reconcileTicks() == 0) {
            reconcile(level, target);
        }
        if (tickCount % catalog.stuckAuditTicks() == 0) {
            auditStuckAndLeash(level, target);
        }
        recoverOneIfNeeded(level, player, target);
        if (!failureReason.isEmpty()) {
            return TickResult.FAILED;
        }

        if (phaseStarted && pending.isEmpty() && owned.isEmpty()) {
            if (phaseIndex == catalog.waves().size()) {
                if (bossClearResult(bossDefeated) != TickResult.VICTORY) {
                    failRuntime("boss disappeared without a confirmed defeat");
                    return TickResult.FAILED;
                }
                victory = true;
                messages.add("BOSS DESTROYED.");
                return TickResult.VICTORY;
            }
            phaseStarted = false;
            if (phaseIndex == catalog.waves().size() - 1) {
                warningRemaining = catalog.boss().warningTicks();
                messages.add("ALL WAVES CLEAR — BOSS IN " + seconds(warningRemaining) + "s.");
            } else {
                warningRemaining = catalog.waveWarningTicks();
                messages.add("WAVE " + (phaseIndex + 1) + " CLEAR — NEXT WAVE IN "
                        + seconds(warningRemaining) + "s.");
            }
        }

        if (!phaseStarted) {
            if (warningRemaining > 0) {
                warningRemaining--;
                if (warningRemaining > 0) {
                    return TickResult.RUNNING;
                }
            }
            if (!beginNextPhase(target)) {
                return TickResult.FAILED;
            }
        }

        if (!pending.isEmpty() && mayCommit(pending.peekFirst())) {
            tryCommitOne(level, player, target);
        }
        return failureReason.isEmpty() ? TickResult.RUNNING : TickResult.FAILED;
    }

    void onDeath(Entity entity) {
        if (entity != null) {
            TrackedEnemy tracked = owned.get(entity.getUUID());
            if (tracked != null) {
                // Architectury's living-death event is cancellable. Do not
                // release ownership until the next server tick proves that a
                // later listener did not keep the entity alive.
                tracked.deathCandidate = entity;
            }
        }
    }

    boolean owns(UUID uuid) {
        return uuid != null && owned.containsKey(uuid);
    }

    void forceNext() {
        if (prepared && !victory && failureReason.isEmpty()) {
            forceNextRequested = true;
        }
    }

    void reset() {
        pending.clear();
        owned.clear();
        recentSpawns.clear();
        messages.clear();
        catalog = null;
        fighterId = null;
        seed = 0L;
        matchId = 0;
        centerX = 0.0;
        centerY = 0.0;
        centerZ = 0.0;
        boundsRadius = 0.0;
        tickCount = 0;
        phaseIndex = -1;
        nextSpawnOrdinal = 0L;
        warningRemaining = 0;
        prepared = false;
        phaseStarted = false;
        forceNextRequested = false;
        replacementQueuedThisTick = false;
        bossDefeated = false;
        victory = false;
        failureReason = "";
    }

    String status() {
        if (!failureReason.isEmpty()) {
            return "FAILED — " + failureReason;
        }
        if (victory) {
            return "VICTORY — boss destroyed";
        }
        if (!prepared) {
            return "IDLE";
        }
        String phase;
        if (phaseIndex < 0) {
            phase = "warmup";
        } else if (phaseIndex < catalog.waves().size()) {
            phase = "wave " + (phaseIndex + 1) + "/" + catalog.waves().size();
        } else {
            phase = "boss";
        }
        String pacing = warningRemaining > 0
                ? "warning " + warningRemaining + "t"
                : "active " + owned.size() + ", queued " + pending.size();
        return phase + " — " + pacing + " — threat " + currentThreat()
                + "/" + catalog.threatCap() + " — seed " + seed;
    }

    String failureReason() {
        return failureReason;
    }

    List<String> drainMessages() {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<String> drained = List.copyOf(messages);
        messages.clear();
        return drained;
    }

    private boolean beginNextPhase(LivingEntity target) {
        phaseIndex++;
        nextSpawnOrdinal = 0L;
        if (phaseIndex < catalog.waves().size()) {
            WaveSpec wave = catalog.waves().get(phaseIndex);
            double healthRatio = target.getMaxHealth() > 0.0F
                    ? target.getHealth() / target.getMaxHealth()
                    : 1.0;
            int budget = adjustedBudget(wave.budget(), healthRatio, false);
            List<UnitSpec> plan;
            try {
                long rosterSeed = mixSeed(seed ^ phaseIndex, ROSTER_SALT);
                plan = planWave(wave, budget, new Random(rosterSeed));
            } catch (IllegalArgumentException ex) {
                failRuntime("wave " + (phaseIndex + 1) + " cannot be planned: " + concise(ex));
                return false;
            }
            for (UnitSpec unit : plan) {
                pending.addLast(newRequest(unit, catalog.minSpawnDistance(), catalog.maxSpawnDistance()));
            }
            String reduced = budget < wave.budget() ? " — low health budget " + budget : "";
            messages.add("WAVE " + (phaseIndex + 1) + "/" + catalog.waves().size()
                    + " — " + wave.id().replace('_', ' ') + reduced + ".");
        } else if (phaseIndex == catalog.waves().size()) {
            bossDefeated = false;
            BossSpec boss = catalog.boss();
            pending.addLast(newRequest(boss.unit(), boss.minSpawnDistance(), boss.maxSpawnDistance()));
            messages.add("BOSS CONTACT — " + boss.id().replace('_', ' ') + ".");
        } else {
            victory = true;
            return true;
        }
        if (pending.isEmpty()) {
            failRuntime("phase " + (phaseIndex + 1) + " produced an empty roster");
            return false;
        }
        phaseStarted = true;
        warningRemaining = 0;
        return true;
    }

    private SpawnRequest newRequest(UnitSpec unit, double minDistance, double maxDistance) {
        RecoveryLineage lineage = RecoveryLineage.initial(nextSpawnOrdinal++);
        return new SpawnRequest(unit, phaseIndex, lineage, minDistance, maxDistance,
                seed, CANDIDATE_SALT);
    }

    private boolean mayCommit(SpawnRequest next) {
        int phaseCap = phaseIndex < catalog.waves().size()
                ? catalog.waves().get(phaseIndex).unitCap()
                : 1;
        return withinActiveCaps(owned.size(), currentThreat(), next.spec.cost(),
                catalog.unitCap(), phaseCap, catalog.threatCap());
    }

    private int currentThreat() {
        int result = 0;
        for (TrackedEnemy enemy : owned.values()) {
            result += enemy.spec.cost();
        }
        return result;
    }

    private void tryCommitOne(ServerLevel level, ServerPlayer player, LivingEntity target) {
        SpawnRequest request = pending.peekFirst();
        Entity enemy = createEnemy(level, request.spec);
        if (enemy == null) {
            failRuntime("entity " + request.spec.entityId() + " did not create a living enemy");
            return;
        }

        SpawnPoint point = findSpawnPoint(level, player, enemy, request);
        if (point == null) {
            enemy.discard();
            if (request.candidateAttempts >= catalog.maxCandidateAttempts()) {
                if (request.lineage.replacementsUsed() >= 1) {
                    retirePendingReplacement(request, "unspawnable");
                } else {
                    failRuntime("no safe loaded spawn among " + request.candidateAttempts
                            + " candidates for " + request.spec.entityId());
                }
            }
            return;
        }

        enemy.moveTo(point.x(), point.y(), point.z(), request.candidates.nextFloat() * 360.0F, 0.0F);
        enemy.addTag(TAG_ARENA);
        enemy.addTag(TAG_MATCH_PREFIX + matchId);
        enemy.addTag(TAG_PVE);
        enemy.addTag(TAG_SOLO);
        LivingEntity living = (LivingEntity) enemy;
        if (living instanceof Mob mob) {
            mob.setPersistenceRequired();
            mob.setTarget(target);
        }
        if (living instanceof BaseSmallMonsterEntity smallMonster) {
            // BaseSmallMonsterEntity overrides vanilla's persistence query with
            // its own saved flag, so setPersistenceRequired() alone is ignored.
            smallMonster.setPersistence(true);
        }

        UUID uuid = enemy.getUUID();
        TrackedEnemy tracked = new TrackedEnemy(request, enemy);
        // EntityEvent.ADD fires inside addFreshEntity. The UUID must already be
        // owned when ArenaManager validates the four tags above.
        owned.put(uuid, tracked);
        if (!level.addFreshEntity(enemy)) {
            owned.remove(uuid);
            enemy.discard();
            request.commitFailures++;
            if (request.commitFailures >= catalog.maxCommitRetries()) {
                if (request.lineage.replacementsUsed() >= 1) {
                    retirePendingReplacement(request, "world-rejected");
                } else {
                    failRuntime("world rejected " + request.spec.entityId() + " after "
                            + request.commitFailures + " commits");
                }
            }
            return;
        }

        if (enemy instanceof BaseBossEntity boss) {
            boss.addHateToEntity(target, 1000);
        }
        pending.removeFirst();
        rememberSpawn(point);
    }

    private Entity createEnemy(ServerLevel level, UnitSpec spec) {
        ResourceLocation id = ResourceLocation.tryParse(spec.entityId());
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        Entity entity = type.create(level);
        boolean validCombatEntity = spec.boss()
                ? entity instanceof BaseBossEntity
                : entity instanceof BaseSmallMonsterEntity;
        if (validCombatEntity) {
            return entity;
        }
        if (entity != null) {
            entity.discard();
        }
        return null;
    }

    private SpawnPoint findSpawnPoint(ServerLevel level, ServerPlayer player,
                                      Entity entity, SpawnRequest request) {
        int remaining = catalog.maxCandidateAttempts() - request.candidateAttempts;
        int attemptsThisTick = Math.min(catalog.candidateAttemptsPerTick(), remaining);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        for (int attempt = 0; attempt < attemptsThisTick; attempt++) {
            int candidateIndex = request.candidateAttempts++;
            // The no-spawn ring follows the fighter, not the static arena center.
            // The center is only the hard leash. This keeps a moving player from
            // walking into a request that was geometrically safe at match start.
            SpawnOffset candidate = candidateForAttempt(request.candidates, candidateIndex,
                    player.getX(), player.getZ(), look.x, look.z,
                    centerX - player.getX(), centerZ - player.getZ(),
                    request.minDistance, request.maxDistance);
            double x = candidate.x();
            double z = candidate.z();
            if (horizontalDistanceSqr(x, z, centerX, centerZ)
                    > catalog.leashDistance() * catalog.leashDistance()) {
                continue;
            }
            BlockPos column = BlockPos.containing(x, centerY, z);
            if (!level.hasChunkAt(column)) {
                continue;
            }

            int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(x), Mth.floor(z));
            double y = surfaceY + request.spec.verticalOffset();
            double halfWidth = entity.getBbWidth() / 2.0;
            AABB box = new AABB(x - halfWidth, y, z - halfWidth,
                    x + halfWidth, y + entity.getBbHeight(), z + halfWidth);
            // Guard every bbox corner before support/collision queries can touch
            // a neighboring chunk. Spawning never generates or synchronously
            // loads terrain.
            if (!insideBuildHeight(level, box) || !allChunksLoaded(level, box)) {
                continue;
            }
            if (request.spec.placement() == Placement.GROUND
                    && !hasFullFootprintSupport(level, x, y, z, halfWidth)) {
                continue;
            }
            if (!level.noCollision(entity, box) || tooCloseToRecentSpawn(x, y, z)) {
                continue;
            }

            Vec3 aim = new Vec3(x, y + entity.getBbHeight() * 0.5, z);
            boolean rayVisible = level.clip(new ClipContext(
                    eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS;
            if (rejectVisibleCandidate(look.x, look.y, look.z,
                    aim.x - eye.x, aim.y - eye.y, aim.z - eye.z,
                    catalog.forwardConeDegrees(), rayVisible)) {
                continue;
            }
            return new SpawnPoint(x, y, z);
        }
        return null;
    }

    private void auditStuckAndLeash(ServerLevel level, LivingEntity target) {
        double movementThresholdSqr = catalog.movementThreshold() * catalog.movementThreshold();
        double leashSqr = catalog.leashDistance() * catalog.leashDistance();
        Iterator<Map.Entry<UUID, TrackedEnemy>> iterator = owned.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedEnemy> entry = iterator.next();
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            TrackedEnemy tracked = entry.getValue();
            double fromCenterSqr = horizontalDistanceSqr(living.getX(), living.getZ(), centerX, centerZ);
            double fromTargetSqr = horizontalDistanceSqr(living.getX(), living.getZ(),
                    target.getX(), target.getZ());
            boolean beyondLeash = fromCenterSqr > leashSqr
                    || fromTargetSqr > leashSqr
                    || Math.abs(living.getY() - centerY) > boundsRadius;
            if (beyondLeash) {
                if (replacementQueuedThisTick) {
                    return;
                }
                entity.discard();
                iterator.remove();
                replaceOrRetire(tracked, "leashed");
                return;
            }

            double movedSqr = horizontalDistanceSqr(living.getX(), living.getZ(),
                    tracked.auditX, tracked.auditZ);
            if (movedSqr < movementThresholdSqr
                    && living.distanceToSqr(target) > MIN_STUCK_TARGET_DISTANCE_SQR) {
                tracked.stationaryTicks += catalog.stuckAuditTicks();
            } else {
                tracked.stationaryTicks = 0;
            }
            tracked.observe(living);
            if (tracked.stationaryTicks >= catalog.stuckWindowTicks() && tracked.recovery == null) {
                switch (tracked.lineage.nextAction()) {
                    case RELOCATE -> tracked.recovery = new SpawnRequest(
                            tracked.spec, tracked.phaseIndex, tracked.lineage,
                            tracked.minDistance, tracked.maxDistance, seed, RECOVERY_SALT);
                    case REPLACE -> {
                        if (replacementQueuedThisTick) {
                            return;
                        }
                        living.discard();
                        iterator.remove();
                        replaceOrRetire(tracked, "stuck");
                        return;
                    }
                    case RETIRE -> {
                        living.discard();
                        iterator.remove();
                        retireTracked(tracked, "unreachable");
                        return;
                    }
                }
            }
        }
    }

    private void recoverOneIfNeeded(ServerLevel level, ServerPlayer player, LivingEntity target) {
        Iterator<Map.Entry<UUID, TrackedEnemy>> iterator = owned.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedEnemy> entry = iterator.next();
            TrackedEnemy tracked = entry.getValue();
            if (tracked.recovery == null) {
                continue;
            }
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                return;
            }
            SpawnPoint point = findSpawnPoint(level, player, living, tracked.recovery);
            if (point != null) {
                living.moveTo(point.x(), point.y(), point.z(), living.getYRot(), living.getXRot());
                living.setDeltaMovement(Vec3.ZERO);
                if (living instanceof Mob mob) {
                    mob.getNavigation().stop();
                    mob.setTarget(target);
                }
                tracked.lineage = tracked.lineage.afterRelocation();
                tracked.stationaryTicks = 0;
                tracked.recovery = null;
                tracked.observe(living);
                rememberSpawn(point);
                messages.add("Recovered stuck " + slotLabel(tracked) + " (relocate 1/1).");
                return;
            }
            if (tracked.recovery.candidateAttempts >= catalog.maxCandidateAttempts()) {
                if (replacementQueuedThisTick) {
                    return;
                }
                tracked.lineage = tracked.lineage.afterRelocation();
                living.discard();
                iterator.remove();
                replaceOrRetire(tracked, "unreachable");
            }
            return;
        }
    }

    private void reconcile(ServerLevel level, LivingEntity target) {
        Iterator<Map.Entry<UUID, TrackedEnemy>> iterator = owned.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrackedEnemy> entry = iterator.next();
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null) {
                if (entity.isAlive()) {
                    entry.getValue().lastBlock = entity.blockPosition();
                    entry.getValue().unresolvedTicks = 0;
                    if (entity instanceof Mob mob && mob.getTarget() != target) {
                        mob.setTarget(target);
                    }
                } else if (entry.getValue().deathCandidate != null) {
                    // Pomkots enemies remain present for their death animation.
                    // Ownership is released only after KILLED removal confirms
                    // that no later listener canceled the living-death event.
                    continue;
                } else {
                    iterator.remove();
                }
                continue;
            }
            BlockPos last = entry.getValue().lastBlock;
            if (last != null && level.isPositionEntityTicking(last)) {
                // Unresolvable in a ticking chunk is confirmed gone. In an
                // unloaded chunk it remains owned so unloading cannot grant a win.
                iterator.remove();
                continue;
            }
            TrackedEnemy tracked = entry.getValue();
            tracked.unresolvedTicks += catalog.reconcileTicks();
            if (tracked.unresolvedTicks >= catalog.unloadedRecoveryTicks()) {
                if (replacementQueuedThisTick) {
                    return;
                }
                // Stop owning the stale UUID before queuing its replacement. If
                // the original chunk later loads, ArenaManager's ADD guard culls
                // that UUID because owns(oldUuid) is now false.
                iterator.remove();
                replaceOrRetire(tracked, "unloaded");
                return; // one replacement at most per reconciliation audit
            }
        }
    }

    private void confirmDeathCandidates() {
        Iterator<Map.Entry<UUID, TrackedEnemy>> iterator = owned.entrySet().iterator();
        while (iterator.hasNext()) {
            TrackedEnemy tracked = iterator.next().getValue();
            Entity candidate = tracked.deathCandidate;
            if (candidate == null) {
                continue;
            }
            if (deathWasConfirmed(candidate.isAlive(), candidate.getRemovalReason())) {
                tracked.deathCandidate = null;
                iterator.remove();
                if (tracked.spec.boss()) {
                    bossDefeated = true;
                }
                continue;
            }
            if (candidate.isAlive() || candidate.isRemoved()) {
                // Alive means death was canceled. A non-KILLED removal is not
                // combat victory and is left for normal disappearance recovery.
                tracked.deathCandidate = null;
            }
        }
    }

    private void applyForceNext(ServerLevel level) {
        if (!phaseStarted) {
            warningRemaining = 0;
            messages.add("DEBUG NEXT — warning skipped.");
            return;
        }
        for (UUID uuid : List.copyOf(owned.keySet())) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) {
                entity.discard();
            }
        }
        owned.clear();
        pending.clear();
        if (phaseIndex == catalog.waves().size()) {
            victory = true;
            messages.add("DEBUG NEXT — boss cleared.");
            return;
        }
        phaseStarted = false;
        warningRemaining = 0;
        messages.add("DEBUG NEXT — advancing encounter.");
    }

    private boolean queueReplacement(TrackedEnemy tracked) {
        if (tracked.lineage.replacementsUsed() >= 1) {
            return false;
        }
        tracked.lineage = tracked.lineage.afterReplacement();
        pending.addFirst(new SpawnRequest(tracked.spec, tracked.phaseIndex, tracked.lineage,
                tracked.minDistance, tracked.maxDistance, seed, REPLACEMENT_SALT));
        replacementQueuedThisTick = true;
        return true;
    }

    private void replaceOrRetire(TrackedEnemy tracked, String condition) {
        if (queueReplacement(tracked)) {
            messages.add("Replaced " + condition + " " + slotLabel(tracked) + " (replace 1/1).");
        } else {
            retireTracked(tracked, condition);
        }
    }

    private void retireTracked(TrackedEnemy tracked, String condition) {
        if (retirementFailsRun(tracked.spec)) {
            failRuntime("boss " + slotLabel(tracked) + " became " + condition
                    + " after exhausting its recovery budget");
            return;
        }
        messages.add("Retired " + condition + " " + slotLabel(tracked)
                + " after recovery budget.");
    }

    private void retirePendingReplacement(SpawnRequest request, String condition) {
        pending.removeFirst();
        String label = slotLabel(request.spec, request.phaseIndex, request.lineage);
        if (retirementFailsRun(request.spec)) {
            failRuntime("boss " + label + " became " + condition
                    + " after exhausting its recovery budget");
            return;
        }
        messages.add("Retired " + condition + " " + label + " after recovery budget.");
    }

    static boolean retirementFailsRun(UnitSpec spec) {
        return spec != null && spec.boss();
    }

    static TickResult bossClearResult(boolean confirmedDefeat) {
        return confirmedDefeat ? TickResult.VICTORY : TickResult.FAILED;
    }

    static boolean deathWasConfirmed(boolean alive, Entity.RemovalReason removalReason) {
        return !alive && removalReason == Entity.RemovalReason.KILLED;
    }

    private static String slotLabel(TrackedEnemy tracked) {
        return slotLabel(tracked.spec, tracked.phaseIndex, tracked.lineage);
    }

    private static String slotLabel(UnitSpec spec, int phaseIndex, RecoveryLineage lineage) {
        return spec.role() + " slot " + (phaseIndex + 1) + ":" + (lineage.slotOrdinal() + 1);
    }

    private boolean tooCloseToRecentSpawn(double x, double y, double z) {
        double separationSqr = catalog.clusterSeparation() * catalog.clusterSeparation();
        for (Vec3 prior : recentSpawns) {
            if (prior.distanceToSqr(x, y, z) < separationSqr) {
                return true;
            }
        }
        return false;
    }

    private void rememberSpawn(SpawnPoint point) {
        recentSpawns.addLast(new Vec3(point.x(), point.y(), point.z()));
        while (recentSpawns.size() > RECENT_SPAWN_LIMIT) {
            recentSpawns.removeFirst();
        }
    }

    private static boolean insideBuildHeight(ServerLevel level, AABB box) {
        return box.minY >= level.getMinBuildHeight() && box.maxY < level.getMaxBuildHeight();
    }

    private static boolean hasFullFootprintSupport(ServerLevel level, double x, double y,
                                                   double z, double halfWidth) {
        double edge = Math.max(0.0, halfWidth - 0.05);
        double[][] samples = {
                {x, z},
                {x - edge, z - edge},
                {x - edge, z + edge},
                {x + edge, z - edge},
                {x + edge, z + edge}
        };
        for (double[] sample : samples) {
            BlockPos support = BlockPos.containing(sample[0], y - 0.01, sample[1]);
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allChunksLoaded(ServerLevel level, AABB box) {
        int y = Mth.floor(box.minY);
        return level.hasChunkAt(new BlockPos(Mth.floor(box.minX), y, Mth.floor(box.minZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.minX), y, Mth.floor(box.maxZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.maxX), y, Mth.floor(box.minZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.maxX), y, Mth.floor(box.maxZ)));
    }

    private static void validateEntityTypes(ServerLevel level, Catalog value) {
        Set<String> validated = new HashSet<>();
        for (WaveSpec wave : value.waves()) {
            for (UnitSpec unit : wave.roster()) {
                if (validated.add("normal:" + unit.entityId())) {
                    requireCombatEntity(level, unit);
                }
            }
        }
        requireCombatEntity(level, value.boss().unit());
    }

    private static void requireCombatEntity(ServerLevel level, UnitSpec spec) {
        ResourceLocation id = ResourceLocation.tryParse(spec.entityId());
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            throw new IllegalArgumentException("unknown entity type " + spec.entityId());
        }
        Entity entity = BuiltInRegistries.ENTITY_TYPE.get(id).create(level);
        if (entity == null) {
            throw new IllegalArgumentException("entity type cannot be created " + spec.entityId());
        }
        try {
            boolean valid = spec.boss()
                    ? entity instanceof BaseBossEntity
                    : entity instanceof BaseSmallMonsterEntity;
            if (!valid) {
                throw new IllegalArgumentException(spec.entityId()
                        + (spec.boss() ? " is not a supported mech boss" : " is not a supported enemy mech"));
            }
        } finally {
            entity.discard();
        }
    }

    private boolean failPrepare(String reason) {
        failureReason = reason;
        messages.add("SOLO DIRECTOR FAILURE — " + reason + ".");
        return false;
    }

    private void failRuntime(String reason) {
        if (failureReason.isEmpty()) {
            failureReason = reason;
            messages.add("SOLO DIRECTOR FAILURE — " + reason + ".");
        }
    }

    private static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1f", ticks / 20.0);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }

    private static String concise(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static double horizontalDistanceSqr(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    static int adjustedBudget(int baseBudget, double healthRatio, boolean bossOnly) {
        if (baseBudget <= 0) {
            throw new IllegalArgumentException("base budget must be positive");
        }
        if (bossOnly) {
            return baseBudget;
        }
        double ratio = Mth.clamp(healthRatio, 0.0, 1.0);
        return ratio <= 0.50 ? Math.max(1, baseBudget - 1) : baseBudget;
    }

    static boolean withinActiveCaps(int activeUnits, int activeThreat, int nextCost,
                                    int globalUnitCap, int phaseUnitCap, int threatCap) {
        if (activeUnits < 0 || activeThreat < 0 || nextCost <= 0
                || globalUnitCap <= 0 || phaseUnitCap <= 0 || threatCap <= 0) {
            return false;
        }
        int effectiveUnitCap = Math.min(globalUnitCap, phaseUnitCap);
        return activeUnits + 1 <= effectiveUnitCap && activeThreat + nextCost <= threatCap;
    }

    static List<UnitSpec> planWave(WaveSpec wave, int budget, Random random) {
        if (wave == null || random == null || budget <= 0) {
            throw new IllegalArgumentException("wave, random, and positive budget are required");
        }
        List<UnitSpec> result = new ArrayList<>();
        int remaining = budget;
        while (remaining > 0) {
            if (result.size() >= MAX_PLANNED_UNITS) {
                throw new IllegalArgumentException("wave exceeds the planned-unit safety cap");
            }
            int allowedCost = remaining;
            List<UnitSpec> fitting = wave.roster().stream()
                    .filter(spec -> spec.cost() <= allowedCost)
                    .toList();
            if (fitting.isEmpty()) {
                throw new IllegalArgumentException("budget " + budget + " cannot be exactly filled");
            }
            int totalWeight = fitting.stream().mapToInt(UnitSpec::weight).sum();
            int roll = random.nextInt(totalWeight);
            UnitSpec chosen = fitting.get(fitting.size() - 1);
            for (UnitSpec candidate : fitting) {
                roll -= candidate.weight();
                if (roll < 0) {
                    chosen = candidate;
                    break;
                }
            }
            result.add(chosen);
            remaining -= chosen.cost();
        }
        return List.copyOf(result);
    }

    static boolean spawnRingCanReachLeash(double playerBounds, double leashDistance,
                                          double minSpawnDistance, double margin) {
        return finite(playerBounds) && finite(leashDistance) && finite(minSpawnDistance)
                && finite(margin) && playerBounds > 0.0 && leashDistance > 0.0
                && minSpawnDistance > 0.0 && margin >= 0.0
                && playerBounds <= leashDistance + minSpawnDistance - margin;
    }

    static boolean leashCoversPlayerBounds(double playerBounds, double leashDistance,
                                           double pursuitMargin) {
        return finite(playerBounds) && finite(leashDistance) && finite(pursuitMargin)
                && playerBounds > 0.0 && leashDistance > 0.0 && pursuitMargin >= 0.0
                && leashDistance >= playerBounds + pursuitMargin;
    }

    static SpawnOffset nextOffset(Random random, double minDistance, double maxDistance) {
        if (random == null || !finite(minDistance) || !finite(maxDistance)
                || minDistance < 0.0 || maxDistance <= minDistance) {
            throw new IllegalArgumentException("invalid spawn ring");
        }
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
        return new SpawnOffset(Math.cos(angle) * distance, Math.sin(angle) * distance);
    }

    static SpawnOffset nextCandidate(Random random, double originX, double originZ,
                                     double minDistance, double maxDistance) {
        if (!finite(originX) || !finite(originZ)) {
            throw new IllegalArgumentException("invalid candidate origin");
        }
        SpawnOffset offset = nextOffset(random, minDistance, maxDistance);
        return new SpawnOffset(originX + offset.x(), originZ + offset.z());
    }

    static SpawnOffset candidateForAttempt(Random random, int attemptIndex,
                                           double originX, double originZ,
                                           double lookX, double lookZ,
                                           double fallbackForwardX, double fallbackForwardZ,
                                           double minDistance, double maxDistance) {
        if (random == null || attemptIndex < 0 || !finite(originX) || !finite(originZ)
                || !finite(lookX) || !finite(lookZ)
                || !finite(fallbackForwardX) || !finite(fallbackForwardZ)
                || !finite(minDistance) || !finite(maxDistance)
                || minDistance < 0.0 || maxDistance <= minDistance) {
            throw new IllegalArgumentException("invalid candidate request");
        }
        if (attemptIndex >= PRIORITY_CANDIDATE_COUNT) {
            return nextCandidate(random, originX, originZ, minDistance, maxDistance);
        }

        double forwardLength = Math.hypot(lookX, lookZ);
        double forwardX = lookX;
        double forwardZ = lookZ;
        if (forwardLength < 1.0E-8) {
            forwardX = fallbackForwardX;
            forwardZ = fallbackForwardZ;
            forwardLength = Math.hypot(forwardX, forwardZ);
        }
        if (forwardLength < 1.0E-8) {
            forwardX = 0.0;
            forwardZ = 1.0;
        } else {
            forwardX /= forwardLength;
            forwardZ /= forwardLength;
        }

        double angleDegrees = switch (attemptIndex) {
            case 0 -> 90.0;
            case 1 -> -90.0;
            case 2 -> 70.0;
            case 3 -> -70.0;
            default -> 180.0;
        };
        double angle = Math.toRadians(angleDegrees);
        double offsetX = (forwardX * Math.cos(angle) - forwardZ * Math.sin(angle)) * minDistance;
        double offsetZ = (forwardX * Math.sin(angle) + forwardZ * Math.cos(angle)) * minDistance;
        return new SpawnOffset(originX + offsetX, originZ + offsetZ);
    }

    static boolean rejectVisibleCandidate(double lookX, double lookY, double lookZ,
                                          double candidateX, double candidateY, double candidateZ,
                                          double coneDegrees, boolean rayVisible) {
        if (!rayVisible) {
            return false;
        }
        double lookLength = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double candidateLength = Math.sqrt(candidateX * candidateX
                + candidateY * candidateY + candidateZ * candidateZ);
        if (lookLength < 1.0E-8) {
            return false;
        }
        if (candidateLength < 1.0E-8) {
            return true;
        }
        double dot = (lookX * candidateX + lookY * candidateY + lookZ * candidateZ)
                / (lookLength * candidateLength);
        double halfAngle = Math.toRadians(Mth.clamp(coneDegrees, 0.0, 180.0) * 0.5);
        return dot >= Math.cos(halfAngle);
    }

    static Catalog parseCatalog(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("catalog JSON is empty");
        }
        try {
            return parseCatalogElement(JsonParser.parseString(json));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("catalog JSON is malformed", ex);
        }
    }

    private static Catalog parseCatalog(Reader reader) {
        try {
            return parseCatalogElement(JsonParser.parseReader(reader));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("catalog JSON is malformed", ex);
        }
    }

    private static Catalog parseCatalogElement(JsonElement parsed) {
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("catalog root must be an object");
        }
        JsonObject root = parsed.getAsJsonObject();
        int schema = requiredInt(root, "schema");
        double minDistance = requiredDouble(root, "min_spawn_distance");
        double maxDistance = requiredDouble(root, "max_spawn_distance");
        double cone = requiredDouble(root, "forward_cone_degrees");
        double clusterSeparation = requiredDouble(root, "cluster_separation");
        double leashDistance = requiredDouble(root, "leash_distance");
        double movementThreshold = requiredDouble(root, "movement_threshold");
        int initialWarning = requiredInt(root, "initial_warning_ticks");
        int waveWarning = requiredInt(root, "wave_warning_ticks");
        int reconcileTicks = requiredInt(root, "reconcile_ticks");
        int unloadedRecoveryTicks = requiredInt(root, "unloaded_recovery_ticks");
        int stuckAuditTicks = requiredInt(root, "stuck_audit_ticks");
        int stuckWindowTicks = requiredInt(root, "stuck_window_ticks");
        int maxCandidates = requiredInt(root, "max_candidate_attempts");
        int attemptsPerTick = requiredInt(root, "candidate_attempts_per_tick");
        int maxCommitRetries = requiredInt(root, "max_commit_retries");
        int unitCap = requiredInt(root, "unit_cap");
        int threatCap = requiredInt(root, "threat_cap");

        require(schema == 1, "schema must be 1");
        require(finite(minDistance) && minDistance >= 36.0,
                "min spawn distance must be at least 36");
        require(finite(maxDistance) && maxDistance > minDistance,
                "max spawn distance must exceed min");
        require(finite(cone) && cone > 0.0 && cone <= 180.0,
                "forward cone must be in (0, 180]");
        require(finite(clusterSeparation) && clusterSeparation > 0.0,
                "cluster separation must be positive");
        require(finite(leashDistance) && leashDistance > maxDistance,
                "leash must exceed the normal spawn band");
        require(finite(movementThreshold) && movementThreshold > 0.0,
                "movement threshold must be positive");
        require(initialWarning > 0 && waveWarning > 0, "warning delays must be positive");
        require(reconcileTicks == 20, "reconcile_ticks must be 20");
        require(unloadedRecoveryTicks >= 200 && unloadedRecoveryTicks % reconcileTicks == 0,
                "unloaded recovery must be at least 200 and align to reconciliation");
        require(stuckAuditTicks == 100, "stuck_audit_ticks must be 100");
        require(stuckWindowTicks >= 300 && stuckWindowTicks % stuckAuditTicks == 0,
                "stuck window must be at least 300 and align to audits");
        require(maxCandidates > 0 && maxCandidates <= 32,
                "max candidate attempts must be in [1, 32]");
        require(attemptsPerTick > 0 && attemptsPerTick <= maxCandidates,
                "candidate attempts per tick exceed the total bound");
        require(maxCommitRetries > 0 && maxCommitRetries <= 8,
                "max commit retries must be in [1, 8]");
        require(unitCap > 0 && unitCap <= 5, "unit cap must be in [1, 5]");
        require(threatCap > 0 && threatCap <= 6, "threat cap must be in [1, 6]");

        JsonArray waveArray = requiredArray(root, "waves");
        require(waveArray.size() >= 1 && waveArray.size() <= 32,
                "catalog must contain between 1 and 32 normal waves");
        List<WaveSpec> waves = new ArrayList<>();
        Set<String> waveIds = new HashSet<>();
        for (int waveIndex = 0; waveIndex < waveArray.size(); waveIndex++) {
            JsonObject waveJson = objectAt(waveArray, waveIndex, "wave");
            String waveId = requiredString(waveJson, "id");
            int budget = requiredInt(waveJson, "budget");
            int waveCap = requiredInt(waveJson, "unit_cap");
            require(!waveId.isBlank() && waveIds.add(waveId),
                    "wave ids must be non-empty and unique");
            require(budget > 0 && budget <= threatCap * 2,
                    "wave budget must be in [1, twice the threat cap]");
            require(waveCap > 0 && waveCap <= unitCap, "wave unit cap exceeds global cap");

            JsonArray rosterJson = requiredArray(waveJson, "roster");
            require(!rosterJson.isEmpty(), "wave roster cannot be empty");
            require(rosterJson.size() <= MAX_ROSTER_ENTRIES,
                    "wave roster exceeds the entry safety cap");
            List<UnitSpec> roster = new ArrayList<>();
            for (int unitIndex = 0; unitIndex < rosterJson.size(); unitIndex++) {
                UnitSpec unit = parseUnit(objectAt(rosterJson, unitIndex, "unit"), threatCap, false);
                require(!unit.boss(), "boss cannot appear in a normal wave");
                roster.add(unit);
            }
            require(roster.stream().anyMatch(unit -> unit.cost() == 1),
                    "normal waves need a cost-1 unit for reduced budgets");
            waves.add(new WaveSpec(waveId, budget, waveCap, roster));
        }

        JsonObject bossJson = requiredObject(root, "boss");
        String bossId = requiredString(bossJson, "id");
        int bossWarning = requiredInt(bossJson, "warning_ticks");
        double bossMin = requiredDouble(bossJson, "min_spawn_distance");
        double bossMax = requiredDouble(bossJson, "max_spawn_distance");
        UnitSpec bossUnit = parseUnit(bossJson, threatCap, true);
        require(bossWarning > 0, "boss warning must be positive");
        require(bossMin >= minDistance && bossMax > bossMin,
                "boss spawn band is invalid");
        require(bossMax < leashDistance, "boss spawn band must stay inside leash");
        require(bossUnit.boss(), "boss entry must set boss=true");

        return new Catalog(schema, minDistance, maxDistance, cone, clusterSeparation,
                leashDistance, movementThreshold, initialWarning, waveWarning,
                reconcileTicks, unloadedRecoveryTicks, stuckAuditTicks, stuckWindowTicks, maxCandidates,
                attemptsPerTick, maxCommitRetries, unitCap, threatCap, waves,
                new BossSpec(bossId, bossWarning, bossMin, bossMax, bossUnit));
    }

    private static UnitSpec parseUnit(JsonObject json, int threatCap, boolean bossEntry) {
        String entityId = requiredString(json, "entity");
        String role = requiredString(json, "role");
        int cost = requiredInt(json, "cost");
        int weight = optionalInt(json, "weight", 1);
        String placementName = requiredString(json, "placement");
        int verticalOffset = optionalInt(json, "vertical_offset", 0);
        boolean boss = optionalBoolean(json, "boss", false);
        Placement placement;
        try {
            placement = Placement.valueOf(placementName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown placement " + placementName);
        }
        require(ResourceLocation.tryParse(entityId) != null, "invalid entity id " + entityId);
        require(!role.isBlank(), "unit role cannot be empty");
        require(cost > 0 && cost <= threatCap, "unit cost must fit the threat cap");
        require(weight > 0 && weight <= MAX_UNIT_WEIGHT,
                "unit weight must be in [1, " + MAX_UNIT_WEIGHT + "]");
        require(verticalOffset >= 0 && verticalOffset <= 64, "invalid vertical offset");
        require(placement == Placement.AIR || verticalOffset == 0,
                "ground units cannot have a vertical offset");
        require(!bossEntry || boss, "boss entry must set boss=true");
        return new UnitSpec(entityId, role, cost, weight, placement, verticalOffset, boss);
    }

    private static long mixSeed(long seed, long salt) {
        long value = seed ^ salt;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static JsonObject requiredObject(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(key + " must be an array");
        }
        return value.getAsJsonArray();
    }

    private static JsonObject objectAt(JsonArray array, int index, String label) {
        JsonElement value = array.get(index);
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException(label + " " + index + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            int result = value.getAsInt();
            if (value.getAsDouble() != result) {
                throw new IllegalArgumentException(key + " must be an integer");
            }
            return result;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer", ex);
        }
    }

    private static int optionalInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? requiredInt(object, key) : fallback;
    }

    private static double requiredDouble(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        try {
            return value.getAsDouble();
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be a number", ex);
        }
    }

    private static boolean optionalBoolean(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
