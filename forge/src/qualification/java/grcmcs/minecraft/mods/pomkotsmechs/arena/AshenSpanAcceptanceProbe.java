package grcmcs.minecraft.mods.pomkotsmechs.qualification;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaHooks;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaData;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaOwnershipRegistry;
import grcmcs.minecraft.mods.pomkotsmechs.arena.AshenSpanDefinition;
import grcmcs.minecraft.mods.pomkotsmechs.arena.AshenSpanMapContract;
import grcmcs.minecraft.mods.pomkotsmechs.arena.GarageFleet;
import grcmcs.minecraft.mods.pomkotsmechs.arena.MissionGateBlock;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.Pmvc01Entity;
import grcmcs.minecraft.mods.pomkotsmechs.items.parts.BasePartsItem;
import grcmcs.minecraft.mods.pomkotsmechs.qualification.mixin.AshenSpanHooksAccessor;
import grcmcs.minecraft.mods.pomkotsmechs.qualification.mixin.AshenSpanOwnershipAccessor;
import grcmcs.minecraft.mods.pomkotsmechs.qualification.mixin.AshenSpanPlayerListAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drives the real Ashen Span command/runtime path on a disposable candidate
 * world.  It is intentionally direct and authored: this is not a reusable AI,
 * navigation system, combat simulator, or production gameplay feature.
 */
final class AshenSpanAcceptanceProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final long SEED = 4707185498036326465L;
    private static final String SOURCE_COMMIT = "d96b7b84688e925f311849d7c40f72a4f8a691c2";
    private static final String WORLD_CLAIM =
            "runtime probe only; external runner verifies exact 680-full-chunk inventory";
    private static final UUID PILOT_ID = UUID.fromString("54f52cd8-728d-4ccb-95c7-6d8ccf7471e9");
    private static final GameProfile PILOT_PROFILE = new GameProfile(PILOT_ID, "AshenSpanProbe");
    private static final int INITIAL_DELAY_TICKS = 100;
    private static final int STATE_TIMEOUT_TICKS = 600;
    private static final int WHOLE_RUN_TIMEOUT_TICKS = 18_000;
    private static final String TAG_SOLO = "mecharena_solo";
    private static final String TAG_SOLO_PROJECTILE = "mecharena_solo_projectile";
    private static final String TAG_MATCH_PREFIX = "mecharena_match_";
    private static final int BUILD_LIMIT = Integer.getInteger(
            "ashenSpan.qualification.buildLimit", 6);
    private static final List<AshenSpanDefinition.PhaseId> PHASES = List.of(
            AshenSpanDefinition.PhaseId.DROP_DECK,
            AshenSpanDefinition.PhaseId.FREIGHT_CANYON,
            AshenSpanDefinition.PhaseId.WEST_SPAN,
            AshenSpanDefinition.PhaseId.EAST_SPAN,
            AshenSpanDefinition.PhaseId.GATEHOUSE_ESCORTS,
            AshenSpanDefinition.PhaseId.GATEKEEPER,
            AshenSpanDefinition.PhaseId.POWER_DECK);
    private static final List<AshenSpanDefinition.BlockVolume> REVEAL_SHUTTERS = List.of(
            volume(-151, 83, 89, -17, -11), volume(-151, 83, 89, 11, 17),
            volume(-153, 83, 89, -3, 3), volume(-113, 65, 78, -28, -19),
            volume(-113, 65, 78, 19, 28), volume(-97, 65, 78, -5, 5),
            volume(-64, 73, 78, -5, 6), volume(0, 1, 73, 78, -13, 14),
            volume(45, 73, 82, -23, -8), volume(45, 73, 82, 8, 23));

    private enum Step {
        BOOT,
        START,
        VERIFY_FIRST_START,
        VERIFY_RETRY,
        RUN_PHASES,
        WAIT_CLEANUP,
        DONE
    }

    private final List<Map<String, Object>> buildEvidence = new ArrayList<>();
    private final EnumMap<AshenSpanDefinition.GateId, List<BlockState>> gateBaseline =
            new EnumMap<>(AshenSpanDefinition.GateId.class);
    private final Map<BlockPos, BlockState> mutationBaseline = new LinkedHashMap<>();
    private final LinkedHashSet<AshenSpanDefinition.PhaseId> observedPhases = new LinkedHashSet<>();
    private final LinkedHashSet<AshenSpanDefinition.PhaseId> stagedPhasesVerified =
            new LinkedHashSet<>();
    private Map<String, String> pendingRestoreBaseline = Map.of();
    private Step step = Step.BOOT;
    private FakePlayer pilot;
    private int totalTicks;
    private int stepTicks;
    private int buildStartTick = -1;
    private int build = 1;
    private int phaseIndex;
    private boolean phaseRetired;
    private UUID firstStartMech;
    private Pmvc01Entity activeMech;
    private int normalRoots;
    private boolean serviceVerified;
    private boolean gatekeeperVerified;
    private boolean bossVerified;
    private boolean bossHitboxVerified;
    private boolean retryVerified;
    private boolean initialTemplateVerified;
    private boolean retryTemplateVerified;
    private boolean retryMutationRestoreVerified;
    private boolean retryExactZeroVerified;
    private boolean serviceResourcesDegraded;
    private boolean gatekeeperUuidContinuityVerified;
    private boolean ownershipRegistryEmptyVerified;
    private boolean ownershipSecondaryIndexesEmptyVerified;
    private UUID stagedGatekeeperRootId;
    private ArenaOwnershipRegistry activeBuildOwnership;
    private int degradedFuelCount;
    private boolean terminal;
    private String failureCode = "";
    private String failureMessage = "";
    private double originalX;
    private double originalY;
    private double originalZ;
    private float originalYaw;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || terminal) {
            return;
        }
        MinecraftServer server = event.getServer();
        try {
            totalTicks++;
            stepTicks++;
            if (totalTicks > WHOLE_RUN_TIMEOUT_TICKS) {
                fail(server, "WHOLE_RUN_TIMEOUT", "qualification exceeded " + WHOLE_RUN_TIMEOUT_TICKS + " ticks");
                return;
            }
            if (step != Step.BOOT && step != Step.DONE && stepTicks > STATE_TIMEOUT_TICKS) {
                fail(server, "STATE_TIMEOUT", "step " + step + " exceeded " + STATE_TIMEOUT_TICKS + " ticks");
                return;
            }
            tick(server);
        } catch (Throwable throwable) {
            LOGGER.error("ASHEN_SPAN_QUALIFICATION_EXCEPTION", throwable);
            fail(server, "UNCAUGHT_EXCEPTION", throwable.getClass().getName() + ": " + throwable.getMessage());
        }
    }

    private void tick(MinecraftServer server) {
        switch (step) {
            case BOOT -> boot(server);
            case START -> startBuild(server);
            case VERIFY_FIRST_START -> verifyFirstStart(server);
            case VERIFY_RETRY -> verifyRetry(server);
            case RUN_PHASES -> runPhases(server);
            case WAIT_CLEANUP -> waitForCleanup(server);
            case DONE -> finishPass(server);
        }
    }

    private void boot(MinecraftServer server) {
        if (totalTicks < INITIAL_DELAY_TICKS) {
            return;
        }
        Path output = outputPath();
        if (BUILD_LIMIT < 1 || BUILD_LIMIT > 6) {
            fail(server, "BUILD_LIMIT", "build limit must be 1-6");
            return;
        }
        if (Files.exists(output)) {
            fail(server, "OUTPUT_EXISTS", "probe output already exists");
            return;
        }
        ServerLevel level = server.overworld();
        AshenSpanMapContract.Result contract = AshenSpanMapContract.validate(server, level);
        if (!contract.valid()) {
            fail(server, "MAP_CONTRACT", contract.error());
            return;
        }
        snapshotGates(level);
        ArenaData data = ArenaData.get(server);
        require(server, data.getMissionGateBlocks().isEmpty(), "PREEXISTING_GATE_LEDGER",
                "candidate world has a non-empty mission gate ledger before qualification");
        if (terminal) return;
        require(server, level.getForcedChunks().isEmpty(), "PREEXISTING_FORCED_CHUNKS",
                "candidate world has pre-existing forced chunks");
        if (terminal) return;
        hydrateInitialPackagedChunks(level);
        pendingRestoreBaseline = pendingRestoreSnapshot(server);
        pilot = new QualificationFakePlayer(level, PILOT_PROFILE);
        pilot.setGameMode(GameType.SURVIVAL);
        pilot.setInvulnerable(true);
        // Exercise the packaged first-run position exactly. The RC6 production
        // collision fix must admit the pilot while excluding it from the PMVC01
        // entity-collision predicate; an offset caller would hide that regression.
        pilot.setPos(AshenSpanDefinition.PLAYER_PAD.x(), AshenSpanDefinition.PLAYER_PAD.y(),
                AshenSpanDefinition.PLAYER_PAD.z());
        pilot.setYRot(0.0F);
        originalX = pilot.getX();
        originalY = pilot.getY();
        originalZ = pilot.getZ();
        originalYaw = pilot.getYRot();
        AshenSpanPlayerListAccessor playerList = (AshenSpanPlayerListAccessor) server.getPlayerList();
        playerList.ashenSpan$getPlayers().add(pilot);
        playerList.ashenSpan$getPlayersByUuid().put(PILOT_ID, pilot);
        level.addNewPlayer(pilot);
        LOGGER.info("ASHEN_SPAN_QUALIFICATION_MAP_PASS chunks=680 markers={}",
                AshenSpanMapContract.markers().size());
        transition(Step.START);
    }

    private void startBuild(MinecraftServer server) {
        resetBuildState();
        if (!loadDeploymentChunks(server.overworld())
                || !loadPhaseChunks(server.overworld(), AshenSpanDefinition.PhaseId.DROP_DECK)) {
            return;
        }
        logDeploymentPreflight(server.overworld());
        buildStartTick = totalTicks;
        int result = command(server, "arena solo start " + build + " " + SEED);
        require(server, result == 1, "START_COMMAND", "real Brigadier start returned " + result);
        if (terminal) return;
        activeBuildOwnership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
        require(server, activeBuildOwnership != null, "START_OWNERSHIP",
                "real start did not expose an active ownership registry");
        if (terminal) return;
        observeStagedRoots(server, server.overworld());
        if (terminal) return;
        require(server, stagedPhasesVerified.contains(AshenSpanDefinition.PhaseId.DROP_DECK),
                "START_STAGING", "real start did not expose the staged Drop Deck socket set");
        if (terminal) return;
        require(server, pilot.getVehicle() instanceof Pmvc01Entity,
                "START_MOUNT", "real start did not mount a PMVC01");
        if (terminal) return;
        activeMech = (Pmvc01Entity) pilot.getVehicle();
        activeMech.setInvulnerable(true);
        initialTemplateVerified = fleetTemplateMatches(server.overworld(), true);
        require(server, initialTemplateVerified, "START_TEMPLATE",
                "real start did not mount exact Garage Fleet build " + build);
        if (terminal) return;
        firstStartMech = activeMech.getUUID();
        transition(Step.VERIFY_FIRST_START);
    }

    private boolean loadDeploymentChunks(ServerLevel level) {
        // Touch only already-packaged chunks intersecting the locked Garage
        // footprint. Persistent forced tickets are forbidden: they request a
        // generated dependency halo outside the bounded 680-chunk candidate.
        for (int x : new int[]{AshenSpanDefinition.PLAYER_PAD.x() - 2,
                AshenSpanDefinition.PLAYER_PAD.x() + 2}) {
            for (int z : new int[]{AshenSpanDefinition.PLAYER_PAD.z() - 2,
                    AshenSpanDefinition.PLAYER_PAD.z() + 2}) {
                if (!loadExistingChunk(level, x, z)) return false;
            }
        }
        return true;
    }

    private void hydrateInitialPackagedChunks(ServerLevel level) {
        // Do this before registering the FakePlayer, so synchronous disk
        // hydration cannot combine with a west-pad player ticket and promote
        // the safety fringe. All requested chunks already exist as FULL in the
        // externally verified 680-chunk candidate.
        for (int x : new int[]{AshenSpanDefinition.PLAYER_PAD.x() - 2,
                AshenSpanDefinition.PLAYER_PAD.x() + 2}) {
            for (int z : new int[]{AshenSpanDefinition.PLAYER_PAD.z() - 2,
                    AshenSpanDefinition.PLAYER_PAD.z() + 2}) {
                level.getChunk(x >> 4, z >> 4);
            }
        }
        for (AshenSpanDefinition.UnitSlot slot : AshenSpanDefinition
                .phase(AshenSpanDefinition.PhaseId.DROP_DECK).slots()) {
            level.getChunk(slot.socket().x() >> 4, slot.socket().z() >> 4);
        }
    }

    private boolean loadExistingChunk(ServerLevel level, int blockX, int blockZ) {
        ChunkPos pos = new ChunkPos(blockX >> 4, blockZ >> 4);
        return level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) != null;
    }

    private void logDeploymentPreflight(ServerLevel level) {
        LivingEntity built = GarageFleet.build(level, build - 1);
        if (!(built instanceof Pmvc01Entity debug)) {
            LOGGER.error("ASHEN_SPAN_QUALIFICATION_DEPLOYMENT_PREFLIGHT build={} no-template", build);
            return;
        }
        debug.resetForArenaService();
        debug.setPos(AshenSpanDefinition.PLAYER_PAD.x(), AshenSpanDefinition.PLAYER_PAD.y(),
                AshenSpanDefinition.PLAYER_PAD.z());
        net.minecraft.world.phys.AABB box = debug.getBoundingBox();
        BlockPos floor = BlockPos.containing(debug.position()).below();
        List<String> colliders = level.getEntities(debug, box, Entity::canBeCollidedWith).stream()
                .map(entity -> entity.getType().toString() + "@" + entity.position()).toList();
        LOGGER.info("ASHEN_SPAN_QUALIFICATION_DEPLOYMENT_PREFLIGHT build={} pilot={} bbox={} "
                        + "chunk={} floor={} blockCollision={} noCollision={} colliders={}",
                build, pilot.position(), box, level.hasChunkAt(floor),
                level.getBlockState(floor),
                level.getBlockCollisions(debug, box).iterator().hasNext(),
                level.noCollision(debug, box), colliders);
        debug.discard();
    }

    private void verifyFirstStart(MinecraftServer server) {
        if (!phaseReady(AshenSpanDefinition.PhaseId.DROP_DECK)) {
            return;
        }
        List<MissionGateBlock> firstRunMutations = ArenaData.get(server).getMissionGateBlocks();
        require(server, !firstRunMutations.isEmpty(), "RETRY_MUTATION_LEDGER",
                "first run opened no authored gate/shutter mutations before retry");
        if (terminal) return;
        ArenaOwnershipRegistry firstOwnership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
        Set<UUID> firstRunOwned = new LinkedHashSet<>(firstOwnership.ownedIds());
        Set<String> firstRunMatchTags = new LinkedHashSet<>();
        for (UUID id : firstRunOwned) {
            Entity entity = server.overworld().getEntity(id);
            if (entity != null) entity.getTags().stream()
                    .filter(tag -> tag.startsWith(TAG_MATCH_PREFIX)).forEach(firstRunMatchTags::add);
        }
        int result = command(server, "arena solo retry");
        require(server, result == 1, "RETRY_COMMAND", "real Brigadier retry returned " + result);
        if (terminal) return;
        activeBuildOwnership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
        require(server, activeBuildOwnership != null, "RETRY_OWNERSHIP",
                "real retry did not expose an active ownership registry");
        if (terminal) return;
        // The retry creates a second real Drop Deck roster. Verify its staged
        // sockets independently instead of carrying the first-start observation.
        stagedPhasesVerified.remove(AshenSpanDefinition.PhaseId.DROP_DECK);
        observeStagedRoots(server, server.overworld());
        if (terminal) return;
        require(server, stagedPhasesVerified.contains(AshenSpanDefinition.PhaseId.DROP_DECK),
                "RETRY_STAGING", "real retry did not expose the staged Drop Deck socket set");
        if (terminal) return;
        boolean oldMatchTagLive = false;
        for (Entity entity : server.overworld().getAllEntities()) {
            if (entity.getTags().stream().anyMatch(firstRunMatchTags::contains)) oldMatchTagLive = true;
        }
        retryExactZeroVerified = !oldMatchTagLive && firstRunOwned.stream().allMatch(id -> {
            Entity entity = server.overworld().getEntity(id);
            return entity == null || entity.isRemoved();
        });
        require(server, retryExactZeroVerified, "RETRY_EXACT_ZERO",
                "retry left a run-1 UUID or match tag live in the actual world");
        if (terminal) return;
        retryMutationRestoreVerified = ledgerStatesRestored(server, firstRunMutations);
        require(server, retryMutationRestoreVerified, "RETRY_MUTATION_RESTORE",
                "retry did not restore every first-run gate/shutter BlockState before restaging");
        if (terminal) return;
        require(server, AshenSpanDefinition.GateId.values().length == gateBaseline.size()
                        && java.util.Arrays.stream(AshenSpanDefinition.GateId.values())
                        .allMatch(gate -> gateAtBaseline(server.overworld(), gate)),
                "RETRY_GATE_RESTORE", "retry did not restore the full fixed-gate baseline");
        if (terminal) return;
        require(server, pilot.getVehicle() instanceof Pmvc01Entity,
                "RETRY_MOUNT", "retry did not mount a PMVC01");
        if (terminal) return;
        activeMech = (Pmvc01Entity) pilot.getVehicle();
        activeMech.setInvulnerable(true);
        retryTemplateVerified = fleetTemplateMatches(server.overworld(), true);
        require(server, retryTemplateVerified, "RETRY_TEMPLATE",
                "real retry did not remount exact Garage Fleet build " + build);
        if (terminal) return;
        require(server, !activeMech.getUUID().equals(firstStartMech),
                "RETRY_IDENTITY", "retry reused the discarded player-mech entity");
        if (terminal) return;
        transition(Step.VERIFY_RETRY);
    }

    private void verifyRetry(MinecraftServer server) {
        if (!phaseReady(AshenSpanDefinition.PhaseId.DROP_DECK)) {
            return;
        }
        retryVerified = true;
        transition(Step.RUN_PHASES);
    }

    private void runPhases(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (phaseIndex >= PHASES.size()) {
            transition(Step.WAIT_CLEANUP);
            return;
        }
        AshenSpanDefinition.PhaseId expected = PHASES.get(phaseIndex);
        loadPhaseChunks(level, expected);
        moveForTrigger(expected);
        observeStagedRoots(server, level);
        if (terminal) return;
        AshenSpanDefinition.PhaseId actual = currentHostilePhase(level);
        if (phaseRetired) {
            retireHostileDescendants(level);
            if (actual == null) {
                if (expected == AshenSpanDefinition.PhaseId.POWER_DECK && !ArenaHooks.isActive()) {
                    phaseIndex++;
                    phaseRetired = false;
                    transition(Step.WAIT_CLEANUP);
                } else {
                    // A threshold-gated successor legitimately has no roots yet.
                    // The retired phase is complete; advance the expected phase
                    // so its movement trigger can be crossed on the next tick.
                    phaseIndex++;
                    phaseRetired = false;
                    transition(Step.RUN_PHASES);
                }
                return;
            }
            if (actual != expected) {
                phaseIndex++;
                phaseRetired = false;
                transition(Step.RUN_PHASES);
            }
            return;
        }
        if (actual == null) {
            retireHostileDescendants(level);
            return;
        }
        require(server, actual == expected, "PHASE_ORDER",
                "expected " + expected + " but live ownership reports " + actual);
        if (terminal) return;

        if (!phaseReady(expected)) {
            return;
        }
        if (!observedPhases.contains(expected)) {
            verifyPhaseRoster(server, level, expected);
            if (terminal) return;
            verifyPriorGate(server, level, expected);
            if (terminal) return;
            observedPhases.add(expected);
            if (expected == AshenSpanDefinition.PhaseId.GATEKEEPER) {
                degradeForServiceVerification();
            }
        }
        if (!phaseRetired) {
            if (phaseIndex + 1 < PHASES.size()) {
                // Production stages the next phase before this probe's next END
                // tick. Preload its packaged chunks before retiring the current
                // roots so asynchronous player-ticket scheduling cannot race
                // the next authored staging transition.
                loadPhaseChunks(level, PHASES.get(phaseIndex + 1));
            }
            retireCurrentHostiles(level);
            phaseRetired = true;
        }
    }

    private void waitForCleanup(MinecraftServer server) {
        if (ArenaHooks.isActive()) {
            retireHostileDescendants(server.overworld());
            return;
        }
        require(server, server.overworld().getForcedChunks().isEmpty(), "FORCED_CHUNK_RESTORE",
                "qualification observed a persistent forced chunk");
        if (terminal) return;
        require(server, observedPhases.equals(new LinkedHashSet<>(PHASES)),
                "PHASE_COMPLETENESS", "observed phases " + observedPhases);
        require(server, normalRoots == AshenSpanDefinition.EXPECTED_NORMAL_ROOTS,
                "NORMAL_ROSTER", "retired " + normalRoots + " normal roots");
        require(server, gatekeeperVerified, "GATEKEEPER", "Gatekeeper R-01 identity was not observed");
        require(server, bossVerified, "SPAN_WARDEN", "PMB04 Span Warden identity was not observed");
        if (terminal) return;
        require(server, bossHitboxVerified, "SPAN_WARDEN_HITBOX",
                "PMB04 supplemental hitbox was not admitted under its root lineage");
        require(server, serviceVerified, "SERVICE", "Garage Fleet service template was not restored");
        require(server, stagedPhasesVerified.equals(new LinkedHashSet<>(PHASES)),
                "STAGED_PHASE_COMPLETENESS",
                "exact staged socket observations were " + stagedPhasesVerified);
        if (terminal) return;
        require(server, gatekeeperUuidContinuityVerified, "GATEKEEPER_UUID_CONTINUITY",
                "Gatekeeper 5A reserve UUID continuity was not proven at 5B promotion");
        if (terminal) return;
        require(server, gatesEqual(server.overworld()), "FINAL_GATE_RESTORE",
                "terminal cleanup did not restore exact BlockStates");
        if (terminal) return;
        ownershipRegistryEmptyVerified = activeBuildOwnership != null
                && activeBuildOwnership.ownedIds().isEmpty()
                && activeBuildOwnership.liveRootCount() == 0
                && activeBuildOwnership.liveHostileRootCount() == 0
                && activeBuildOwnership.hostileDescendantCount() == 0
                && activeBuildOwnership.projectileCount() == 0
                && activeBuildOwnership.effectCount() == 0
                && activeBuildOwnership.hitboxCount() == 0;
        require(server, ownershipRegistryEmptyVerified, "OWNERSHIP_REGISTRY_NOT_EMPTY",
                "terminal cleanup left entries in the captured ownership registry");
        if (terminal) return;
        AshenSpanOwnershipAccessor ownershipIndexes =
                (AshenSpanOwnershipAccessor) (Object) activeBuildOwnership;
        ownershipSecondaryIndexesEmptyVerified = ownershipIndexes.ashenSpan$getRoots().isEmpty()
                && ownershipIndexes.ashenSpan$getDescendants().isEmpty()
                && ownershipIndexes.ashenSpan$getGoneRootBodies().isEmpty()
                && AshenSpanHooksAccessor.ashenSpan$getLastKnownPositions().isEmpty()
                && AshenSpanHooksAccessor.ashenSpan$getOwnership() == null;
        require(server, ownershipSecondaryIndexesEmptyVerified,
                "OWNERSHIP_SECONDARY_INDEX_NOT_EMPTY",
                "terminal cleanup left an ownership root/descendant/tombstone/position index");
        if (terminal) return;
        require(server, missionResidue(server) == 0, "EXACT_ZERO",
                "terminal cleanup left " + missionResidue(server) + " mission-tagged entities");
        require(server, pilot.getVehicle() == null, "PLAYER_DISMOUNT", "terminal cleanup left pilot mounted");
        if (terminal) return;
        require(server, pilot.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                "PLAYER_RESTORE", "terminal cleanup did not restore pilot gamemode");
        if (terminal) return;
        require(server, samePlayerRestore(), "PLAYER_POSITION_RESTORE",
                "terminal cleanup did not restore exact pilot dimension/position/yaw");
        if (terminal) return;
        ArenaData data = ArenaData.get(server);
        require(server, data.getPendingRestore(PILOT_ID) == null, "PENDING_RESTORE",
                "terminal cleanup left a pending restore for the qualification pilot");
        if (terminal) return;
        require(server, pendingRestoreSnapshot(server).equals(pendingRestoreBaseline),
                "RESTORE_BASELINE", "terminal cleanup changed the durable restore baseline");
        if (terminal) return;
        require(server, data.getMissionGateBlocks().isEmpty(), "MISSION_GATE_LEDGER",
                "terminal cleanup left durable authored gate/shutter entries");
        if (terminal) return;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("build", build);
        evidence.put("name", GarageFleet.name(build - 1));
        evidence.put("command_path", "arena solo start");
        evidence.put("brigadier_start", true);
        evidence.put("brigadier_retry", retryVerified);
        evidence.put("initial_template", initialTemplateVerified);
        evidence.put("retry_template", retryTemplateVerified);
        evidence.put("phase_order", PHASES.stream().map(Enum::name).toList());
        evidence.put("normal_roots", normalRoots);
        evidence.put("gatekeeper_entity", AshenSpanDefinition.UnitKind.GATEKEEPER_R01.entityId());
        evidence.put("span_warden_entity", AshenSpanDefinition.UnitKind.PMB04_SPAN_WARDEN.entityId());
        evidence.put("pmb04_hitbox_lineage", bossHitboxVerified);
        evidence.put("service_template_restored", serviceVerified);
        evidence.put("service_resources_degraded", serviceResourcesDegraded);
        evidence.put("root_ownership_metadata", true);
        evidence.put("staged_socket_contract", true);
        evidence.put("gatekeeper_uuid_continuity", gatekeeperUuidContinuityVerified);
        evidence.put("ownership_registry_empty", ownershipRegistryEmptyVerified);
        evidence.put("ownership_secondary_indexes_empty", ownershipSecondaryIndexesEmptyVerified);
        evidence.put("retry_gate_restore", true);
        evidence.put("retry_mutation_restore", retryMutationRestoreVerified);
        evidence.put("retry_exact_zero", retryExactZeroVerified);
        evidence.put("final_gate_restore", true);
        evidence.put("mission_gate_ledger_empty", true);
        evidence.put("forced_chunks_restored", true);
        evidence.put("victory_outcome", true);
        evidence.put("exact_zero", true);
        evidence.put("player_dismounted", true);
        evidence.put("player_position_restore", true);
        evidence.put("player_restore", true);
        evidence.put("durable_restore_cleared", true);
        int elapsedTicks = totalTicks - buildStartTick + 1;
        require(server, buildStartTick > 0 && elapsedTicks > 0,
                "BUILD_ELAPSED_TICKS", "per-build elapsed tick interval was not initialized");
        if (terminal) return;
        evidence.put("ticks", elapsedTicks);
        buildEvidence.add(evidence);
        LOGGER.info("ASHEN_SPAN_QUALIFICATION_BUILD_PASS build={} name={} elapsed_ticks={}", build,
                GarageFleet.name(build - 1), elapsedTicks);

        build++;
        if (build > BUILD_LIMIT) {
            transition(Step.DONE);
        } else {
            transition(Step.START);
        }
    }

    private void finishPass(MinecraftServer server) {
        Map<String, Object> result = baseResult("PASS");
        result.put("builds", buildEvidence);
        result.put("build_limit", BUILD_LIMIT);
        result.put("all_six_builds", BUILD_LIMIT == GarageFleet.size()
                && buildEvidence.size() == GarageFleet.size());
        result.put("safety_chunks", 680);
        result.put("world_claim", WORLD_CLAIM);
        writeResult(result);
        removePilot(server);
        terminal = true;
        LOGGER.info("ASHEN_SPAN_QUALIFICATION_PASS output={}", outputPath());
        server.halt(false);
    }

    private void fail(MinecraftServer server, String code, String message) {
        if (terminal) return;
        terminal = true;
        failureCode = code;
        failureMessage = message == null ? "" : message;
        Map<String, Object> result = baseResult("FAIL");
        result.put("failure_code", failureCode);
        result.put("failure_message", failureMessage);
        result.put("step", step.name());
        result.put("build", build);
        result.put("builds", buildEvidence);
        try {
            if (pilot != null && ArenaHooks.isActive()) {
                command(server, "arena solo stop");
            }
        } catch (Throwable ignored) {
            // Failure evidence must survive even when cleanup itself is the defect.
        }
        writeResult(result);
        removePilot(server);
        LOGGER.error("ASHEN_SPAN_QUALIFICATION_FAIL code={} message={}", code, failureMessage);
        server.halt(false);
    }

    private Map<String, Object> baseResult(String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 2);
        result.put("probe_version", "1.1.0");
        result.put("candidate_id", System.getProperty("ashenSpan.qualification.candidate", "unbound"));
        result.put("source_commit", System.getProperty(
                "ashenSpan.qualification.sourceCommit", SOURCE_COMMIT));
        result.put("runtime_source_commit", System.getProperty(
                "ashenSpan.qualification.runtimeSourceCommit", SOURCE_COMMIT));
        result.put("mission_id", AshenSpanDefinition.MISSION_ID);
        result.put("map_id", AshenSpanDefinition.MAP_ID);
        result.put("seed", SEED);
        result.put("status", status);
        result.put("total_ticks", totalTicks);
        return result;
    }

    private void verifyPhaseRoster(MinecraftServer server, ServerLevel level,
                                   AshenSpanDefinition.PhaseId phaseId) {
        AshenSpanDefinition.PhaseSpec spec = AshenSpanDefinition.phase(phaseId);
        require(server, stagedPhasesVerified.contains(phaseId), "STAGED_PHASE_MISSING",
                phaseId + " activated before its exact staged socket contract was observed");
        if (terminal) return;
        List<ArenaOwnershipRegistry.RootRecord> roots = liveHostileRoots(level);
        if (roots.size() != spec.expectedRootCount()) {
            ArenaOwnershipRegistry ownership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
            List<String> debug = ownership.ownedIds().stream().filter(ownership::isRoot)
                    .map(id -> {
                        ArenaOwnershipRegistry.RootRecord root = ownership.rootFor(id);
                        Entity entity = level.getEntity(id);
                        return root + " entity=" + (entity == null ? "null" : entity.getType()
                                + "@" + entity.position() + " removed=" + entity.isRemoved());
                    }).toList();
            LOGGER.error("ASHEN_SPAN_QUALIFICATION_ROOT_DEBUG phase={} roots={}", phaseId, debug);
        }
        require(server, roots.size() == spec.expectedRootCount(), "PHASE_ROOT_COUNT",
                phaseId + " expected " + spec.expectedRootCount() + " live roots but found " + roots.size());
        if (terminal) return;
        List<String> actualTypes = new ArrayList<>();
        for (ArenaOwnershipRegistry.RootRecord root : roots) {
            Entity entity = level.getEntity(root.rootId());
            require(server, root.role() == ArenaOwnershipRegistry.RootRole.HOSTILE
                            && root.phase().equals(phaseId.name())
                            && root.slot() >= 0 && root.slot() < spec.slots().size(),
                    "ROOT_METADATA", phaseId + " has invalid admitted-root ownership metadata");
            if (terminal) return;
            long matchTags = entity.getTags().stream().filter(tag -> tag.startsWith(TAG_MATCH_PREFIX)).count();
            require(server, entity.getTags().contains(ArenaHooks.TAG_OWNED)
                            && entity.getTags().contains(TAG_SOLO) && matchTags == 1,
                    "ROOT_TAGS", phaseId + " admitted root lacks exact current mission tags");
            if (terminal) return;
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            actualTypes.add(key.toString());
        }
        require(server, roots.stream().map(ArenaOwnershipRegistry.RootRecord::slot).sorted().toList()
                        .equals(java.util.stream.IntStream.range(0, spec.slots().size()).boxed().toList()),
                "ROOT_SLOTS", phaseId + " does not own the exact authored slot set");
        if (terminal) return;
        List<String> expectedTypes = spec.slots().stream().map(slot -> slot.unit().entityId()).sorted().toList();
        actualTypes.sort(String::compareTo);
        require(server, actualTypes.equals(expectedTypes), "PHASE_ENTITY_TYPES",
                phaseId + " expected " + expectedTypes + " but found " + actualTypes);
        if (terminal) return;
        normalRoots += (int) spec.normalRootCount();
        if (phaseId == AshenSpanDefinition.PhaseId.GATEKEEPER) {
            gatekeeperVerified = actualTypes.equals(List.of(AshenSpanDefinition.UnitKind.GATEKEEPER_R01.entityId()));
            require(server, gatekeeperUuidContinuityVerified, "GATEKEEPER_UUID_CONTINUITY",
                    "promoted Gatekeeper UUID differs from its staged 5A reserve root");
            if (terminal) return;
        }
        if (phaseId == AshenSpanDefinition.PhaseId.POWER_DECK) {
            bossVerified = actualTypes.equals(List.of(AshenSpanDefinition.UnitKind.PMB04_SPAN_WARDEN.entityId()));
            UUID bossRoot = roots.get(0).rootId();
            ArenaOwnershipRegistry ownership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
            bossHitboxVerified = ownership.ownedIds().stream().map(ownership::descendant)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(descendant -> descendant.rootId().equals(bossRoot)
                            && descendant.kind() == ArenaOwnershipRegistry.DescendantKind.HITBOX);
        }
    }

    private void verifyPriorGate(MinecraftServer server, ServerLevel level,
                                 AshenSpanDefinition.PhaseId current) {
        Set<AshenSpanDefinition.GateId> open = switch (current) {
            case DROP_DECK -> Set.of();
            case FREIGHT_CANYON -> Set.of(AshenSpanDefinition.GateId.G1);
            case WEST_SPAN -> Set.of(AshenSpanDefinition.GateId.G1, AshenSpanDefinition.GateId.G2);
            case EAST_SPAN -> Set.of(AshenSpanDefinition.GateId.G1, AshenSpanDefinition.GateId.G2,
                    AshenSpanDefinition.GateId.G3);
            case GATEHOUSE_ESCORTS -> Set.of(AshenSpanDefinition.GateId.G1,
                    AshenSpanDefinition.GateId.G2, AshenSpanDefinition.GateId.G3,
                    AshenSpanDefinition.GateId.G4);
            case GATEKEEPER -> Set.of(AshenSpanDefinition.GateId.G1,
                    AshenSpanDefinition.GateId.G2, AshenSpanDefinition.GateId.G3,
                    AshenSpanDefinition.GateId.G4, AshenSpanDefinition.GateId.INTERNAL_SHUTTER);
            case POWER_DECK -> Set.of(AshenSpanDefinition.GateId.values());
        };
        for (AshenSpanDefinition.GateId gate : AshenSpanDefinition.GateId.values()) {
            boolean valid = open.contains(gate) ? gateIsAir(level, gate) : gateAtBaseline(level, gate);
            require(server, valid, "GATE_MATRIX",
                    current + " gate matrix mismatch at " + gate + " expected "
                            + (open.contains(gate) ? "OPEN" : "BASELINE"));
            if (terminal) return;
        }
        if (current == AshenSpanDefinition.PhaseId.POWER_DECK) {
            serviceVerified = verifyServiceTemplate(level);
            require(server, serviceVerified, "SERVICE_TEMPLATE",
                    "service did not restore exact Garage Fleet build " + build);
        }
    }

    private boolean verifyServiceTemplate(ServerLevel level) {
        return serviceResourcesDegraded
                && activeMech.getItem(Pmvc01Entity.INV_FUEL).getCount() > degradedFuelCount
                && fleetTemplateMatches(level, false);
    }

    private void degradeForServiceVerification() {
        activeMech.setHealth(1.0F);
        activeMech.consumeEnergy(Math.max(1, activeMech.getMaxEnergy() / 2));
        boolean hadAmmo = false;
        boolean allAmmoConsumed = true;
        for (int slot = Pmvc01Entity.INV_WEAPON_RIGHT_HAND;
             slot <= Pmvc01Entity.INV_WEAPON_LEFT_SHOULDER; slot++) {
            Pmvc01Entity.AmmoManager ammo = activeMech.getAmmoManager(slot);
            if (!activeMech.getItem(slot).isEmpty() && ammo.getBulletNum() > 0) {
                hadAmmo = true;
                allAmmoConsumed &= activeMech.consumeBulletFromServerSide(
                        1, activeMech.getItem(slot), slot);
            }
        }
        net.minecraft.world.item.ItemStack fuel = activeMech.getItem(Pmvc01Entity.INV_FUEL);
        if (!fuel.isEmpty()) fuel.shrink(1);
        degradedFuelCount = fuel.getCount();
        serviceResourcesDegraded = activeMech.getHealth() == 1.0F
                && activeMech.getEnergy() < activeMech.getMaxEnergy()
                && (!hadAmmo || allAmmoConsumed);
    }

    private boolean fleetTemplateMatches(ServerLevel level, boolean exactFuel) {
        if (activeMech == null) return false;
        LivingEntity built = GarageFleet.build(level, build - 1);
        if (!(built instanceof Pmvc01Entity expected)) {
            if (built != null) built.discard();
            return false;
        }
        expected.resetForArenaService();
        boolean same = activeMech.getContainerSize() == expected.getContainerSize();
        for (int slot = 0; same && slot < activeMech.getContainerSize(); slot++) {
            net.minecraft.world.item.ItemStack actualStack = activeMech.getItem(slot);
            net.minecraft.world.item.ItemStack expectedStack = expected.getItem(slot);
            if (!exactFuel && slot == Pmvc01Entity.INV_FUEL) {
                // The director services during SERVER_POST. Before the probe sees POWER_DECK,
                // the mounted mech has performed exactly one canWork() readiness check: it
                // loads one pellet into the private fuel accumulator without consuming a
                // unit of total fuel. Bind that deterministic state exactly rather than
                // accepting an arbitrary partial refill.
                same = net.minecraft.world.item.ItemStack.isSameItem(actualStack, expectedStack)
                        && actualStack.getCount() == expectedStack.getCount() - 1;
            } else if (exactFuel) {
                same = net.minecraft.world.item.ItemStack.matches(actualStack, expectedStack);
            } else {
                // Weapon runtime state may add transient NBT during the same entity tick.
                // The authored fleet contract is the exact item/count and authored parts
                // level; combat readiness is asserted separately through AmmoManager.
                same = sameTemplateStack(actualStack, expectedStack);
            }
        }
        same = same && activeMech.getHealth() == expected.getHealth()
                && activeMech.getMaxHealth() == expected.getMaxHealth()
                && activeMech.getEnergy() == expected.getEnergy()
                && activeMech.getMaxEnergy() == expected.getMaxEnergy()
                && activeMech.getMaxFuel() == expected.getMaxFuel();
        same = same && activeMech.getFuelNow() == expected.getFuelNow();
        for (int slot = Pmvc01Entity.INV_WEAPON_RIGHT_HAND;
             same && slot <= Pmvc01Entity.INV_WEAPON_LEFT_SHOULDER; slot++) {
            Pmvc01Entity.AmmoManager actualAmmo = activeMech.getAmmoManager(slot);
            Pmvc01Entity.AmmoManager expectedAmmo = expected.getAmmoManager(slot);
            same = actualAmmo.getBulletNum() == expectedAmmo.getBulletNum()
                    && actualAmmo.getBulletNumPerMagazine() == expectedAmmo.getBulletNumPerMagazine()
                    && actualAmmo.getMagazineNum() == expectedAmmo.getMagazineNum()
                    && actualAmmo.getReloadTicks() == expectedAmmo.getReloadTicks();
        }
        if (!same) {
            List<String> slots = new ArrayList<>();
            for (int slot = 0; slot < activeMech.getContainerSize(); slot++) {
                if (!net.minecraft.world.item.ItemStack.matches(activeMech.getItem(slot), expected.getItem(slot))) {
                    slots.add(slot + ":" + activeMech.getItem(slot) + "!=" + expected.getItem(slot));
                }
            }
            LOGGER.error("ASHEN_SPAN_QUALIFICATION_TEMPLATE_DEBUG build={} exactFuel={} slots={} "
                            + "hp={}/{} expected={}/{} energy={}/{} expected={}/{} fuel={}/{} expected={}/{}",
                    build, exactFuel, slots, activeMech.getHealth(), activeMech.getMaxHealth(),
                    expected.getHealth(), expected.getMaxHealth(), activeMech.getEnergy(),
                    activeMech.getMaxEnergy(), expected.getEnergy(), expected.getMaxEnergy(),
                    activeMech.getFuelNow(), activeMech.getMaxFuel(), expected.getFuelNow(), expected.getMaxFuel());
        }
        expected.discard();
        return same;
    }

    private static boolean sameTemplateStack(net.minecraft.world.item.ItemStack actual,
                                             net.minecraft.world.item.ItemStack expected) {
        if (!net.minecraft.world.item.ItemStack.isSameItem(actual, expected)
                || actual.getCount() != expected.getCount()) {
            return false;
        }
        if (actual.isEmpty()) {
            return true;
        }
        if (actual.getItem() instanceof BasePartsItem actualPart
                && expected.getItem() instanceof BasePartsItem expectedPart) {
            return actualPart.getLevel(actual) == expectedPart.getLevel(expected);
        }
        return true;
    }

    private void retireCurrentHostiles(ServerLevel level) {
        for (ArenaOwnershipRegistry.RootRecord root : liveHostileRoots(level)) {
            Entity entity = level.getEntity(root.rootId());
            if (entity instanceof LivingEntity living) {
                living.kill();
            } else if (entity != null) {
                entity.discard();
            }
        }
        retireHostileDescendants(level);
    }

    private void retireHostileDescendants(ServerLevel level) {
        if (!ArenaHooks.isActive()) return;
        ArenaOwnershipRegistry ownership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
        for (UUID id : ownership.ownedIds()) {
            if (ownership.isRoot(id) || !ownership.isHostile(id)) continue;
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) entity.discard();
        }
    }

    private List<ArenaOwnershipRegistry.RootRecord> liveHostileRoots(ServerLevel level) {
        if (!ArenaHooks.isActive()) return List.of();
        ArenaOwnershipRegistry ownership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
        List<ArenaOwnershipRegistry.RootRecord> roots = new ArrayList<>();
        for (UUID id : ownership.ownedIds()) {
            if (!ownership.isRoot(id)) continue;
            ArenaOwnershipRegistry.RootRecord root = ownership.rootFor(id);
            Entity entity = level.getEntity(id);
            if (root != null && root.role() == ArenaOwnershipRegistry.RootRole.HOSTILE
                    && entity != null && !entity.isRemoved()) {
                roots.add(root);
            }
        }
        return roots;
    }

    private AshenSpanDefinition.PhaseId currentHostilePhase(ServerLevel level) {
        Set<String> phases = new LinkedHashSet<>();
        for (ArenaOwnershipRegistry.RootRecord root : liveHostileRoots(level)) phases.add(root.phase());
        if (phases.isEmpty()) return null;
        if (phases.size() != 1) throw new IllegalStateException("multiple live hostile phases: " + phases);
        return AshenSpanDefinition.PhaseId.valueOf(phases.iterator().next());
    }

    private void observeStagedRoots(MinecraftServer server, ServerLevel level) {
        if (!ArenaHooks.isActive()) return;
        ArenaOwnershipRegistry ownership = AshenSpanHooksAccessor.ashenSpan$getOwnership();
        EnumMap<AshenSpanDefinition.PhaseId, List<ArenaOwnershipRegistry.RootRecord>> staged =
                new EnumMap<>(AshenSpanDefinition.PhaseId.class);
        for (UUID id : ownership.ownedIds()) {
            if (!ownership.isRoot(id)) continue;
            ArenaOwnershipRegistry.RootRecord root = ownership.rootFor(id);
            Entity entity = level.getEntity(id);
            if (root == null || entity == null || entity.isRemoved() || !ArenaHooks.isStaged(entity)
                    || root.phase().equals("PLAYER")) {
                continue;
            }
            AshenSpanDefinition.PhaseId phase;
            try {
                phase = AshenSpanDefinition.PhaseId.valueOf(root.phase());
            } catch (IllegalArgumentException exception) {
                fail(server, "STAGED_PHASE_ID", "unknown staged ownership phase " + root.phase());
                return;
            }
            staged.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(root);
        }

        for (Map.Entry<AshenSpanDefinition.PhaseId,
                List<ArenaOwnershipRegistry.RootRecord>> entry : staged.entrySet()) {
            AshenSpanDefinition.PhaseId phase = entry.getKey();
            List<ArenaOwnershipRegistry.RootRecord> roots = entry.getValue();
            if (phase != AshenSpanDefinition.PhaseId.GATEKEEPER) {
                if (!stagedPhasesVerified.contains(phase)
                        && verifyExactStagedPhase(server, level, phase, roots,
                        ArenaOwnershipRegistry.RootRole.HOSTILE)) {
                    stagedPhasesVerified.add(phase);
                }
                if (terminal) return;
                continue;
            }

            require(server, roots.size() == 1, "GATEKEEPER_STAGED_COUNT",
                    "Gatekeeper staging exposed " + roots.size() + " roots");
            if (terminal) return;
            ArenaOwnershipRegistry.RootRecord gatekeeper = roots.get(0);
            if (gatekeeper.role() == ArenaOwnershipRegistry.RootRole.RESERVE_HOSTILE) {
                if (!stagedPhasesVerified.contains(phase)) {
                    if (!verifyExactStagedPhase(server, level, phase, roots,
                            ArenaOwnershipRegistry.RootRole.RESERVE_HOSTILE)) {
                        return;
                    }
                    stagedGatekeeperRootId = gatekeeper.rootId();
                    stagedPhasesVerified.add(phase);
                } else {
                    require(server, gatekeeper.rootId().equals(stagedGatekeeperRootId),
                            "GATEKEEPER_RESERVE_UUID",
                            "Gatekeeper reserve root changed before 5B promotion");
                    if (terminal) return;
                }
            } else if (gatekeeper.role() == ArenaOwnershipRegistry.RootRole.HOSTILE) {
                require(server, stagedPhasesVerified.contains(phase)
                                && stagedGatekeeperRootId != null,
                        "GATEKEEPER_RESERVE_MISSING",
                        "Gatekeeper became hostile without a staged 5A reserve observation");
                if (terminal) return;
                if (!verifyExactStagedPhase(server, level, phase, roots,
                        ArenaOwnershipRegistry.RootRole.HOSTILE)) {
                    return;
                }
                gatekeeperUuidContinuityVerified = gatekeeper.rootId().equals(stagedGatekeeperRootId);
                require(server, gatekeeperUuidContinuityVerified,
                        "GATEKEEPER_UUID_CONTINUITY",
                        "5B promotion replaced the staged 5A Gatekeeper root UUID");
                if (terminal) return;
            } else {
                fail(server, "GATEKEEPER_STAGED_ROLE",
                        "Gatekeeper staging used role " + gatekeeper.role());
                return;
            }
        }
    }

    private boolean verifyExactStagedPhase(MinecraftServer server, ServerLevel level,
                                           AshenSpanDefinition.PhaseId phase,
                                           List<ArenaOwnershipRegistry.RootRecord> roots,
                                           ArenaOwnershipRegistry.RootRole expectedRole) {
        AshenSpanDefinition.PhaseSpec spec = AshenSpanDefinition.phase(phase);
        require(server, roots.size() == spec.slots().size(), "STAGED_ROOT_COUNT",
                phase + " staged " + roots.size() + " roots for " + spec.slots().size()
                        + " authored slots");
        if (terminal) return false;
        List<ArenaOwnershipRegistry.RootRecord> ordered = roots.stream()
                .sorted(java.util.Comparator.comparingInt(ArenaOwnershipRegistry.RootRecord::slot))
                .toList();
        for (int index = 0; index < spec.slots().size(); index++) {
            AshenSpanDefinition.UnitSlot slot = spec.slots().get(index);
            ArenaOwnershipRegistry.RootRecord root = ordered.get(index);
            Entity entity = level.getEntity(root.rootId());
            require(server, root.role() == expectedRole && root.phase().equals(phase.name())
                            && root.slot() == index && root.replacementsUsed() == 0,
                    "STAGED_ROOT_METADATA", phase + " slot " + index
                            + " has staged metadata " + root);
            if (terminal) return false;
            require(server, entity != null && !entity.isRemoved() && ArenaHooks.isStaged(entity),
                    "STAGED_ROOT_ENTITY", phase + " slot " + index
                            + " has no live staged entity");
            if (terminal) return false;
            ResourceLocation actualType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            require(server, actualType.toString().equals(slot.unit().entityId()),
                    "STAGED_UNIT", phase + " slot " + index + " expected "
                            + slot.unit().entityId() + " but found " + actualType);
            if (terminal) return false;
            AshenSpanDefinition.BlockPoint socket = slot.socket();
            boolean exactSocket = Math.abs(entity.getX() - (socket.x() + 0.5D)) < 0.000001D
                    && Math.abs(entity.getY() - socket.y()) < 0.000001D
                    && Math.abs(entity.getZ() - (socket.z() + 0.5D)) < 0.000001D;
            require(server, exactSocket, "STAGED_SOCKET", phase + " slot " + index
                    + " drifted from " + socket + " to " + entity.position());
            if (terminal) return false;
            float expectedYaw = slot.facing() == AshenSpanDefinition.Facing.WEST ? 90.0F : -90.0F;
            require(server, Math.abs(Mth.wrapDegrees(entity.getYRot() - expectedYaw)) < 0.0001F,
                    "STAGED_FACING", phase + " slot " + index + " expected "
                            + slot.facing() + " yaw " + expectedYaw + " but found " + entity.getYRot());
            if (terminal) return false;
        }
        LOGGER.info("ASHEN_SPAN_QUALIFICATION_STAGED_PASS build={} phase={} roots={} role={}",
                build, phase, roots.size(), expectedRole);
        return true;
    }

    private boolean phaseReady(AshenSpanDefinition.PhaseId phase) {
        ServerLevel level = pilot.serverLevel();
        observeStagedRoots(level.getServer(), level);
        if (terminal) return false;
        if (!loadPhaseChunks(level, phase)) return false;
        if (currentHostilePhase(level) != phase) return false;
        for (ArenaOwnershipRegistry.RootRecord root : liveHostileRoots(level)) {
            Entity entity = level.getEntity(root.rootId());
            if (entity != null && ArenaHooks.isStaged(entity)) {
                if (stepTicks % 100 == 0) {
                    LOGGER.info("ASHEN_SPAN_QUALIFICATION_PHASE_WAIT phase={} root={} slot={} pos={} staged=true",
                            phase, root.rootId(), root.slot(), entity.position());
                }
                return false;
            }
        }
        return true;
    }

    private boolean loadPhaseChunks(ServerLevel level, AshenSpanDefinition.PhaseId phase) {
        AshenSpanDefinition.PhaseSpec spec = AshenSpanDefinition.phase(phase);
        for (AshenSpanDefinition.UnitSlot slot : spec.slots()) {
            if (!loadExistingChunk(level, slot.socket().x(), slot.socket().z())) return false;
        }
        if (!loadExistingChunk(level, spec.recoveryAnchor().x(), spec.recoveryAnchor().z())) return false;
        if (spec.playerFallback() != null) {
            if (!loadExistingChunk(level, spec.playerFallback().x(), spec.playerFallback().z())) return false;
        }
        return true;
    }

    private void moveForTrigger(AshenSpanDefinition.PhaseId phase) {
        Integer x = AshenSpanDefinition.phase(phase).trigger().xThreshold();
        if (x == null || activeMech.getX() >= x) return;
        int y = phase == AshenSpanDefinition.PhaseId.FREIGHT_CANYON
                ? AshenSpanDefinition.FREIGHT_GROUND_Y + 1 : AshenSpanDefinition.SPAN_DECK_Y + 1;
        activeMech.teleportTo(x + 0.5D, y, 0.5D);
        pilot.teleportTo(pilot.serverLevel(), x + 0.5D, y, 0.5D,
                java.util.Set.of(), -90.0F, 0.0F);
        if (pilot.getVehicle() != activeMech) pilot.startRiding(activeMech, true);
    }

    private void snapshotGates(ServerLevel level) {
        gateBaseline.clear();
        mutationBaseline.clear();
        for (AshenSpanDefinition.GateId gate : AshenSpanDefinition.GateId.values()) {
            List<BlockState> states = new ArrayList<>();
            forEach(AshenSpanDefinition.gate(gate).volume(), pos -> {
                BlockState state = level.getBlockState(pos);
                states.add(state);
                mutationBaseline.putIfAbsent(pos.immutable(), state);
            });
            gateBaseline.put(gate, List.copyOf(states));
        }
        for (AshenSpanDefinition.BlockVolume shutter : REVEAL_SHUTTERS) {
            forEach(shutter, pos -> mutationBaseline.putIfAbsent(pos.immutable(), level.getBlockState(pos)));
        }
    }

    private boolean gatesEqual(ServerLevel level) {
        return mutationBaseline.entrySet().stream()
                .allMatch(entry -> level.getBlockState(entry.getKey()).equals(entry.getValue()));
    }

    private boolean gateAtBaseline(ServerLevel level, AshenSpanDefinition.GateId gate) {
        List<BlockState> actual = new ArrayList<>();
        forEach(AshenSpanDefinition.gate(gate).volume(), pos -> actual.add(level.getBlockState(pos)));
        return actual.equals(gateBaseline.get(gate));
    }

    private boolean ledgerStatesRestored(MinecraftServer server, List<MissionGateBlock> ledger) {
        for (MissionGateBlock record : ledger) {
            ServerLevel level = server.getLevel(record.dimensionKey());
            BlockState expected = record.decodeState();
            if (level == null || expected == null
                    || !level.getBlockState(record.pos()).equals(expected)) return false;
        }
        return true;
    }

    private boolean gateIsAir(ServerLevel level, AshenSpanDefinition.GateId gate) {
        final boolean[] air = {true};
        forEach(AshenSpanDefinition.gate(gate).volume(),
                pos -> air[0] &= level.getBlockState(pos).is(Blocks.AIR));
        return air[0];
    }

    private static AshenSpanDefinition.BlockVolume volume(int x, int minY, int maxY,
                                                          int minZ, int maxZ) {
        return new AshenSpanDefinition.BlockVolume(x, x, minY, maxY, minZ, maxZ);
    }

    private static AshenSpanDefinition.BlockVolume volume(int minX, int maxX,
                                                          int minY, int maxY,
                                                          int minZ, int maxZ) {
        return new AshenSpanDefinition.BlockVolume(minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static void forEach(AshenSpanDefinition.BlockVolume volume,
                                java.util.function.Consumer<BlockPos> consumer) {
        for (int x = volume.minX(); x <= volume.maxX(); x++) {
            for (int y = volume.minY(); y <= volume.maxY(); y++) {
                for (int z = volume.minZ(); z <= volume.maxZ(); z++) consumer.accept(new BlockPos(x, y, z));
            }
        }
    }

    private int missionResidue(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getTags().contains("mecharena")
                        || entity.getTags().contains("mecharena_pve")
                        || entity.getTags().stream().anyMatch(tag -> tag.startsWith(TAG_MATCH_PREFIX))
                        || entity.getTags().contains(ArenaHooks.TAG_OWNED)
                        || entity.getTags().contains(TAG_SOLO)
                        || entity.getTags().contains(TAG_SOLO_PROJECTILE)) count++;
            }
        }
        return count;
    }

    private boolean samePlayerRestore() {
        return pilot != null && pilot.serverLevel().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
                && Math.abs(pilot.getX() - originalX) < 0.000001D
                && Math.abs(pilot.getY() - originalY) < 0.000001D
                && Math.abs(pilot.getZ() - originalZ) < 0.000001D
                && Math.abs(pilot.getYRot() - originalYaw) < 0.000001F;
    }

    private Map<String, String> pendingRestoreSnapshot(MinecraftServer server) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        ArenaData.get(server).copyPendingRestores().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey().toString(), entry.getValue().save().toString()));
        return snapshot;
    }

    private int command(MinecraftServer server, String command) {
        return server.getCommands().performPrefixedCommand(pilot.createCommandSourceStack(), command);
    }

    private void removePilot(MinecraftServer server) {
        if (pilot == null) return;
        pilot.stopRiding();
        AshenSpanPlayerListAccessor playerList = (AshenSpanPlayerListAccessor) server.getPlayerList();
        playerList.ashenSpan$getPlayers().remove(pilot);
        playerList.ashenSpan$getPlayersByUuid().remove(PILOT_ID);
        if (!pilot.isRemoved()) pilot.serverLevel().removePlayerImmediately(pilot, Entity.RemovalReason.DISCARDED);
    }

    private void resetBuildState() {
        buildStartTick = -1;
        phaseIndex = 0;
        phaseRetired = false;
        firstStartMech = null;
        activeMech = null;
        normalRoots = 0;
        serviceVerified = false;
        gatekeeperVerified = false;
        bossVerified = false;
        bossHitboxVerified = false;
        retryVerified = false;
        initialTemplateVerified = false;
        retryTemplateVerified = false;
        retryMutationRestoreVerified = false;
        retryExactZeroVerified = false;
        serviceResourcesDegraded = false;
        gatekeeperUuidContinuityVerified = false;
        ownershipRegistryEmptyVerified = false;
        ownershipSecondaryIndexesEmptyVerified = false;
        stagedGatekeeperRootId = null;
        activeBuildOwnership = null;
        degradedFuelCount = Integer.MAX_VALUE;
        observedPhases.clear();
        stagedPhasesVerified.clear();
    }

    private void transition(Step next) {
        step = next;
        stepTicks = 0;
    }

    private void require(MinecraftServer server, boolean condition, String code, String message) {
        if (!condition) fail(server, code, message);
    }

    private Path outputPath() {
        String value = System.getProperty("ashenSpan.qualification.output", "");
        if (value.isBlank()) throw new IllegalStateException("ashenSpan.qualification.output is required");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private void writeResult(Map<String, Object> result) {
        Path output = outputPath();
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, JSON.toJson(result) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            LOGGER.error("ASHEN_SPAN_QUALIFICATION_EVIDENCE_WRITE_FAILED output={}", output, exception);
        }
    }
}
