package grcmcs.minecraft.mods.pomkotsmechs.arena;

import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss.BossHitBoxEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.ExplosionEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.MissileBaseEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.PomkotsThrowableProjectile;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.custom.AlertEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.custom.BossBoxEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Narrow runtime bridge between entity creation hooks and the active Ashen Span
 * ownership graph. It is inert in DUEL/ROYALE and outside an active authored run.
 */
public final class ArenaHooks {
    public static final String TAG_OWNED = "mecharena_ashen_owned";
    public static final String TAG_STAGED = "mecharena_ashen_staged";

    public enum AddDecision {
        NOT_APPLICABLE,
        ACCEPT,
        REJECT
    }

    private static ArenaOwnershipRegistry ownership;
    private static int activeMatchId;
    private static ResourceKey<Level> activeDimension;
    private static final Map<UUID, BlockPos> lastKnownPositions = new LinkedHashMap<>();

    private ArenaHooks() {
    }

    static void begin(ArenaOwnershipRegistry registry, int matchId, ResourceKey<Level> dimension) {
        ownership = registry;
        activeMatchId = matchId;
        activeDimension = dimension;
    }

    static void end() {
        if (ownership != null) {
            ownership.clear();
        }
        ownership = null;
        activeMatchId = 0;
        activeDimension = null;
        lastKnownPositions.clear();
    }

    public static boolean isActive() {
        return ownership != null && activeDimension != null;
    }

    /**
     * Read-only legitimacy check for ArenaManager's periodic stray sweep. The
     * sweep must never fall back to the legacy SpawnDirector UUID set while an
     * authored Ashen ownership graph is active: roots, projectiles, effects, and
     * boss hitboxes all live in this registry instead.
     */
    static boolean isCurrentOwnedEntity(Entity entity, ServerLevel level) {
        if (!isActive() || entity == null || level == null) {
            return false;
        }
        return acceptsOwnedSweepCandidate(
                level.dimension().equals(activeDimension),
                ownership.owns(entity.getUUID()),
                hasCurrentTags(entity));
    }

    static boolean acceptsOwnedSweepCandidate(boolean dimensionMatches,
                                               boolean registered,
                                               boolean hasCurrentTags) {
        return dimensionMatches && registered && hasCurrentTags;
    }

    static ArenaOwnershipRegistry ownership() {
        if (ownership == null) {
            throw new IllegalStateException("Ashen Span ownership is not active");
        }
        return ownership;
    }

    static void registerRoot(Entity entity, ArenaOwnershipRegistry.RootRole role,
                             String phase, int slot, boolean staged) {
        ownership().registerRoot(entity.getUUID(), role, phase, slot);
        lastKnownPositions.put(entity.getUUID(), entity.blockPosition());
        tagOwned(entity, role == ArenaOwnershipRegistry.RootRole.HOSTILE);
        if (staged) {
            entity.addTag(TAG_STAGED);
        }
    }

    static void replaceRoot(Entity oldEntity, Entity replacement, boolean staged) {
        ownership().replaceRoot(oldEntity.getUUID(), replacement.getUUID());
        lastKnownPositions.remove(oldEntity.getUUID());
        lastKnownPositions.put(replacement.getUUID(), replacement.blockPosition());
        tagOwned(replacement, true);
        if (staged) {
            replacement.addTag(TAG_STAGED);
        }
    }

    static void promoteReserveRoot(Entity entity) {
        ownership().promoteReserveRoot(entity.getUUID());
        entity.addTag(ArenaManager.TAG_PVE);
    }

    static void unregisterRoot(UUID rootId) {
        if (ownership != null) {
            ownership.remove(rootId);
        }
        lastKnownPositions.remove(rootId);
    }

    public static boolean isStaged(Entity entity) {
        return entity.getTags().contains(TAG_STAGED);
    }

    public static void activate(Entity entity) {
        unstage(entity);
        if (ownership == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        ArenaOwnershipRegistry.RootRecord root = ownership.rootFor(entity.getUUID());
        if (root == null) {
            return;
        }
        for (UUID descendantId : ownership.descendantIds(root.rootId())) {
            Entity descendant = level.getEntity(descendantId);
            if (descendant != null && !descendant.isRemoved()) {
                unstage(descendant);
            }
        }
    }

    /** Explicit pre-ADD path for ownerless visual effects and boss hitboxes. */
    public static boolean beforeOwnedAdd(Entity parent, Entity child,
                                         ArenaOwnershipRegistry.DescendantKind kind) {
        if (!isActive()) {
            return true;
        }
        if (parent == null || child == null || kind == null) {
            return false;
        }
        boolean parentRegistered = ownership.owns(parent.getUUID());
        AddDecision decision = decideOwnedPreAdd(
                true,
                parentRegistered,
                parentRegistered && hasCurrentTags(parent),
                parent.getTags().contains(TAG_OWNED),
                ownership.owns(child.getUUID()),
                child.getTags().contains(TAG_OWNED));
        if (decision == AddDecision.NOT_APPLICABLE) {
            return true;
        }
        if (decision == AddDecision.REJECT) {
            return false;
        }
        if (!ownership.registerDescendant(child.getUUID(), parent.getUUID(), kind)) {
            return false;
        }
        lastKnownPositions.put(child.getUUID(), child.blockPosition());
        tagOwned(child, ownership.isHostile(parent.getUUID()));
        if (isStaged(parent)) {
            child.addTag(TAG_STAGED);
            child.setInvulnerable(true);
        }
        return true;
    }

    /** Pure policy seam for the entity-creation hooks and their regression tests. */
    static AddDecision decideOwnedPreAdd(boolean active,
                                         boolean parentRegistered,
                                         boolean parentHasCurrentTags,
                                         boolean parentTaggedOwned,
                                         boolean childRegistered,
                                         boolean childTaggedOwned) {
        if (!active) {
            return AddDecision.NOT_APPLICABLE;
        }
        if (parentRegistered) {
            return parentHasCurrentTags && !childRegistered && !childTaggedOwned
                    ? AddDecision.ACCEPT : AddDecision.REJECT;
        }
        return parentTaggedOwned || childRegistered || childTaggedOwned
                ? AddDecision.REJECT : AddDecision.NOT_APPLICABLE;
    }

    /** Called from ArenaManager's ADD guard before its legacy mode-specific checks. */
    static AddDecision admitAdded(Entity entity, ServerLevel level) {
        if (!isActive()) {
            return entity.getTags().contains(TAG_OWNED)
                    ? AddDecision.REJECT : AddDecision.NOT_APPLICABLE;
        }
        boolean dimensionMatches = level.dimension().equals(activeDimension);
        if (ownership.owns(entity.getUUID())) {
            lastKnownPositions.put(entity.getUUID(), entity.blockPosition());
            return dimensionMatches && hasCurrentTags(entity)
                    ? AddDecision.ACCEPT : AddDecision.REJECT;
        }
        Entity parent = ancestryParent(entity);
        if (parent == null || !ownership.owns(parent.getUUID())) {
            return entity.getTags().contains(TAG_OWNED)
                    ? AddDecision.REJECT : AddDecision.NOT_APPLICABLE;
        }
        if (!dimensionMatches) {
            return AddDecision.REJECT;
        }
        ArenaOwnershipRegistry.DescendantKind kind = classify(entity);
        if (kind == null || !ownership.registerDescendant(entity.getUUID(), parent.getUUID(), kind)) {
            return AddDecision.REJECT;
        }
        lastKnownPositions.put(entity.getUUID(), entity.blockPosition());
        tagOwned(entity, ownership.isHostile(parent.getUUID()));
        return AddDecision.ACCEPT;
    }

    static void onDeath(Entity entity) {
        if (ownership == null || !ownership.owns(entity.getUUID())) {
            return;
        }
        if (!ownership.isRoot(entity.getUUID())) {
            ownership.remove(entity.getUUID());
            lastKnownPositions.remove(entity.getUUID());
        }
        // Keep a dead root live through its death animation. Several Pomkots roots
        // create their final owned ExplosionEntity immediately after remove(KILLED);
        // reconcile() tombstones the root only after that creation edge has run.
    }

    static void reconcile(ServerLevel level) {
        if (!isActive() || !level.dimension().equals(activeDimension)) {
            return;
        }
        for (UUID id : new ArrayList<>(ownership.ownedIds())) {
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) {
                lastKnownPositions.put(id, entity.blockPosition());
                continue;
            }
            BlockPos lastKnown = lastKnownPositions.get(id);
            // ServerLevel#getEntity returns null for temporarily unloaded entities.
            // Absence is terminal only when the entity explicitly reports removal or
            // its last known chunk is currently loaded and the lookup is still empty.
            if (entity == null && !canReconcileMissing(lastKnown != null,
                    lastKnown != null && level.hasChunkAt(lastKnown))) {
                continue;
            }
            if (entity == null || entity.isRemoved()) {
                if (ownership.isRoot(id)) {
                    ownership.markRootBodyGone(id);
                } else {
                    ownership.remove(id);
                }
                lastKnownPositions.remove(id);
            }
        }
    }

    static boolean canReconcileMissing(boolean hasLastKnownPosition,
                                       boolean lastKnownChunkLoaded) {
        return hasLastKnownPosition && lastKnownChunkLoaded;
    }

    static int projectileCount() {
        return ownership == null ? 0 : ownership.projectileCount();
    }

    static int effectCount() {
        return ownership == null ? 0 : ownership.effectCount();
    }

    static int rejectedProjectiles() {
        return ownership == null ? 0 : ownership.rejectedProjectiles();
    }

    private static void tagOwned(Entity entity, boolean hostile) {
        entity.addTag(ArenaManager.TAG_ARENA);
        entity.addTag(ArenaManager.TAG_MATCH_PREFIX + activeMatchId);
        entity.addTag(ArenaManager.TAG_SOLO);
        entity.addTag(TAG_OWNED);
        if (hostile) {
            entity.addTag(ArenaManager.TAG_PVE);
        }
        if (entity instanceof Projectile) {
            entity.addTag(ArenaManager.TAG_SOLO_PROJECTILE);
        }
    }

    private static void unstage(Entity entity) {
        entity.removeTag(TAG_STAGED);
        entity.setInvulnerable(false);
    }

    private static boolean hasCurrentTags(Entity entity) {
        return entity.getTags().contains(TAG_OWNED)
                && entity.getTags().contains(ArenaManager.TAG_ARENA)
                && entity.getTags().contains(ArenaManager.TAG_SOLO)
                && entity.getTags().contains(ArenaManager.TAG_MATCH_PREFIX + activeMatchId);
    }

    @Nullable
    private static Entity ancestryParent(Entity entity) {
        if (entity instanceof BossHitBoxEntity hitBox) {
            return hitBox.getParentEntity();
        }
        if (entity instanceof PomkotsThrowableProjectile projectile
                && projectile.getShooter() != null) {
            return projectile.getShooter();
        }
        if (entity instanceof MissileBaseEntity missile && missile.getShooter() != null) {
            return missile.getShooter();
        }
        if (entity instanceof Projectile projectile) {
            return projectile.getOwner();
        }
        return null;
    }

    @Nullable
    private static ArenaOwnershipRegistry.DescendantKind classify(Entity entity) {
        if (entity instanceof BossHitBoxEntity) {
            return ArenaOwnershipRegistry.DescendantKind.HITBOX;
        }
        if (entity instanceof ExplosionEntity || entity instanceof AlertEntity
                || entity instanceof BossBoxEntity) {
            return ArenaOwnershipRegistry.DescendantKind.EFFECT;
        }
        if (entity instanceof Projectile) {
            return ArenaOwnershipRegistry.DescendantKind.PROJECTILE;
        }
        return null;
    }
}
