package grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pmvc01EntityTest {
    @Test
    void arenaOwnedPmvcEmitsNoLoadoutDropsOrDeathExplosion() {
        assertFalse(Pmvc01DeathPolicy.allowsLoadoutDrop(true, true));
        assertFalse(Pmvc01DeathPolicy.allowsDeathExplosion(true));
    }

    @Test
    void ordinaryKilledPmvcRetainsLegacyCombatArtifacts() {
        assertTrue(Pmvc01DeathPolicy.allowsLoadoutDrop(true, false));
        assertTrue(Pmvc01DeathPolicy.allowsDeathExplosion(false));
        assertFalse(Pmvc01DeathPolicy.allowsLoadoutDrop(false, false));
    }
}
