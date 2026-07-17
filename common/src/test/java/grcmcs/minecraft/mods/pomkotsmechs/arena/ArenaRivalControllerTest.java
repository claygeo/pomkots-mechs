package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRivalControllerTest {
    @Test
    void distanceBandsHonorAuthoredBoundaries() {
        assertEquals(ArenaRivalController.Tactic.RETREAT,
                ArenaRivalController.tacticForDistance(13.999));
        assertEquals(ArenaRivalController.Tactic.SMG_BURST,
                ArenaRivalController.tacticForDistance(14.0));
        assertEquals(ArenaRivalController.Tactic.SMG_BURST,
                ArenaRivalController.tacticForDistance(27.999));
        assertEquals(ArenaRivalController.Tactic.STRAFE_RIFLE,
                ArenaRivalController.tacticForDistance(28.0));
        assertEquals(ArenaRivalController.Tactic.STRAFE_RIFLE,
                ArenaRivalController.tacticForDistance(48.0));
        assertEquals(ArenaRivalController.Tactic.CLOSE_RIFLE,
                ArenaRivalController.tacticForDistance(48.001));
    }

    @Test
    void replayIsDeterministicFromMissionSeed() {
        ArenaRivalController first = new ArenaRivalController();
        ArenaRivalController replay = new ArenaRivalController();
        first.activate(0x5A17E1L);
        replay.activate(0x5A17E1L);

        for (int tick = 0; tick < 500; tick++) {
            double distance = tick < 100 ? 60.0 : tick < 200 ? 38.0 : tick < 350 ? 20.0 : 10.0;
            double health = tick < 250 ? 0.8 : 0.4;
            assertEquals(first.tick(distance, health, true), replay.tick(distance, health, true));
        }
    }

    @Test
    void smgBurstAndCooldownStayInsideLockedRanges() {
        ArenaRivalController controller = new ArenaRivalController();
        controller.activate(77L);

        List<ArenaRivalController.Weapon> weapons = new ArrayList<>();
        for (int tick = 0; tick < 180; tick++) {
            weapons.add(controller.tick(20.0, 1.0, true).weapon());
        }

        int first = weapons.indexOf(ArenaRivalController.Weapon.LEFT_HAND_SMG);
        assertTrue(first >= 1, "transition tick releases before the first burst");
        int end = first;
        while (end < weapons.size()
                && weapons.get(end) == ArenaRivalController.Weapon.LEFT_HAND_SMG) {
            end++;
        }
        int burstLength = end - first;
        assertTrue(burstLength >= 10 && burstLength <= 14, "burst=" + burstLength);

        int next = end;
        while (next < weapons.size()
                && weapons.get(next) != ArenaRivalController.Weapon.LEFT_HAND_SMG) {
            next++;
        }
        int cooldown = next - end;
        assertTrue(cooldown >= 45 && cooldown <= 70, "cooldown=" + cooldown);
    }

    @Test
    void rifleCadenceIsThirtyToFortyFiveTicks() {
        ArenaRivalController controller = new ArenaRivalController();
        controller.activate(91234L);
        List<Integer> shots = new ArrayList<>();
        for (int tick = 0; tick < 300; tick++) {
            if (controller.tick(36.0, 1.0, true).weapon()
                    == ArenaRivalController.Weapon.RIGHT_HAND_RIFLE) {
                shots.add(tick);
            }
        }
        assertTrue(shots.size() >= 5);
        for (int i = 1; i < shots.size(); i++) {
            int interval = shots.get(i) - shots.get(i - 1);
            assertTrue(interval >= 30 && interval <= 45, "interval=" + interval);
        }
    }

    @Test
    void suwaIsOnlyAddedBelowHalfHealthAndNeverCombinesWeapons() {
        ArenaRivalController healthy = new ArenaRivalController();
        healthy.activate(9L);
        for (int tick = 0; tick < 400; tick++) {
            assertNotEquals(ArenaRivalController.Weapon.RIGHT_SHOULDER_SUWA,
                    healthy.tick(55.0, 0.5, true).weapon());
        }

        ArenaRivalController damaged = new ArenaRivalController();
        damaged.activate(9L);
        boolean sawSuwa = false;
        for (int tick = 0; tick < 400; tick++) {
            ArenaRivalController.Decision decision = damaged.tick(55.0, 0.499, true);
            sawSuwa |= decision.weapon() == ArenaRivalController.Weapon.RIGHT_SHOULDER_SUWA;
            assertNotNull(decision.weapon()); // one enum value is the entire weapon command
        }
        assertTrue(sawSuwa);
    }

    @Test
    void transitionTargetLossAndLedgeRejectionReleaseUnsafeControl() {
        ArenaRivalController controller = new ArenaRivalController();
        controller.activate(123L);

        ArenaRivalController.Decision far = controller.tick(60.0, 1.0, true);
        assertTrue(far.releasedForTransition());
        assertEquals(ArenaRivalController.Weapon.NONE, far.weapon());

        ArenaRivalController.Decision changed = controller.tick(20.0, 1.0, true);
        assertTrue(changed.releasedForTransition());
        assertEquals(ArenaRivalController.Weapon.NONE, changed.weapon());

        ArenaRivalController.Decision lost = controller.tick(20.0, 1.0, false);
        assertEquals(ArenaRivalController.Tactic.HOLD, lost.tactic());
        assertEquals(0.0F, lost.forward());
        assertEquals(0.0F, lost.strafe());
        assertEquals(ArenaRivalController.Weapon.NONE, lost.weapon());

        controller.activate(123L);
        ArenaRivalController.Decision retreat = null;
        for (int i = 0; i < 200; i++) {
            ArenaRivalController.Decision candidate = controller.tick(10.0, 1.0, true);
            if (candidate.evade()) {
                retreat = candidate;
                break;
            }
        }
        assertNotNull(retreat, "deterministic retreat eventually requests one bounded evade");
        ArenaRivalController.Decision rejected = retreat.withSafety(false, false);
        assertEquals(0.0F, rejected.forward());
        assertEquals(0.0F, rejected.strafe());
        assertFalse(rejected.evade());
    }

    @Test
    void targetContractRequiresEverySafetyCondition() {
        assertTrue(ArenaRivalController.targetContract(true, true, true, 150.0 * 150.0, true));
        assertFalse(ArenaRivalController.targetContract(false, true, true, 1.0, true));
        assertFalse(ArenaRivalController.targetContract(true, false, true, 1.0, true));
        assertFalse(ArenaRivalController.targetContract(true, true, false, 1.0, true));
        assertFalse(ArenaRivalController.targetContract(true, true, true, 150.001 * 150.001, true));
        assertFalse(ArenaRivalController.targetContract(true, true, true, 1.0, false));
    }

    @Test
    void deterministicSamplerIsBoundedAndRejectsInvalidRange() {
        for (long ordinal = 0; ordinal < 500; ordinal++) {
            int value = ArenaRivalController.deterministicRange(42L, ordinal, 10, 14);
            assertTrue(value >= 10 && value <= 14);
            assertEquals(value, ArenaRivalController.deterministicRange(42L, ordinal, 10, 14));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ArenaRivalController.deterministicRange(1L, 0L, 5, 4));
    }
}
