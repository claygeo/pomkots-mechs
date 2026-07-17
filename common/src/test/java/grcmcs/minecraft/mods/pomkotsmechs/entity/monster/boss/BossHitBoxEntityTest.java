package grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossHitBoxEntityTest {
    @Test
    void discardedOrDeadParentCannotKeepHitBoxActive() {
        assertTrue(BossHitBoxPolicy.isParentActive(true, true, false));
        assertFalse(BossHitBoxPolicy.isParentActive(true, true, true));
        assertFalse(BossHitBoxPolicy.isParentActive(true, false, false));
        assertFalse(BossHitBoxPolicy.isParentActive(false, false, false));
    }

    @Test
    void stagedOrInvulnerableHitBoxRejectsDamageBeforeBreakAccounting() {
        assertTrue(BossHitBoxPolicy.rejectsDamage(true, false));
        assertTrue(BossHitBoxPolicy.rejectsDamage(false, true));
        assertTrue(BossHitBoxPolicy.rejectsDamage(true, true));
        assertFalse(BossHitBoxPolicy.rejectsDamage(false, false));
    }
}
