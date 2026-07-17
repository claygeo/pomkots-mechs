package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MissionGateBlockTest {
    @Test
    void roundTripsCompleteBlockStateIncludingProperties() {
        MissionGateBlock entry = MissionGateBlock.encodedForTests(
                new ResourceLocation("minecraft", "overworld"), new BlockPos(72, 80, 3),
                new ResourceLocation("minecraft", "orange_glazed_terracotta"),
                Map.of("facing", "west"));

        MissionGateBlock loaded = MissionGateBlock.load(entry.save());
        assertNotNull(loaded);
        assertEquals(entry.dimension(), loaded.dimension());
        assertEquals(entry.pos(), loaded.pos());
        assertEquals(entry.block(), loaded.block());
        assertEquals("west", loaded.properties().get("facing"));
        assertEquals(Map.of("facing", "west"), loaded.properties());
    }

    @Test
    void preservesUnknownBlockForFailClosedRuntimeRestore() {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("dim", "minecraft:overworld");
        tag.putString("block", "missing:not_a_block");
        MissionGateBlock loaded = MissionGateBlock.load(tag);
        assertNotNull(loaded);
        assertEquals(new ResourceLocation("missing", "not_a_block"), loaded.block());
    }
}
