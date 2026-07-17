package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Narrow cross-source-set bridge for Forge's headless, world-backed GameTests.
 * Production mission code continues to use the package-private runtime seams.
 */
public final class ArenaGameTestAccess {
    public record DirectorOutcomeProbe(boolean prepared, String status, String summary,
                                       String failureReason, boolean ownershipActiveAfterReset) {
    }

    private ArenaGameTestAccess() {
    }

    public static void beginOwnership(ArenaOwnershipRegistry registry, int matchId,
                                      ResourceKey<Level> dimension) {
        ArenaHooks.begin(registry, matchId, dimension);
    }

    public static void endOwnership() {
        ArenaHooks.end();
    }

    public static void registerRoot(Entity entity, ArenaOwnershipRegistry.RootRole role,
                                    String phase, int slot, boolean staged) {
        ArenaHooks.registerRoot(entity, role, phase, slot, staged);
    }

    public static void unregisterRoot(UUID rootId) {
        ArenaHooks.unregisterRoot(rootId);
    }

    public static void onDeath(Entity entity) {
        ArenaHooks.onDeath(entity);
    }

    public static void reconcile(ServerLevel level) {
        ArenaHooks.reconcile(level);
    }

    public static boolean openGate(MinecraftServer server, ServerLevel level,
                                   AshenSpanDefinition.BlockVolume volume) {
        return MissionGateLedger.open(server, level, volume);
    }

    public static boolean restoreGates(MinecraftServer server) {
        return MissionGateLedger.restoreAll(server);
    }

    public static boolean prepareSoloGarageBuild(LivingEntity mech) {
        return ArenaManager.prepareSoloGarageBuild(mech);
    }

    public static void clearTransientDescendantsForRelocation(
            ServerLevel level, ArenaOwnershipRegistry registry, UUID rootId) {
        AshenSpanDirector.clearTransientDescendantsForRelocation(level, registry, rootId);
    }

    /**
     * Executes the smallest world-backed director lifecycle that does not require the
     * authored bounded world: prepare, force one terminal outcome, report, and reset.
     */
    public static DirectorOutcomeProbe probeForcedDirectorOutcome(
            ServerLevel level, int matchId, long seed, int selectedBuild,
            UUID ownerId, LivingEntity playerMech,
            AshenSpanMissionModel.DefeatReason reason, String detail) {
        AshenSpanDirector director = new AshenSpanDirector();
        boolean prepared = director.prepare(level, matchId, seed, selectedBuild,
                ownerId, playerMech);
        if (prepared) {
            director.recordDefeat(reason, detail);
        }
        String status = director.status();
        String summary = director.summary(playerMech.getHealth());
        String failureReason = director.failureReason();
        director.reset();
        return new DirectorOutcomeProbe(prepared, status, summary, failureReason,
                ArenaHooks.isActive());
    }
}
