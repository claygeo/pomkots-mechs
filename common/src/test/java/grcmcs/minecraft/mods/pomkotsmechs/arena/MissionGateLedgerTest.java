package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MissionGateLedgerTest {
    @Test
    void enumeratesEveryGateBlockExactlyOnce() {
        var volume = new AshenSpanDefinition.BlockVolume(24, 24, 72, 82, -13, 14);
        var positions = MissionGateLedger.positions(volume);
        assertEquals(11 * 28, positions.size());
        assertEquals(positions.size(), new HashSet<>(positions).size());
        assertEquals(new BlockPos(24, 72, -13), positions.get(0));
        assertEquals(new BlockPos(24, 82, 14), positions.get(positions.size() - 1));
    }

    @Test
    void exactAuthoredGateVolumesHaveLockedSizes() {
        assertEquals(8 * 17,
                MissionGateLedger.positions(AshenSpanDefinition.gate(AshenSpanDefinition.GateId.G1).volume()).size());
        assertEquals(15 * 29,
                MissionGateLedger.positions(AshenSpanDefinition.gate(AshenSpanDefinition.GateId.G2).volume()).size());
        assertEquals(11 * 28,
                MissionGateLedger.positions(AshenSpanDefinition.gate(AshenSpanDefinition.GateId.G3).volume()).size());
        assertEquals(13 * 17,
                MissionGateLedger.positions(AshenSpanDefinition.gate(AshenSpanDefinition.GateId.INTERNAL_SHUTTER).volume()).size());
    }
}
