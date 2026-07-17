package grcmcs.minecraft.mods.pomkotsmechs.arena;

import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss.BaseBossEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.mob.BaseSmallMonsterEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.ArenaRivalPmvc01Entity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.Pmvc01Entity;
import grcmcs.minecraft.mods.pomkotsmechs.util.Utils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authored runtime for Operation Ashen Span. This class intentionally owns one fixed
 * mission graph; it is not a catalog, random wave system, or general navigation layer.
 */
final class AshenSpanDirector {
    enum TickResult {
        RUNNING,
        VICTORY,
        FAILED
    }

    private static final int RECOVERY_AUDIT_TICKS = 40;
    private static final int STUCK_TICKS = 240;
    private static final double STUCK_MOVEMENT_SQR = 0.20D * 0.20D;
    private static final double STUCK_TARGET_DISTANCE_SQR = 36.0D * 36.0D;
    private static final int[][] RECOVERY_OFFSETS = {
            {0, 0}, {0, -6}, {0, 6}, {-6, 0}, {6, 0}, {-6, -6}, {-6, 6}
    };

    private static final Map<AshenSpanDefinition.PhaseId,
            List<AshenSpanDefinition.BlockVolume>> REVEAL_SHUTTERS = Map.of(
            AshenSpanDefinition.PhaseId.DROP_DECK, List.of(
                    volume(-151, 83, 89, -17, -11),
                    volume(-151, 83, 89, 11, 17),
                    volume(-153, 83, 89, -3, 3)),
            AshenSpanDefinition.PhaseId.FREIGHT_CANYON, List.of(
                    volume(-113, 65, 78, -28, -19),
                    volume(-113, 65, 78, 19, 28),
                    volume(-97, 65, 78, -5, 5)),
            AshenSpanDefinition.PhaseId.WEST_SPAN, List.of(
                    volume(-64, 73, 78, -5, 6)),
            AshenSpanDefinition.PhaseId.EAST_SPAN, List.of(
                    volume(0, 1, 73, 78, -13, 14)),
            AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS, List.of(
                    volume(45, 73, 82, -23, -8),
                    volume(45, 73, 82, 8, 23)));

    private static final class TrackedRoot {
        final AshenSpanDefinition.UnitSlot slot;
        Entity entity;
        boolean relocationUsed;
        boolean replacementUsed;
        double auditX;
        double auditZ;
        int stationaryTicks;
        boolean deathCounted;

        TrackedRoot(AshenSpanDefinition.UnitSlot slot, Entity entity,
                    boolean relocationUsed, boolean replacementUsed) {
            this.slot = slot;
            this.entity = entity;
            this.relocationUsed = relocationUsed;
            this.replacementUsed = replacementUsed;
            observe();
        }

        void observe() {
            auditX = entity.getX();
            auditZ = entity.getZ();
        }
    }

    private final ArenaOwnershipRegistry ownership = new ArenaOwnershipRegistry();
    private final Map<UUID, TrackedRoot> currentRoots = new LinkedHashMap<>();
    private final Map<UUID, TrackedRoot> reservedRoots = new LinkedHashMap<>();
    private final Deque<String> messages = new ArrayDeque<>();

    private AshenSpanMissionModel model;
    private int matchId;
    private long seed;
    private int selectedBuild;
    private UUID ownerId;
    private int ticks;
    private int recoveryAudit;
    private boolean currentPhaseActivated;
    private boolean bossRecoveryExhausted;
    private String failureReason = "";
    private int normalKills;
    private int rivalKills;
    private int bossKills;

    boolean prepare(ServerLevel level, int matchId, long seed, int selectedBuild,
                    UUID ownerId, LivingEntity playerMech) {
        reset();
        if (selectedBuild < 0 || selectedBuild >= GarageFleet.size()
                || ownerId == null || playerMech == null) {
            return fail("invalid garage recipe");
        }
        this.matchId = matchId;
        this.seed = seed;
        this.selectedBuild = selectedBuild;
        this.ownerId = ownerId;
        this.model = new AshenSpanMissionModel(seed, selectedBuild);
        ArenaHooks.begin(ownership, matchId, level.dimension());
        ArenaHooks.registerRoot(playerMech, ArenaOwnershipRegistry.RootRole.PLAYER,
                "PLAYER", 0, false);
        return true;
    }

    boolean startAfterMount(ServerLevel level, ServerPlayer player, LivingEntity playerMech) {
        if (model == null || player == null || playerMech == null
                || !player.getUUID().equals(ownerId) || player.getVehicle() != playerMech) {
            return fail("garage mount did not satisfy the authored start contract");
        }
        model.onBuildMounted();
        if (!stageCurrentPhase(level, player, playerMech, true)) {
            return false;
        }
        messages.add("OBJECTIVE — " + model.snapshot().objective());
        return true;
    }

    TickResult tick(MinecraftServer server, ServerLevel level,
                    ServerPlayer player, LivingEntity playerMech) {
        if (model == null) {
            fail("director is not prepared");
            return TickResult.FAILED;
        }
        ticks++;
        enforceContainment(level, playerMech);
        if (++recoveryAudit >= RECOVERY_AUDIT_TICKS) {
            recoveryAudit = 0;
            auditRecoveries(level, player, playerMech);
            if (!failureReason.isEmpty()) {
                return TickResult.FAILED;
            }
        }
        ArenaHooks.reconcile(level);

        AshenSpanMissionModel.Snapshot before = model.snapshot();
        AshenSpanMissionModel.HostileCounts counts = hostileCounts();
        AshenSpanMissionModel.Snapshot after = model.observe(
                new AshenSpanMissionModel.TickInput(
                        playerMech.getX(), counts, player.isAlive(), playerMech.isAlive(),
                        !player.hasDisconnected(), false, bossRecoveryExhausted));

        if (after.status() == AshenSpanMissionModel.Status.DEFEAT) {
            if (failureReason.isEmpty()) {
                failureReason = "mission defeat: " + after.defeatReason().name().toLowerCase();
            }
            return TickResult.FAILED;
        }
        if (after.status() == AshenSpanMissionModel.Status.VICTORY) {
            retireClearedRoots(level);
            return TickResult.VICTORY;
        }

        boolean phaseAdvanced = before.currentPhase() != after.currentPhase();
        if (phaseAdvanced || (before.status() == AshenSpanMissionModel.Status.ACTIVE
                && after.status() == AshenSpanMissionModel.Status.SERVICE)) {
            AshenSpanDefinition.PhaseSpec cleared = AshenSpanDefinition.phase(before.currentPhase());
            retireClearedRoots(level);
            if (after.status() == AshenSpanMissionModel.Status.SERVICE) {
                if (!(playerMech instanceof Pmvc01Entity mech)
                        || !GarageFleet.service(mech, selectedBuild)) {
                    fail("captured gantry could not restore the selected Garage Fleet template");
                    return TickResult.FAILED;
                }
                if (!model.completeService()) {
                    fail("captured gantry service was already consumed");
                    return TickResult.FAILED;
                }
                messages.add("SERVICE COMPLETE");
                Utils.playSoundEffect(PomkotsMechs.SE_MACHINE.get(), playerMech);
                Utils.playSoundEffect(PomkotsMechs.SE_ALERT.get(), playerMech);
                if (!stageCurrentPhase(level, player, playerMech, true)) {
                    return TickResult.FAILED;
                }
                messages.add("OBJECTIVE — " + model.snapshot().objective());
                if (!openGate(server, level, AshenSpanDefinition.GateId.G5)) {
                    return TickResult.FAILED;
                }
            } else {
                if (!stageCurrentPhase(level, player, playerMech, true)) {
                    return TickResult.FAILED;
                }
                messages.add("OBJECTIVE — " + model.snapshot().objective());
                if (cleared.opensAfterClear() != null
                        && !openGate(server, level, cleared.opensAfterClear())) {
                    return TickResult.FAILED;
                }
            }
        } else if (!currentPhaseActivated
                && after.status() == AshenSpanMissionModel.Status.ACTIVE) {
            activateIfReady(server, level, player, playerMech);
        }
        return failureReason.isEmpty() ? TickResult.RUNNING : TickResult.FAILED;
    }

    void onDeath(LivingEntity entity) {
        TrackedRoot tracked = currentRoots.get(entity.getUUID());
        if (tracked != null && !tracked.deathCounted) {
            tracked.deathCounted = true;
            switch (tracked.slot.unit()) {
                case GATEKEEPER_R01 -> rivalKills++;
                case PMB04_SPAN_WARDEN -> bossKills++;
                default -> normalKills++;
            }
        }
        ArenaHooks.onDeath(entity);
    }

    List<String> drainMessages() {
        List<String> result = new ArrayList<>(messages);
        messages.clear();
        return result;
    }

    String failureReason() {
        return failureReason.isEmpty() ? "no failure recorded" : failureReason;
    }

    void recordDefeat(AshenSpanMissionModel.DefeatReason reason, String detail) {
        if (model != null) {
            model.forceDefeat(reason);
        }
        if (failureReason.isEmpty()) {
            failureReason = detail;
        }
    }

    String status() {
        if (model == null) {
            return "not prepared";
        }
        AshenSpanMissionModel.Snapshot snapshot = model.snapshot();
        return snapshot.status() + " " + snapshot.currentPhase()
                + " | roots " + ownership.liveHostileRootCount()
                + " | projectiles " + ownership.projectileCount()
                + "/" + ArenaOwnershipRegistry.PROJECTILE_CAP
                + " | effects " + ownership.effectCount()
                + "/" + ArenaOwnershipRegistry.EFFECT_CAP;
    }

    String summary(float remainingHealth) {
        String buildName = selectedBuild >= 0 && selectedBuild < GarageFleet.size()
                ? GarageFleet.name(selectedBuild) : "unknown";
        String outcome = model == null ? "aborted" : model.outcome().name().toLowerCase();
        return "Build " + buildName + " | elapsed " + formatTicks(ticks)
                + " | normal kills " + normalKills + "/15 | rival " + rivalKills
                + "/1 | boss " + bossKills + "/1 | mech HP "
                + String.format(java.util.Locale.ROOT, "%.1f", Math.max(0.0F, remainingHealth))
                + " | outcome " + outcome + " | seed " + seed;
    }

    void reset() {
        for (TrackedRoot tracked : currentRoots.values()) {
            if (tracked.entity instanceof ArenaRivalPmvc01Entity rival) {
                rival.deactivateAutonomy();
            }
        }
        for (TrackedRoot tracked : reservedRoots.values()) {
            if (tracked.entity instanceof ArenaRivalPmvc01Entity rival) {
                rival.deactivateAutonomy();
            }
        }
        currentRoots.clear();
        reservedRoots.clear();
        messages.clear();
        ownership.clear();
        ArenaHooks.end();
        model = null;
        matchId = 0;
        seed = 0L;
        selectedBuild = -1;
        ownerId = null;
        ticks = 0;
        recoveryAudit = 0;
        currentPhaseActivated = false;
        bossRecoveryExhausted = false;
        failureReason = "";
        normalKills = 0;
        rivalKills = 0;
        bossKills = 0;
    }

    private boolean stageCurrentPhase(ServerLevel level, ServerPlayer player,
                                      LivingEntity playerMech, boolean deferActivation) {
        AshenSpanDefinition.PhaseSpec phase = model.currentPhase().orElse(null);
        if (phase == null || !currentRoots.isEmpty()) {
            return fail("phase staging state is inconsistent");
        }
        currentPhaseActivated = false;
        if (phase.id() == AshenSpanDefinition.PhaseId.GATEKEEPER) {
            return promotePreplacedGatekeeper(phase);
        }
        if (!reservedRoots.isEmpty()) {
            return fail("a future reveal root exists outside its authored escort phase");
        }
        int slotIndex = 0;
        for (AshenSpanDefinition.UnitSlot slot : phase.slots()) {
            Entity entity = createConfigured(level, slot);
            if (entity == null) {
                rollbackStaging();
                return fail("could not create authored root " + slot.slotId());
            }
            boolean exactValid = placeAt(level, player, playerMech, entity, slot.socket(),
                    slot, true);
            boolean recovered = false;
            if (!exactValid) {
                recovered = placeAtRecovery(level, player, playerMech, entity, phase, slot);
            }
            if (!exactValid && !recovered) {
                entity.discard();
                rollbackStaging();
                return fail("no safe loaded authored position for " + slot.slotId());
            }

            configureStaged(entity);
            ArenaHooks.registerRoot(entity, ArenaOwnershipRegistry.RootRole.HOSTILE,
                    phase.id().name(), slotIndex, true);
            TrackedRoot tracked = new TrackedRoot(slot, entity, recovered, false);
            currentRoots.put(entity.getUUID(), tracked);
            if (!level.addFreshEntity(entity)) {
                currentRoots.remove(entity.getUUID());
                ArenaHooks.unregisterRoot(entity.getUUID());
                entity.discard();
                rollbackStaging();
                return fail("world rejected authored root " + slot.slotId());
            }
            slotIndex++;
        }
        if (phase.id() == AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS
                && !preplaceGatekeeper(level, player, playerMech)) {
            rollbackStaging();
            return false;
        }
        model.admitCurrentPhase(currentRoots.size());
        if (!deferActivation && model.status() == AshenSpanMissionModel.Status.ACTIVE) {
            activateIfReady(level.getServer(), level, player, playerMech);
        }
        return true;
    }

    /**
     * R-01 exists behind its internal shutter throughout the roller fight. It is
     * Arena-owned before ADD but held in a reserve role so it cannot block 5A's
     * exact-zero gate. Promotion changes ownership accounting without re-adding the
     * entity, preserving the authored reveal.
     */
    private boolean preplaceGatekeeper(ServerLevel level, ServerPlayer player,
                                       LivingEntity playerMech) {
        AshenSpanDefinition.PhaseSpec duel =
                AshenSpanDefinition.phase(AshenSpanDefinition.PhaseId.GATEKEEPER);
        AshenSpanDefinition.UnitSlot slot = duel.slots().get(0);
        Entity entity = createConfigured(level, slot);
        if (!(entity instanceof ArenaRivalPmvc01Entity)) {
            if (entity != null) entity.discard();
            return fail("could not create pre-placed Gatekeeper R-01");
        }
        boolean exactValid = placeAt(level, player, playerMech, entity, slot.socket(), slot, true);
        boolean recovered = !exactValid
                && placeAtRecovery(level, player, playerMech, entity, duel, slot);
        if (!exactValid && !recovered) {
            entity.discard();
            return fail("no safe loaded pre-placement for Gatekeeper R-01");
        }
        configureStaged(entity);
        ArenaHooks.registerRoot(entity, ArenaOwnershipRegistry.RootRole.RESERVE_HOSTILE,
                duel.id().name(), 0, true);
        TrackedRoot tracked = new TrackedRoot(slot, entity, recovered, false);
        reservedRoots.put(entity.getUUID(), tracked);
        if (!level.addFreshEntity(entity)) {
            reservedRoots.remove(entity.getUUID());
            ArenaHooks.unregisterRoot(entity.getUUID());
            entity.discard();
            return fail("world rejected pre-placed Gatekeeper R-01");
        }
        return true;
    }

    private boolean promotePreplacedGatekeeper(AshenSpanDefinition.PhaseSpec phase) {
        if (reservedRoots.size() != 1 || phase.slots().size() != 1) {
            return fail("Gatekeeper pre-placement state is inconsistent");
        }
        TrackedRoot tracked = reservedRoots.values().iterator().next();
        if (tracked.entity.isRemoved()
                || tracked.slot.unit() != AshenSpanDefinition.UnitKind.GATEKEEPER_R01) {
            return fail("pre-placed Gatekeeper R-01 is unavailable");
        }
        try {
            ArenaHooks.promoteReserveRoot(tracked.entity);
        } catch (IllegalStateException exception) {
            return fail("Gatekeeper ownership promotion failed");
        }
        reservedRoots.clear();
        currentRoots.put(tracked.entity.getUUID(), tracked);
        model.admitCurrentPhase(1);
        return true;
    }

    private Entity createConfigured(ServerLevel level, AshenSpanDefinition.UnitSlot slot) {
        ResourceLocation id = ResourceLocation.tryParse(slot.unit().entityId());
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        Entity entity = type.create(level);
        boolean valid = switch (slot.unit()) {
            case GATEKEEPER_R01 -> entity instanceof ArenaRivalPmvc01Entity;
            case PMB04_SPAN_WARDEN -> entity instanceof BaseBossEntity;
            default -> entity instanceof BaseSmallMonsterEntity;
        };
        if (!valid) {
            if (entity != null) entity.discard();
            return null;
        }
        if (entity instanceof ArenaRivalPmvc01Entity rival) {
            rival.configureLoadout();
            rival.deactivateAutonomy();
        }
        if (entity instanceof BaseBossEntity boss) {
            boss.deactivate();
            boss.setArenaBossName(Component.literal("SPAN WARDEN").withStyle(ChatFormatting.RED));
        }
        return entity;
    }

    private void configureStaged(Entity entity) {
        entity.setInvulnerable(true);
        entity.setDeltaMovement(Vec3.ZERO);
        if (entity instanceof Mob mob) {
            mob.setTarget(null);
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        if (entity instanceof BaseSmallMonsterEntity small) {
            small.setPersistence(true);
        }
    }

    private void activateIfReady(MinecraftServer server, ServerLevel level,
                                 ServerPlayer player, LivingEntity playerMech) {
        if (currentPhaseActivated || model.status() != AshenSpanMissionModel.Status.ACTIVE) {
            return;
        }
        AshenSpanDefinition.PhaseSpec phase = model.currentPhase().orElseThrow();
        List<AshenSpanDefinition.BlockVolume> shutters = revealShutters(phase.id());
        for (AshenSpanDefinition.BlockVolume shutter : shutters) {
            if (!MissionGateLedger.open(server, level, shutter)) {
                fail("could not open authored reveal shutter for " + phase.id());
                return;
            }
        }
        for (TrackedRoot tracked : currentRoots.values()) {
            Entity entity = tracked.entity;
            ArenaHooks.activate(entity);
            entity.setInvulnerable(false);
            if (entity instanceof Mob mob) {
                mob.setNoAi(false);
                mob.setTarget(playerMech);
            }
            if (entity instanceof ArenaRivalPmvc01Entity rival) {
                rival.activateAutonomy(playerMech, seed);
            }
            if (entity instanceof BaseBossEntity boss) {
                boss.addHateToEntity(playerMech, 1000);
                boss.boot();
            }
        }
        currentPhaseActivated = true;
    }

    private void auditRecoveries(ServerLevel level, ServerPlayer player,
                                 LivingEntity playerMech) {
        if (!currentPhaseActivated || model == null
                || model.status() != AshenSpanMissionModel.Status.ACTIVE) {
            return;
        }
        for (TrackedRoot tracked : new ArrayList<>(currentRoots.values())) {
            if (!(tracked.entity instanceof LivingEntity living) || !living.isAlive()) {
                continue;
            }
            boolean outside = !containsAabb(AshenSpanDefinition.PLAYABLE_BOUNDS,
                    living.getBoundingBox());
            double moved = horizontalDistanceSqr(living.getX(), living.getZ(),
                    tracked.auditX, tracked.auditZ);
            if (moved < STUCK_MOVEMENT_SQR
                    && living.distanceToSqr(playerMech) > STUCK_TARGET_DISTANCE_SQR) {
                tracked.stationaryTicks += RECOVERY_AUDIT_TICKS;
            } else {
                tracked.stationaryTicks = 0;
            }
            tracked.observe();
            if (outside || tracked.stationaryTicks >= STUCK_TICKS) {
                recover(level, player, playerMech, tracked, outside ? "boundary" : "stuck");
                return; // one recovery mutation per audit tick
            }
        }
    }

    private void recover(ServerLevel level, ServerPlayer player, LivingEntity playerMech,
                         TrackedRoot tracked, String reason) {
        AshenSpanDefinition.PhaseSpec phase = model.currentPhase().orElseThrow();
        clearTransientDescendantsForRelocation(level, ownership, tracked.entity.getUUID());
        if (tracked.entity instanceof ArenaRivalPmvc01Entity rival) {
            rival.prepareForRelocation();
        }
        if (!tracked.relocationUsed) {
            tracked.relocationUsed = true;
            tracked.entity.setDeltaMovement(Vec3.ZERO);
            if (placeAtRecovery(level, player, playerMech, tracked.entity, phase, tracked.slot)) {
                tracked.stationaryTicks = 0;
                tracked.observe();
                reactivateRecovered(tracked.entity, playerMech);
                messages.add(tracked.slot.slotId() + " relocated after " + reason + ".");
                return;
            }
        }
        // A same-root relocation keeps modular boss hitboxes alive because bosses
        // create them only on their first tick. Once relocation is unavailable or
        // fails, replacement/retirement must remove the complete old lineage.
        clearRootDescendants(level, tracked.entity.getUUID());
        if (!tracked.replacementUsed) {
            Entity replacement = createConfigured(level, tracked.slot);
            if (replacement != null
                    && placeAtRecovery(level, player, playerMech, replacement, phase, tracked.slot)) {
                UUID oldId = tracked.entity.getUUID();
                tracked.entity.discard();
                ArenaHooks.replaceRoot(tracked.entity, replacement, false);
                currentRoots.remove(oldId);
                tracked.entity = replacement;
                tracked.replacementUsed = true;
                tracked.stationaryTicks = 0;
                tracked.observe();
                currentRoots.put(replacement.getUUID(), tracked);
                if (level.addFreshEntity(replacement)) {
                    reactivateRecovered(replacement, playerMech);
                    messages.add(tracked.slot.slotId() + " replaced after " + reason + ".");
                    return;
                }
                currentRoots.remove(replacement.getUUID());
                ArenaHooks.unregisterRoot(replacement.getUUID());
                replacement.discard();
            } else if (replacement != null) {
                replacement.discard();
            }
        }
        tracked.entity.discard();
        currentRoots.remove(tracked.entity.getUUID());
        ArenaHooks.unregisterRoot(tracked.entity.getUUID());
        if (tracked.slot.unit() == AshenSpanDefinition.UnitKind.PMB04_SPAN_WARDEN) {
            bossRecoveryExhausted = true;
            fail("Span Warden recovery budget exhausted",
                    recoveryExhaustionDefeatReason(tracked.slot.unit()));
        } else if (tracked.slot.unit() == AshenSpanDefinition.UnitKind.GATEKEEPER_R01) {
            fail("Gatekeeper recovery budget exhausted",
                    recoveryExhaustionDefeatReason(tracked.slot.unit()));
        } else {
            messages.add(tracked.slot.slotId() + " retired after recovery budget exhaustion.");
        }
    }

    private void reactivateRecovered(Entity entity, LivingEntity target) {
        entity.setInvulnerable(false);
        ArenaHooks.activate(entity);
        if (entity instanceof Mob mob) {
            mob.setNoAi(false);
            mob.setTarget(target);
        }
        if (entity instanceof ArenaRivalPmvc01Entity rival) {
            rival.activateAutonomy(target, seed);
        }
        if (entity instanceof BaseBossEntity boss) {
            boss.addHateToEntity(target, 1000);
            boss.boot();
        }
    }

    private boolean placeAtRecovery(ServerLevel level, ServerPlayer player,
                                    LivingEntity playerMech, Entity entity,
                                    AshenSpanDefinition.PhaseSpec phase,
                                    AshenSpanDefinition.UnitSlot slot) {
        for (int[] offset : RECOVERY_OFFSETS) {
            AshenSpanDefinition.BlockPoint anchor = new AshenSpanDefinition.BlockPoint(
                    phase.recoveryAnchor().x() + offset[0], phase.recoveryAnchor().y(),
                    phase.recoveryAnchor().z() + offset[1]);
            if (placeAt(level, player, playerMech, entity, anchor, slot, false)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeAt(ServerLevel level, ServerPlayer player, LivingEntity playerMech,
                            Entity entity, AshenSpanDefinition.BlockPoint point,
                            AshenSpanDefinition.UnitSlot slot, boolean exactSocket) {
        float yaw = slot.facing() == AshenSpanDefinition.Facing.WEST ? 90.0F : -90.0F;
        entity.moveTo(point.x() + 0.5D, point.y(), point.z() + 0.5D, yaw, 0.0F);
        AABB box = entity.getBoundingBox();
        if (!insideBuildHeight(level, box) || !allChunksLoaded(level, box)
                || !containsAabb(AshenSpanDefinition.PLAYABLE_BOUNDS, box)
                || !level.noCollision(entity, box)) {
            return false;
        }
        if (slot.unit() != AshenSpanDefinition.UnitKind.PMS02_WASP
                && !hasFullFootprintSupport(level, box)) {
            return false;
        }
        if (playerMech.getBoundingBox().inflate(3.0D).intersects(box)
                || currentRoots.values().stream().anyMatch(root ->
                root.entity != entity && root.entity.getBoundingBox().inflate(2.0D).intersects(box))) {
            return false;
        }
        if (!slot.cameraConeExemptOnActivation()) {
            Vec3 eye = player.getEyePosition();
            Vec3 aim = box.getCenter();
            boolean visible = level.clip(new ClipContext(eye, aim,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS;
            // Exact sockets must be physically occluded. Once a shutter has opened,
            // recovery may use the bounded anchor only outside the pilot's 100-degree
            // forward camera cone; visible pop-in inside that cone remains forbidden.
            if (rejectNormalPlacement(exactSocket, player.getLookAngle(),
                    aim.subtract(eye), visible)) {
                return false;
            }
        }
        return exactSocket || horizontalDistanceSqr(entity.getX(), entity.getZ(),
                phaseCenterX(), phaseCenterZ()) <= 150.0D * 150.0D;
    }

    private double phaseCenterX() {
        return model.currentPhase().map(phase -> (double) phase.recoveryAnchor().x()).orElse(0.0D);
    }

    private double phaseCenterZ() {
        return model.currentPhase().map(phase -> (double) phase.recoveryAnchor().z()).orElse(0.0D);
    }

    private void clearRootDescendants(ServerLevel level, UUID rootId) {
        clearRootDescendants(level, ownership, rootId, true);
    }

    static void clearTransientDescendantsForRelocation(ServerLevel level,
                                                        ArenaOwnershipRegistry ownership,
                                                        UUID rootId) {
        clearRootDescendants(level, ownership, rootId, false);
    }

    private static void clearRootDescendants(ServerLevel level,
                                             ArenaOwnershipRegistry ownership,
                                             UUID rootId,
                                             boolean includeHitboxes) {
        for (UUID descendant : ownership.descendantIds(rootId)) {
            ArenaOwnershipRegistry.DescendantRecord record = ownership.descendant(descendant);
            if (!includeHitboxes && record != null
                    && record.kind() == ArenaOwnershipRegistry.DescendantKind.HITBOX) {
                continue;
            }
            Entity entity = level.getEntity(descendant);
            if (entity != null) entity.discard();
            ownership.remove(descendant);
        }
    }

    private void retireClearedRoots(ServerLevel level) {
        for (TrackedRoot tracked : currentRoots.values()) {
            clearRootDescendants(level, tracked.entity.getUUID());
            if (tracked.entity instanceof ArenaRivalPmvc01Entity rival) {
                rival.deactivateAutonomy();
            }
            ownership.remove(tracked.entity.getUUID());
        }
        currentRoots.clear();
        currentPhaseActivated = false;
    }

    private void rollbackStaging() {
        for (TrackedRoot tracked : currentRoots.values()) {
            tracked.entity.discard();
            ArenaHooks.unregisterRoot(tracked.entity.getUUID());
        }
        currentRoots.clear();
        for (TrackedRoot tracked : reservedRoots.values()) {
            if (tracked.entity instanceof ArenaRivalPmvc01Entity rival) {
                rival.deactivateAutonomy();
            }
            tracked.entity.discard();
            ArenaHooks.unregisterRoot(tracked.entity.getUUID());
        }
        reservedRoots.clear();
    }

    private boolean openGate(MinecraftServer server, ServerLevel level,
                             AshenSpanDefinition.GateId gate) {
        if (!MissionGateLedger.open(server, level, AshenSpanDefinition.gate(gate).volume())) {
            return fail("could not durably open " + gate);
        }
        return true;
    }

    private AshenSpanMissionModel.HostileCounts hostileCounts() {
        return new AshenSpanMissionModel.HostileCounts(
                ownership.liveHostileRootCount(),
                ownership.hostileDescendantCount(ArenaOwnershipRegistry.DescendantKind.PROJECTILE),
                ownership.hostileDescendantCount(ArenaOwnershipRegistry.DescendantKind.EFFECT),
                ownership.hostileDescendantCount(ArenaOwnershipRegistry.DescendantKind.HITBOX));
    }

    private void enforceContainment(ServerLevel level, LivingEntity mech) {
        if (requiresPowerDeckFallback(model == null ? null : model.status(),
                model == null ? null : model.currentPhase().map(
                        AshenSpanDefinition.PhaseSpec::id).orElse(null),
                mech.getX(), mech.getY(), mech.getZ())) {
            AshenSpanDefinition.BlockPoint fallback = new AshenSpanDefinition.BlockPoint(92, 73, 0);
            mech.moveTo(fallback.x() + 0.5D, fallback.y(), fallback.z() + 0.5D,
                    -90.0F, 0.0F);
            mech.setDeltaMovement(Vec3.ZERO);
            messages.add("POWER DECK RECOVERY");
            return;
        }
        AABB box = mech.getBoundingBox();
        AshenSpanDefinition.HorizontalBounds bounds = AshenSpanDefinition.PLAYABLE_BOUNDS;
        double halfX = box.getXsize() * 0.5D;
        double halfZ = box.getZsize() * 0.5D;
        double x = Mth.clamp(mech.getX(), bounds.minX() + halfX, bounds.maxX() - halfX);
        double z = Mth.clamp(mech.getZ(), bounds.minZ() + halfZ, bounds.maxZ() - halfZ);
        if (x != mech.getX() || z != mech.getZ()) {
            mech.setPos(x, mech.getY(), z);
            mech.setDeltaMovement(Vec3.ZERO);
        }
    }

    static boolean requiresPowerDeckFallback(@Nullable AshenSpanMissionModel.Status status,
                                             @Nullable AshenSpanDefinition.PhaseId phase,
                                             double x, double y, double z) {
        if (status != AshenSpanMissionModel.Status.ACTIVE
                || phase != AshenSpanDefinition.PhaseId.POWER_DECK) {
            return false;
        }
        return y < 70.0D
                || x < AshenSpanDefinition.EAST_POWER_DECK.minX()
                || x > AshenSpanDefinition.EAST_POWER_DECK.maxX()
                || z < AshenSpanDefinition.EAST_POWER_DECK.minZ()
                || z > AshenSpanDefinition.EAST_POWER_DECK.maxZ();
    }

    static boolean containsAabb(AshenSpanDefinition.HorizontalBounds bounds, AABB box) {
        return box.minX >= bounds.minX() && box.maxX <= bounds.maxX() + 1.0D
                && box.minZ >= bounds.minZ() && box.maxZ <= bounds.maxZ() + 1.0D;
    }

    private static boolean insideBuildHeight(ServerLevel level, AABB box) {
        return box.minY >= level.getMinBuildHeight() && box.maxY < level.getMaxBuildHeight();
    }

    private static boolean allChunksLoaded(ServerLevel level, AABB box) {
        int y = Mth.floor(box.minY);
        return level.hasChunkAt(new BlockPos(Mth.floor(box.minX), y, Mth.floor(box.minZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.minX), y, Mth.floor(box.maxZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.maxX), y, Mth.floor(box.minZ)))
                && level.hasChunkAt(new BlockPos(Mth.floor(box.maxX), y, Mth.floor(box.maxZ)));
    }

    private static boolean hasFullFootprintSupport(ServerLevel level, AABB box) {
        double insetX = Math.min(0.25D, box.getXsize() * 0.15D);
        double insetZ = Math.min(0.25D, box.getZsize() * 0.15D);
        double[] xs = {box.minX + insetX, box.maxX - insetX};
        double[] zs = {box.minZ + insetZ, box.maxZ - insetZ};
        for (double x : xs) {
            for (double z : zs) {
                BlockPos support = BlockPos.containing(x, box.minY - 0.05D, z);
                if (!level.hasChunkAt(support)
                        || !level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double horizontalDistanceSqr(double x1, double z1,
                                                double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private boolean fail(String reason) {
        return fail(reason, AshenSpanMissionModel.DefeatReason.MISSION_ABORTED);
    }

    private boolean fail(String reason, AshenSpanMissionModel.DefeatReason defeatReason) {
        if (failureReason.isEmpty()) {
            failureReason = reason;
        }
        if (model != null && !model.isTerminal()) {
            model.forceDefeat(defeatReason);
        }
        return false;
    }

    static AshenSpanMissionModel.DefeatReason recoveryExhaustionDefeatReason(
            AshenSpanDefinition.UnitKind unit) {
        return unit == AshenSpanDefinition.UnitKind.PMB04_SPAN_WARDEN
                ? AshenSpanMissionModel.DefeatReason.BOSS_RECOVERY_EXHAUSTED
                : AshenSpanMissionModel.DefeatReason.MISSION_ABORTED;
    }

    static List<AshenSpanDefinition.BlockVolume> revealShutters(
            AshenSpanDefinition.PhaseId phase) {
        return REVEAL_SHUTTERS.getOrDefault(phase, List.of());
    }

    static boolean rejectNormalPlacement(boolean exactSocket, Vec3 look,
                                         Vec3 candidateOffset, boolean rayVisible) {
        if (!rayVisible) {
            return false;
        }
        return exactSocket || SpawnDirector.rejectVisibleCandidate(
                look.x, look.y, look.z,
                candidateOffset.x, candidateOffset.y, candidateOffset.z,
                100.0D, true);
    }

    private static String formatTicks(int ticks) {
        int totalSeconds = Math.max(0, ticks / 20);
        return String.format(java.util.Locale.ROOT, "%d:%02d", totalSeconds / 60,
                totalSeconds % 60);
    }

    private static AshenSpanDefinition.BlockVolume volume(int x, int minY, int maxY,
                                                          int minZ, int maxZ) {
        return new AshenSpanDefinition.BlockVolume(x, x, minY, maxY, minZ, maxZ);
    }

    private static AshenSpanDefinition.BlockVolume volume(int minX, int maxX,
                                                          int minY, int maxY,
                                                          int minZ, int maxZ) {
        return new AshenSpanDefinition.BlockVolume(
                minX, maxX, minY, maxY, minZ, maxZ);
    }
}
