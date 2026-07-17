package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Crash-safe save-before-mutate operations for Ashen Span gates and shutters. */
final class MissionGateLedger {
    private MissionGateLedger() {
    }

    /**
     * Opens a complete authored volume. Every original state is durably flushed before
     * the first block changes; an unavailable chunk causes a no-mutation failure.
     */
    static boolean open(MinecraftServer server, ServerLevel level,
                        AshenSpanDefinition.BlockVolume volume) {
        List<BlockPos> positions = positions(volume);
        for (BlockPos pos : positions) {
            if (!level.hasChunkAt(pos)) {
                return false;
            }
        }
        ArenaData data = ArenaData.get(server);
        ResourceLocation dimension = level.dimension().location();
        for (BlockPos pos : positions) {
            if (!data.hasMissionGateBlock(dimension, pos)) {
                data.addMissionGateBlock(new MissionGateBlock(
                        dimension, pos, level.getBlockState(pos)));
            }
        }
        // Durability barrier: original states reach disk before any mutation.
        server.overworld().getDataStorage().save();

        boolean success = true;
        for (BlockPos pos : positions) {
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
                success = false;
            }
        }
        if (!success) {
            restoreAll(server);
        }
        return success;
    }

    /**
     * Restores every decodable entry and retains any unavailable/invalid entry for the
     * next boot. No entry is cleared until its exact state is present in-world.
     */
    static boolean restoreAll(MinecraftServer server) {
        ArenaData data = ArenaData.get(server);
        List<MissionGateBlock> existing = data.getMissionGateBlocks();
        if (existing.isEmpty()) {
            return true;
        }
        List<MissionGateBlock> retained = new ArrayList<>();
        for (MissionGateBlock entry : existing) {
            ServerLevel level = server.getLevel(entry.dimensionKey());
            BlockState original = entry.decodeState();
            if (level == null || original == null) {
                retained.add(entry);
                continue;
            }
            if (!level.setBlock(entry.pos(), original, 3)
                    || !level.getBlockState(entry.pos()).equals(original)) {
                retained.add(entry);
            }
        }
        data.setMissionGateBlocks(retained);
        server.overworld().getDataStorage().save();
        return retained.isEmpty();
    }

    static List<BlockPos> positions(AshenSpanDefinition.BlockVolume volume) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = volume.minX(); x <= volume.maxX(); x++) {
            for (int y = volume.minY(); y <= volume.maxY(); y++) {
                for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                    result.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(result);
    }
}
