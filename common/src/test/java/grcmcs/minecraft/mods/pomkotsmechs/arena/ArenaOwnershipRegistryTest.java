package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaOwnershipRegistryTest {
    @Test
    void requiresKnownParentAndRetainsExactZeroTombstone() {
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        UUID root = uuid(1);
        UUID projectile = uuid(2);
        UUID chained = uuid(3);

        assertFalse(registry.registerDescendant(projectile, root,
                ArenaOwnershipRegistry.DescendantKind.PROJECTILE));
        registry.registerRoot(root, uuid(100), ArenaOwnershipRegistry.RootRole.HOSTILE,
                "P6", 0);
        assertTrue(registry.registerDescendant(projectile, root,
                ArenaOwnershipRegistry.DescendantKind.PROJECTILE));
        assertTrue(registry.registerDescendant(chained, projectile,
                ArenaOwnershipRegistry.DescendantKind.PROJECTILE));

        registry.markRootBodyGone(root);
        assertEquals(0, registry.liveHostileRootCount());
        assertEquals(2, registry.hostileDescendantCount());
        assertFalse(registry.hostileExactZero());

        registry.remove(projectile);
        registry.remove(chained);
        assertTrue(registry.hostileExactZero());
        assertTrue(registry.owns(root), "tombstone keeps lineage until phase retirement");
        registry.remove(root);
        assertFalse(registry.owns(root));
    }

    @Test
    void enforcesIndependentProjectileAndEffectCaps() {
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        UUID root = uuid(1);
        registry.registerRoot(root, uuid(100), ArenaOwnershipRegistry.RootRole.HOSTILE,
                "P4", 0);

        for (int i = 0; i < ArenaOwnershipRegistry.PROJECTILE_CAP; i++) {
            assertTrue(registry.registerDescendant(uuid(1000 + i), root,
                    ArenaOwnershipRegistry.DescendantKind.PROJECTILE));
        }
        assertFalse(registry.registerDescendant(uuid(2000), root,
                ArenaOwnershipRegistry.DescendantKind.PROJECTILE));
        assertEquals(1, registry.rejectedProjectiles());

        for (int i = 0; i < ArenaOwnershipRegistry.EFFECT_CAP; i++) {
            assertTrue(registry.registerDescendant(uuid(3000 + i), root,
                    ArenaOwnershipRegistry.DescendantKind.EFFECT));
        }
        assertFalse(registry.registerDescendant(uuid(4000), root,
                ArenaOwnershipRegistry.DescendantKind.EFFECT));
        assertEquals(1, registry.rejectedEffects());
        assertEquals(1, registry.liveHostileRootCount());
    }

    @Test
    void replacementKeepsLineageAndCanHappenOnlyOnce() {
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        UUID oldRoot = uuid(1);
        UUID lineage = uuid(99);
        registry.registerRoot(oldRoot, lineage, ArenaOwnershipRegistry.RootRole.HOSTILE,
                "P3", 2);
        assertTrue(registry.registerDescendant(uuid(2), oldRoot,
                ArenaOwnershipRegistry.DescendantKind.HITBOX));

        ArenaOwnershipRegistry.RootRecord replacement = registry.replaceRoot(oldRoot, uuid(3));
        assertEquals(lineage, replacement.lineageId());
        assertEquals(1, replacement.replacementsUsed());
        assertFalse(registry.owns(oldRoot));
        assertEquals(0, registry.hitboxCount());
        assertThrows(IllegalStateException.class,
                () -> registry.replaceRoot(uuid(3), uuid(4)));
    }

    @Test
    void playerDescendantsNeverBlockHostilePhaseClear() {
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        UUID playerRoot = uuid(1);
        registry.registerRoot(playerRoot, uuid(100), ArenaOwnershipRegistry.RootRole.PLAYER,
                "PLAYER", 0);
        assertTrue(registry.registerDescendant(uuid(2), playerRoot,
                ArenaOwnershipRegistry.DescendantKind.PROJECTILE));
        assertTrue(registry.hostileExactZero());
        assertEquals(1, registry.projectileCount());
    }

    @Test
    void reserveHostileIsOwnedButDoesNotGateUntilPromoted() {
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        UUID reserve = uuid(7);
        registry.registerRoot(reserve, uuid(107),
                ArenaOwnershipRegistry.RootRole.RESERVE_HOSTILE, "GATEKEEPER_DUEL", 0);

        assertTrue(registry.owns(reserve));
        assertFalse(registry.isHostile(reserve));
        assertEquals(0, registry.liveHostileRootCount());
        assertTrue(registry.hostileExactZero());

        ArenaOwnershipRegistry.RootRecord promoted = registry.promoteReserveRoot(reserve);
        assertEquals(ArenaOwnershipRegistry.RootRole.HOSTILE, promoted.role());
        assertTrue(registry.isHostile(reserve));
        assertEquals(1, registry.liveHostileRootCount());
        assertFalse(registry.hostileExactZero());
        assertThrows(IllegalStateException.class, () -> registry.promoteReserveRoot(reserve));
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
