package grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom;

/** Dependency-free Arena death-artifact policy shared by PMVC01 and unit tests. */
final class Pmvc01DeathPolicy {
    private Pmvc01DeathPolicy() {
    }

    static boolean allowsLoadoutDrop(boolean killed, boolean arenaOwned) {
        return killed && !arenaOwned;
    }

    static boolean allowsDeathExplosion(boolean arenaOwned) {
        return !arenaOwned;
    }
}
