package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.server.MinecraftServer;

/** Keeps vanilla's initial spawn ticket inside the frozen Sector 01 envelope. */
public final class AshenSpanSpawnPreparationPolicy {
    static final int BOUNDED_GENERATED_TARGET = 0;

    private AshenSpanSpawnPreparationPolicy() {
    }

    public static boolean applies(MinecraftServer server) {
        return server != null
                && server.overworld() != null
                && AshenSpanMapContract.hasExpectedWorldIdentity(
                        server, server.overworld());
    }

    public static boolean addVanillaStartTicket(boolean applies) {
        return !applies;
    }

    public static int generatedTarget(boolean applies, int vanillaTarget) {
        return applies ? BOUNDED_GENERATED_TARGET : vanillaTarget;
    }
}
