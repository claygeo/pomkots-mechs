package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaHooksTest {
    @Test
    void unloadedEntitiesCannotCreateFalseExactZero() {
        assertFalse(ArenaHooks.canReconcileMissing(false, false));
        assertFalse(ArenaHooks.canReconcileMissing(true, false));
        assertTrue(ArenaHooks.canReconcileMissing(true, true));
    }

    @Test
    void authoredSweepRequiresDimensionRegistryAndCurrentTags() {
        assertTrue(ArenaHooks.acceptsOwnedSweepCandidate(true, true, true));
        assertFalse(ArenaHooks.acceptsOwnedSweepCandidate(false, true, true));
        assertFalse(ArenaHooks.acceptsOwnedSweepCandidate(true, false, true));
        assertFalse(ArenaHooks.acceptsOwnedSweepCandidate(true, true, false));
    }

    @Test
    void preAddAllowsUnrelatedEntitiesButRejectsStaleOwnership() {
        assertEquals(ArenaHooks.AddDecision.NOT_APPLICABLE, ArenaHooks.decideOwnedPreAdd(
                true, false, false, false, false, false));
        assertEquals(ArenaHooks.AddDecision.ACCEPT, ArenaHooks.decideOwnedPreAdd(
                true, true, true, true, false, false));

        assertEquals(ArenaHooks.AddDecision.REJECT, ArenaHooks.decideOwnedPreAdd(
                true, false, false, true, false, false));
        assertEquals(ArenaHooks.AddDecision.REJECT, ArenaHooks.decideOwnedPreAdd(
                true, false, false, false, false, true));
        assertEquals(ArenaHooks.AddDecision.REJECT, ArenaHooks.decideOwnedPreAdd(
                true, true, false, true, false, false));
        assertEquals(ArenaHooks.AddDecision.REJECT, ArenaHooks.decideOwnedPreAdd(
                true, true, true, true, true, false));
    }
}
