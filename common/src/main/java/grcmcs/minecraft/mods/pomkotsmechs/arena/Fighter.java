package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Runtime state for one participant in the active match. Records the pre-match
 * gamemode and position so the player can be restored during cleanup. Not
 * persisted: a match never survives a restart.
 */
public class Fighter {
    public final UUID uuid;
    public final String name;
    public final String mechId;

    // Snapshot taken the instant before the fighter is teleported into the arena.
    public final GameType originalGameMode;
    public final ResourceKey<Level> originalDimension;
    public final double originalX;
    public final double originalY;
    public final double originalZ;
    public final float originalYaw;

    // Live match state.
    public LivingEntity mech;
    public boolean eliminated = false;
    /** Consecutive ticks spent outside the arena bounds (reset when back inside). */
    public int outsideTicks = 0;
    /**
     * ROYALE only: this fighter's slot on the sky-cage ring, assigned at deploy.
     * The cage build reuses it so each fighter's floating cage sits exactly where
     * the fighter was teleported. Unused in DUEL.
     */
    public int ringIndex = 0;

    public Fighter(UUID uuid, String name, String mechId, GameType originalGameMode,
                   ResourceKey<Level> originalDimension,
                   double originalX, double originalY, double originalZ, float originalYaw) {
        this.uuid = uuid;
        this.name = name;
        this.mechId = mechId;
        this.originalGameMode = originalGameMode;
        this.originalDimension = originalDimension;
        this.originalX = originalX;
        this.originalY = originalY;
        this.originalZ = originalZ;
        this.originalYaw = originalYaw;
    }
}
