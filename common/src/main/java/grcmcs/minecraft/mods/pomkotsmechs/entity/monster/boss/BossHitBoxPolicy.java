package grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss;

/** Dependency-free lifecycle rule shared by modern boss hitboxes and tests. */
final class BossHitBoxPolicy {
    private BossHitBoxPolicy() {
    }

    static boolean isParentActive(boolean present, boolean alive, boolean removed) {
        return present && alive && !removed;
    }

    static boolean rejectsDamage(boolean staged, boolean invulnerable) {
        return staged || invulnerable;
    }
}
