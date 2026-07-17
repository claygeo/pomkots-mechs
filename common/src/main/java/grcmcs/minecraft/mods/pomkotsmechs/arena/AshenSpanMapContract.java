package grcmcs.minecraft.mods.pomkotsmechs.arena;

import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.mixin.AshenSpanChunkMapAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, fail-closed validation boundary for the authored Sector 01 world. */
public final class AshenSpanMapContract {
    public static final String EXPECTED_MARKER_FINGERPRINT =
            "ashen-span-v1:lodestone:blackstone:orange-east:cyan-west";
    public static final ResourceLocation ASSET_CONTRACT_RESOURCE = new ResourceLocation(
            AshenSpanDefinition.ASSET_MOD_ID, "ashen_span/cold_ruin_sector_01.json");
    public static final String EXPECTED_ASSET_CONTRACT_RESOURCE_SHA256 =
            "3cfb7f1daa8e3c1c166aa7821e4f9226602d192d9fd3c50f77f3b1546a18f6a4";

    public record Marker(BlockPos pos, ResourceLocation block,
                         Map<String, String> properties, String label) {
        public Marker {
            properties = Map.copyOf(properties);
        }

        boolean matches(BlockState state) {
            if (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(block)) {
                return false;
            }
            for (Map.Entry<String, String> expected : properties.entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(expected.getKey());
                if (property == null || !propertyName(property, state).equals(expected.getValue())) {
                    return false;
                }
            }
            return true;
        }

        boolean matchesStored(CompoundTag state) {
            if (state == null || !block.toString().equals(state.getString("Name"))) {
                return false;
            }
            CompoundTag storedProperties = state.getCompound("Properties");
            for (Map.Entry<String, String> expected : properties.entrySet()) {
                if (!expected.getValue().equals(storedProperties.getString(expected.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static String propertyName(Property property, BlockState state) {
            return property.getName(state.getValue(property));
        }
    }

    public record Result(boolean valid, String error) {
        public static Result ok() {
            return new Result(true, "");
        }

        public static Result fail(String error) {
            return new Result(false, error);
        }
    }

    private static final List<Marker> MARKERS = createMarkers();

    private AshenSpanMapContract() {
    }

    public static List<Marker> markers() {
        return MARKERS;
    }

    private static List<Marker> createMarkers() {
        List<Marker> markers = new ArrayList<>();
        markers.add(new Marker(new BlockPos(-168, 79, -23), id("lodestone"),
                Map.of(), "identity-map-id"));
        markers.add(new Marker(new BlockPos(-167, 79, -23),
                id("chiseled_polished_blackstone"), Map.of(), "identity-version"));
        markers.add(new Marker(new BlockPos(-166, 79, -23),
                id("orange_glazed_terracotta"), Map.of("facing", "east"), "identity-origin"));
        markers.add(new Marker(new BlockPos(-165, 79, -23),
                id("cyan_glazed_terracotta"), Map.of("facing", "west"), "identity-orientation"));
        markers.add(surfaceMarker(AshenSpanDefinition.PLAYER_PAD,
                "cyan_concrete", "player-pad"));

        for (AshenSpanDefinition.PhaseSpec phase : AshenSpanDefinition.phases()) {
            for (AshenSpanDefinition.UnitSlot slot : phase.slots()) {
                markers.add(surfaceMarker(slot.socket(), "cyan_concrete",
                        "socket-" + slot.slotId()));
            }
            markers.add(surfaceMarker(phase.recoveryAnchor(),
                    "chiseled_polished_blackstone", "recovery-" + phase.id().name()));
            if (phase.playerFallback() != null) {
                markers.add(surfaceMarker(phase.playerFallback(),
                        "chiseled_polished_blackstone", "player-fallback-" + phase.id().name()));
            }
        }

        for (AshenSpanDefinition.GateSpec gate : AshenSpanDefinition.gates().values()) {
            AshenSpanDefinition.BlockVolume volume = gate.volume();
            markers.add(new Marker(new BlockPos(volume.minX(), volume.minY(),
                    midpoint(volume.minZ(), volume.maxZ())), id("orange_concrete"), Map.of(),
                    "gate-" + gate.id().name()));
        }
        for (AshenSpanDefinition.PhaseId phase : AshenSpanDefinition.PhaseId.values()) {
            int ordinal = 0;
            for (AshenSpanDefinition.BlockVolume volume : AshenSpanDirector.revealShutters(phase)) {
                markers.add(new Marker(new BlockPos(volume.minX(), volume.minY(),
                        midpoint(volume.minZ(), volume.maxZ())),
                        id("polished_blackstone_bricks"), Map.of(),
                        "reveal-shutter-" + phase.name() + "-" + ordinal++));
            }
        }
        markers.add(new Marker(new BlockPos(AshenSpanDefinition.SERVICE_GANTRY.x(),
                AshenSpanDefinition.SERVICE_GANTRY.y(), AshenSpanDefinition.SERVICE_GANTRY.z()),
                id("cyan_concrete"), Map.of(), "service-gantry"));
        return List.copyOf(markers);
    }

    private static Marker surfaceMarker(AshenSpanDefinition.BlockPoint point,
                                        String block, String label) {
        return new Marker(new BlockPos(point.x(), point.y() - 1, point.z()),
                id(block), Map.of(), label);
    }

    private static int midpoint(int min, int max) {
        return min + (max - min) / 2;
    }

    /**
     * Cheap join-card identity check. It reads only the small SavedData identity
     * record and the already-loaded world seed; it never scans chunk storage,
     * reads physical markers, or opens the asset resource. Mission start must
     * still call {@link #validate(MinecraftServer, ServerLevel)}.
     */
    static boolean hasExpectedWorldIdentity(MinecraftServer server, ServerLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return false;
        }
        AshenSpanMapData data = level.getDataStorage().get(
                AshenSpanMapData::load, AshenSpanMapData.DATA_NAME);
        long worldSeed = server.getWorldData().worldGenOptions().seed();
        return hasExpectedWorldIdentity(data, worldSeed);
    }

    static boolean hasExpectedWorldIdentity(AshenSpanMapData data, long worldSeed) {
        return data != null
                && worldSeed == AshenSpanMapData.WORLD_SEED
                && data.validate().isEmpty();
    }

    /**
     * Validation deliberately performs no journal, teleport, block, entity, gamerule,
     * or config writes. Missing marker chunks fail rather than being force-loaded.
     */
    public static Result validate(MinecraftServer server, ServerLevel level) {
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return Result.fail("Operation Ashen Span requires the authored Overworld template.");
        }
        AshenSpanMapData data = level.getDataStorage().get(
                AshenSpanMapData::load, AshenSpanMapData.DATA_NAME);
        if (data == null) {
            return Result.fail("Sector 01 metadata is missing; use the matched bounded world template.");
        }
        String metadataError = data.validate();
        if (!metadataError.isEmpty()) {
            return Result.fail("Sector 01 metadata rejected: " + metadataError + ".");
        }
        Result assetContract = validateLoadedAssetContract(server);
        if (!assetContract.valid()) {
            return assetContract;
        }
        if (server.getWorldData().worldGenOptions().seed() != AshenSpanMapData.WORLD_SEED) {
            return Result.fail("Sector 01 level seed does not match the fixed mission seed.");
        }
        int viewDistance = server.getPlayerList().getViewDistance();
        int simulationDistance = server.getPlayerList().getSimulationDistance();
        Result distanceContract = validateDistanceContract(viewDistance, simulationDistance);
        if (!distanceContract.valid()) {
            return distanceContract;
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return Result.fail("Sector 01 requires mobGriefing=false.");
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            return Result.fail("Sector 01 requires doMobSpawning=false.");
        }
        if (level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) {
            return Result.fail("Sector 01 requires doMobLoot=false.");
        }
        if (PomkotsMechs.CONFIG.enableEntityBlockDestruction
                || PomkotsMechs.CONFIG.enablePlayerVehicleBlockDestruction) {
            return Result.fail("Sector 01 requires both Pomkots block-destruction switches to be false.");
        }
        Result storedChunks = validateStoredChunkEnvelope(level);
        if (!storedChunks.valid()) {
            return storedChunks;
        }
        Result physicalMarkers = validatePhysicalMarkers(level);
        if (!physicalMarkers.valid()) {
            return physicalMarkers;
        }
        return Result.ok();
    }

    /**
     * Checks the frozen safety envelope from persisted NBT. This deliberately does
     * not use {@code ChunkMap.isExistingChunkFull}: its mutable chunk-type cache can
     * retain a replaceable/proto result while the dedicated server prepares spawn.
     * Reading NBT directly creates no ticket and cannot generate a missing chunk.
     */
    private static Result validateStoredChunkEnvelope(ServerLevel level) {
        Object chunkMap = level.getChunkSource().chunkMap;
        if (!(chunkMap instanceof AshenSpanChunkMapAccessor accessor)) {
            return Result.fail("Sector 01 persisted-chunk validator is unavailable; verify the matched Pomkots JAR.");
        }
        try {
            return validateStoredChunkEnvelope((x, z) -> {
                ChunkPos pos = new ChunkPos(x, z);
                CompoundTag stored = accessor.pomkotsmechs$readChunk(pos)
                        .join().orElse(null);
                return isStoredFullChunk(stored, pos);
            });
        } catch (RuntimeException exception) {
            return Result.fail("Sector 01 persisted chunks could not be read without generation: "
                    + exception.getClass().getSimpleName() + ".");
        }
    }

    static boolean isStoredFullChunk(CompoundTag stored, ChunkPos expected) {
        if (stored == null
                || !stored.contains("xPos", Tag.TAG_INT)
                || !stored.contains("zPos", Tag.TAG_INT)
                || stored.getInt("xPos") != expected.x
                || stored.getInt("zPos") != expected.z) {
            return false;
        }
        String status = stored.getString("Status");
        return "full".equals(status) || "minecraft:full".equals(status);
    }

    static Result validateStoredChunkEnvelope(ChunkStatusProbe probe) {
        for (int x = AshenSpanDefinition.SAFETY_CHUNKS.minX();
             x <= AshenSpanDefinition.SAFETY_CHUNKS.maxX(); x++) {
            for (int z = AshenSpanDefinition.SAFETY_CHUNKS.minZ();
                 z <= AshenSpanDefinition.SAFETY_CHUNKS.maxZ(); z++) {
                if (!probe.isExistingFullChunk(x, z)) {
                    return Result.fail("Sector 01 required chunk " + x + "," + z
                            + " is missing or not fully generated; restore the matched bounded world.");
                }
            }
        }
        return Result.ok();
    }

    @FunctionalInterface
    interface ChunkStatusProbe {
        boolean isExistingFullChunk(int x, int z);
    }

    /** Reads remote marker chunks directly from storage without tickets or generation. */
    private static Result validatePhysicalMarkers(ServerLevel level) {
        Object chunkMap = level.getChunkSource().chunkMap;
        if (!(chunkMap instanceof AshenSpanChunkMapAccessor accessor)) {
            return Result.fail("Sector 01 physical-marker validator is unavailable; verify the matched Pomkots JAR.");
        }
        Map<Long, CompoundTag> storedChunks = new LinkedHashMap<>();
        try {
            for (Marker marker : MARKERS) {
                ChunkPos chunkPos = new ChunkPos(marker.pos());
                var loaded = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
                boolean matches;
                if (loaded != null) {
                    matches = marker.matches(loaded.getBlockState(marker.pos()));
                } else {
                    CompoundTag chunk = storedChunks.computeIfAbsent(chunkPos.toLong(), ignored ->
                            accessor.pomkotsmechs$readChunk(chunkPos).join().orElse(null));
                    matches = marker.matchesStored(readStoredBlockState(chunk, marker.pos()));
                }
                if (!matches) {
                    return Result.fail("Sector 01 physical marker mismatch at " + marker.pos()
                            + " (" + marker.label() + ").");
                }
            }
        } catch (RuntimeException exception) {
            return Result.fail("Sector 01 physical markers could not be read without generation: "
                    + exception.getClass().getSimpleName() + ".");
        }
        return Result.ok();
    }

    /** Decodes one 1.20.1 paletted block state directly from persisted chunk NBT. */
    static CompoundTag readStoredBlockState(CompoundTag chunk, BlockPos pos) {
        if (chunk == null) {
            return null;
        }
        int sectionY = Math.floorDiv(pos.getY(), 16);
        ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            CompoundTag section = sections.getCompound(sectionIndex);
            if (section.getByte("Y") != (byte) sectionY || !section.contains("block_states", Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag states = section.getCompound("block_states");
            ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
            if (palette.isEmpty()) {
                return null;
            }
            if (palette.size() == 1) {
                return palette.getCompound(0);
            }
            long[] packed = states.getLongArray("data");
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            int valuesPerLong = 64 / bits;
            int localIndex = ((pos.getY() & 15) << 8)
                    | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
            int word = localIndex / valuesPerLong;
            int offset = (localIndex % valuesPerLong) * bits;
            if (word >= packed.length) {
                return null;
            }
            int paletteIndex = (int) ((packed[word] >>> offset) & ((1L << bits) - 1L));
            return paletteIndex < palette.size() ? palette.getCompound(paletteIndex) : null;
        }
        return null;
    }

    static Result validateMetadataOnly(AshenSpanMapData data) {
        String error = data.validate();
        return error.isEmpty() ? Result.ok() : Result.fail(error);
    }

    static Result validateDistanceContract(int viewDistance, int simulationDistance) {
        return viewDistance == AshenSpanDefinition.REQUIRED_VIEW_DISTANCE
                && simulationDistance == AshenSpanDefinition.REQUIRED_SIMULATION_DISTANCE
                ? Result.ok()
                : Result.fail("Sector 01 requires exact view-distance/simulation-distance 6/6; effective values are "
                        + viewDistance + "/" + simulationDistance + ".");
    }

    static Result validateAssetContractBytes(byte[] bytes) {
        if (bytes == null || !EXPECTED_ASSET_CONTRACT_RESOURCE_SHA256.equalsIgnoreCase(
                AshenSpanMapData.sha256(bytes))) {
            return Result.fail("Sector 01 loaded asset contract does not match the locked mp.25 resource.");
        }
        return Result.ok();
    }

    private static Result validateLoadedAssetContract(MinecraftServer server) {
        var resource = server.getResourceManager().getResource(ASSET_CONTRACT_RESOURCE);
        if (resource.isEmpty()) {
            return Result.fail("Sector 01 asset resource is not loaded; install the matched mecharena_sector01 JAR.");
        }
        try (var stream = resource.get().open()) {
            return validateAssetContractBytes(stream.readAllBytes());
        } catch (IOException exception) {
            return Result.fail("Sector 01 asset resource could not be read: " + exception.getMessage());
        }
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("minecraft", path);
    }
}
