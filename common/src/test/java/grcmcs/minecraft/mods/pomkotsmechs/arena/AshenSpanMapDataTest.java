package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AshenSpanMapDataTest {
    @Test
    void physicalPreflightCoversEveryAuthoredSocketRecoveryGateAndShutter() {
        var markers = AshenSpanMapContract.markers();
        assertEquals(47, markers.size());
        assertEquals(17, markers.stream().filter(marker -> marker.label().startsWith("socket-")).count());
        assertEquals(7, markers.stream().filter(marker -> marker.label().startsWith("recovery-")).count());
        assertEquals(6, markers.stream().filter(marker -> marker.label().startsWith("gate-")).count());
        assertEquals(10, markers.stream().filter(marker -> marker.label().startsWith("reveal-shutter-")).count());
        assertTrue(markers.stream().anyMatch(marker -> marker.label().equals("service-gantry")));
        assertTrue(markers.stream().anyMatch(marker -> marker.label().equals("player-fallback-POWER_DECK")));
    }

    @Test
    void persistedPaletteDecoderHandlesPackedNegativeCoordinateMarker() {
        net.minecraft.nbt.CompoundTag chunk = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.CompoundTag section = new net.minecraft.nbt.CompoundTag();
        section.putByte("Y", (byte) 5);
        net.minecraft.nbt.CompoundTag states = new net.minecraft.nbt.CompoundTag();
        net.minecraft.nbt.ListTag palette = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.CompoundTag air = new net.minecraft.nbt.CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        net.minecraft.nbt.CompoundTag marker = new net.minecraft.nbt.CompoundTag();
        marker.putString("Name", "minecraft:cyan_concrete");
        palette.add(marker);
        states.put("palette", palette);

        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(-150, 82, -14);
        int localIndex = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
        int valuesPerLong = 16; // two-entry palettes still use Minecraft's four-bit minimum
        long[] packed = new long[(4096 + valuesPerLong - 1) / valuesPerLong];
        packed[localIndex / valuesPerLong] |= 1L << ((localIndex % valuesPerLong) * 4);
        states.putLongArray("data", packed);
        section.put("block_states", states);
        net.minecraft.nbt.ListTag sections = new net.minecraft.nbt.ListTag();
        sections.add(section);
        chunk.put("sections", sections);

        net.minecraft.nbt.CompoundTag decoded =
                AshenSpanMapContract.readStoredBlockState(chunk, pos);
        assertNotNull(decoded);
        assertEquals("minecraft:cyan_concrete", decoded.getString("Name"));
    }

    @Test
    void storedChunkEnvelopeChecksEverySafetyChunkWithoutExpandingBounds() {
        java.util.Set<String> visited = new java.util.HashSet<>();
        AshenSpanMapContract.Result result = AshenSpanMapContract.validateStoredChunkEnvelope(
                (x, z) -> visited.add(x + "," + z));

        assertTrue(result.valid());
        assertEquals(680, visited.size());
        assertTrue(visited.contains("-17,-10"));
        assertTrue(visited.contains("16,9"));
        assertFalse(visited.contains("17,9"));
        assertFalse(visited.contains("16,10"));
    }

    @Test
    void storedChunkEnvelopeFailsAtFirstMissingFullChunk() {
        AshenSpanMapContract.Result result = AshenSpanMapContract.validateStoredChunkEnvelope(
                (x, z) -> x != -3 || z != 4);

        assertFalse(result.valid());
        assertTrue(result.error().contains("-3,4"));
        assertTrue(result.error().contains("missing or not fully generated"));
    }

    @Test
    void persistedFullChunkCheckUsesExactStatusAndCoordinates() {
        net.minecraft.nbt.CompoundTag stored = new net.minecraft.nbt.CompoundTag();
        stored.putInt("xPos", 13);
        stored.putInt("zPos", -10);
        stored.putString("Status", "minecraft:full");

        assertTrue(AshenSpanMapContract.isStoredFullChunk(
                stored, new net.minecraft.world.level.ChunkPos(13, -10)));
        assertFalse(AshenSpanMapContract.isStoredFullChunk(
                stored, new net.minecraft.world.level.ChunkPos(12, -10)));

        stored.putString("Status", "minecraft:features");
        assertFalse(AshenSpanMapContract.isStoredFullChunk(
                stored, new net.minecraft.world.level.ChunkPos(13, -10)));
        assertFalse(AshenSpanMapContract.isStoredFullChunk(
                null, new net.minecraft.world.level.ChunkPos(13, -10)));
    }

    @Test
    void exactMetadataRoundTripsAndValidates() {
        AshenSpanMapData expected = AshenSpanMapData.expectedForTests();
        AshenSpanMapData loaded = AshenSpanMapData.load(expected.save(new net.minecraft.nbt.CompoundTag()));
        assertTrue(AshenSpanMapContract.validateMetadataOnly(loaded).valid());
        assertEquals(64, AshenSpanMapData.expectedSafetyChunkDigest().length());
        assertEquals(AshenSpanMapData.EXPECTED_SAFETY_CHUNKS,
                (AshenSpanMapData.SAFETY_MAX_CHUNK_X - AshenSpanMapData.SAFETY_MIN_CHUNK_X + 1)
                        * (AshenSpanMapData.SAFETY_MAX_CHUNK_Z - AshenSpanMapData.SAFETY_MIN_CHUNK_Z + 1));
    }

    @Test
    void joinCardIdentityProbeFailsClosedWithoutScanningChunkStorage() {
        AshenSpanMapData expected = AshenSpanMapData.expectedForTests();
        assertTrue(AshenSpanMapContract.hasExpectedWorldIdentity(
                expected, AshenSpanMapData.WORLD_SEED));
        assertFalse(AshenSpanMapContract.hasExpectedWorldIdentity(
                null, AshenSpanMapData.WORLD_SEED));
        assertFalse(AshenSpanMapContract.hasExpectedWorldIdentity(
                expected, AshenSpanMapData.WORLD_SEED + 1));

        var tag = expected.save(new net.minecraft.nbt.CompoundTag());
        tag.putInt("mapVersion", AshenSpanMapData.MAP_VERSION + 1);
        assertFalse(AshenSpanMapContract.hasExpectedWorldIdentity(
                AshenSpanMapData.load(tag), AshenSpanMapData.WORLD_SEED));
    }

    @Test
    void blankOrWrongWorldDataFailsClosed() {
        AshenSpanMapContract.Result blank = AshenSpanMapContract.validateMetadataOnly(new AshenSpanMapData());
        assertFalse(blank.valid());
        assertTrue(blank.error().contains("map ID"));

        AshenSpanMapData expected = AshenSpanMapData.expectedForTests();
        var tag = expected.save(new net.minecraft.nbt.CompoundTag());
        tag.putInt("safetyChunkCount", 679);
        AshenSpanMapContract.Result cropped = AshenSpanMapContract.validateMetadataOnly(AshenSpanMapData.load(tag));
        assertFalse(cropped.valid());
        assertTrue(cropped.error().contains("680"));
    }

    @Test
    void rejectsAnyBuildHashThatIsNotTheExactLockedInput() {
        AshenSpanMapData data = AshenSpanMapData.expectedForTests();
        var tag = data.save(new net.minecraft.nbt.CompoundTag());
        tag.putString("profileSha256", "a".repeat(64));
        assertTrue(AshenSpanMapData.load(tag).validate().contains("profile SHA-256"));
        tag = data.save(new net.minecraft.nbt.CompoundTag());
        tag.putString("assetSha256", "b".repeat(64));
        assertTrue(AshenSpanMapData.load(tag).validate().contains("asset SHA-256"));
        tag = data.save(new net.minecraft.nbt.CompoundTag());
        tag.putString("contractSha256", "c".repeat(64));
        assertTrue(AshenSpanMapData.load(tag).validate().contains("contract SHA-256"));
        assertFalse(AshenSpanMapData.isSha256("g".repeat(64)));
    }

    @Test
    void physicalMarkerFingerprintIsStableAndWithinPlayableBounds() {
        assertEquals(47, AshenSpanMapContract.markers().size());
        assertEquals(-168, AshenSpanMapContract.markers().get(0).pos().getX());
        assertTrue(AshenSpanMapContract.markers().stream().allMatch(marker ->
                AshenSpanDefinition.PLAYABLE_BOUNDS.contains(
                        marker.pos().getX(), marker.pos().getZ())));
    }

    @Test
    void loadedAssetResourceMustBeTheExactCanonicalContract() throws Exception {
        byte[] bytes;
        try (var jar = new java.util.zip.ZipFile(
                java.nio.file.Path.of("..", "sector01-src", "dist",
                        "mecharena_sector01-1.0.0-mp25.jar").toFile())) {
            bytes = jar.getInputStream(jar.getEntry(
                    "data/mecharena_sector01/ashen_span/cold_ruin_sector_01.json"))
                    .readAllBytes();
        }
        assertTrue(AshenSpanMapContract.validateAssetContractBytes(bytes).valid());
        bytes[bytes.length / 2] ^= 1;
        assertFalse(AshenSpanMapContract.validateAssetContractBytes(bytes).valid());
    }

    @Test
    void runtimeDistanceContractIsExactRatherThanMerelyCapped() {
        assertTrue(AshenSpanMapContract.validateDistanceContract(6, 6).valid());
        assertFalse(AshenSpanMapContract.validateDistanceContract(5, 6).valid());
        assertFalse(AshenSpanMapContract.validateDistanceContract(6, 5).valid());
        assertFalse(AshenSpanMapContract.validateDistanceContract(7, 6).valid());
    }

    @Test
    void authoredWorldSuppressesOnlyVanillaInitialSpawnExpansion() {
        assertFalse(AshenSpanSpawnPreparationPolicy.addVanillaStartTicket(true));
        assertEquals(0, AshenSpanSpawnPreparationPolicy.generatedTarget(true, 441));
        assertTrue(AshenSpanSpawnPreparationPolicy.addVanillaStartTicket(false));
        assertEquals(441, AshenSpanSpawnPreparationPolicy.generatedTarget(false, 441));
    }
}
