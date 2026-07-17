package grcmcs.minecraft.mods.pomkotsmechs.arena;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exact, per-match ownership graph for Ashen Span combat entities.
 *
 * <p>Roots are recorded before their world ADD event. Descendants are accepted only
 * when their immediate parent already belongs to the graph, so proximity, entity type,
 * and stale match tags can never manufacture ownership. Replacements retain a stable
 * lineage id while receiving a new root UUID.</p>
 */
public final class ArenaOwnershipRegistry {
    public static final int PROJECTILE_CAP = 48;
    public static final int EFFECT_CAP = 5;

    public enum RootRole {
        PLAYER,
        /** Mission-owned future hostile that is staged but must not gate the current phase. */
        RESERVE_HOSTILE,
        HOSTILE
    }

    public enum DescendantKind {
        PROJECTILE,
        EFFECT,
        HITBOX
    }

    public record RootRecord(UUID rootId, UUID lineageId, RootRole role,
                             String phase, int slot, int replacementsUsed) {
        public RootRecord {
            if (rootId == null || lineageId == null || role == null || phase == null
                    || phase.isBlank() || slot < 0 || replacementsUsed < 0
                    || replacementsUsed > 1) {
                throw new IllegalArgumentException("invalid Arena root record");
            }
        }
    }

    public record DescendantRecord(UUID entityId, UUID rootId, UUID lineageId,
                                   DescendantKind kind) {
        public DescendantRecord {
            if (entityId == null || rootId == null || lineageId == null || kind == null) {
                throw new IllegalArgumentException("invalid Arena descendant record");
            }
        }
    }

    private final Map<UUID, RootRecord> roots = new LinkedHashMap<>();
    private final Map<UUID, DescendantRecord> descendants = new LinkedHashMap<>();
    private int rejectedProjectiles;
    private int rejectedEffects;

    public void clear() {
        roots.clear();
        descendants.clear();
        goneRootBodies.clear();
        rejectedProjectiles = 0;
        rejectedEffects = 0;
    }

    public RootRecord registerRoot(UUID rootId, RootRole role, String phase, int slot) {
        if (owns(rootId)) {
            throw new IllegalStateException("entity is already Arena-owned: " + rootId);
        }
        RootRecord record = new RootRecord(rootId, UUID.randomUUID(), role, phase, slot, 0);
        roots.put(rootId, record);
        return record;
    }

    /** Test/package seam for deterministic lineage construction. */
    RootRecord registerRoot(UUID rootId, UUID lineageId, RootRole role,
                            String phase, int slot) {
        if (owns(rootId)) {
            throw new IllegalStateException("entity is already Arena-owned: " + rootId);
        }
        RootRecord record = new RootRecord(rootId, lineageId, role, phase, slot, 0);
        roots.put(rootId, record);
        return record;
    }

    /**
     * Replaces one hostile root without creating a new roster slot. The old root and
     * all of its live descendants leave the graph atomically; the lineage can be
     * replaced at most once.
     */
    public RootRecord replaceRoot(UUID oldRootId, UUID newRootId) {
        RootRecord old = roots.get(oldRootId);
        if (old == null) {
            throw new IllegalArgumentException("unknown root: " + oldRootId);
        }
        if (old.replacementsUsed() >= 1) {
            throw new IllegalStateException("root replacement budget is exhausted");
        }
        if (owns(newRootId)) {
            throw new IllegalStateException("replacement UUID is already owned");
        }
        removeRootAndDescendants(oldRootId);
        RootRecord replacement = new RootRecord(newRootId, old.lineageId(), old.role(),
                old.phase(), old.slot(), old.replacementsUsed() + 1);
        roots.put(newRootId, replacement);
        return replacement;
    }

    /** Promotes an already-admitted inactive reveal root into the current hostile phase. */
    public RootRecord promoteReserveRoot(UUID rootId) {
        RootRecord root = roots.get(rootId);
        if (root == null || root.role() != RootRole.RESERVE_HOSTILE
                || goneRootBodies.containsKey(rootId)) {
            throw new IllegalStateException("root is not a live reserve hostile: " + rootId);
        }
        RootRecord promoted = new RootRecord(root.rootId(), root.lineageId(), RootRole.HOSTILE,
                root.phase(), root.slot(), root.replacementsUsed());
        roots.put(rootId, promoted);
        return promoted;
    }

    /**
     * Registers a descendant by immediate parent. Returns false when the parent is
     * unknown, the UUID is already used, or the relevant live cap is full.
     */
    public boolean registerDescendant(UUID entityId, UUID parentId, DescendantKind kind) {
        if (entityId == null || parentId == null || kind == null || owns(entityId)) {
            return false;
        }
        RootRecord root = rootFor(parentId);
        if (root == null) {
            return false;
        }
        if (kind == DescendantKind.PROJECTILE && projectileCount() >= PROJECTILE_CAP) {
            rejectedProjectiles++;
            return false;
        }
        if (kind == DescendantKind.EFFECT && effectCount() >= EFFECT_CAP) {
            rejectedEffects++;
            return false;
        }
        descendants.put(entityId,
                new DescendantRecord(entityId, root.rootId(), root.lineageId(), kind));
        return true;
    }

    public boolean owns(UUID entityId) {
        return roots.containsKey(entityId) || descendants.containsKey(entityId);
    }

    public boolean isRoot(UUID entityId) {
        return roots.containsKey(entityId);
    }

    public boolean isHostile(UUID entityId) {
        RootRecord root = rootFor(entityId);
        return root != null && root.role() == RootRole.HOSTILE;
    }

    @Nullable
    public RootRecord rootFor(UUID entityId) {
        RootRecord direct = roots.get(entityId);
        if (direct != null) {
            return direct;
        }
        DescendantRecord descendant = descendants.get(entityId);
        return descendant == null ? null : roots.get(descendant.rootId());
    }

    @Nullable
    public DescendantRecord descendant(UUID entityId) {
        return descendants.get(entityId);
    }

    public void remove(UUID entityId) {
        if (roots.containsKey(entityId)) {
            removeRootAndDescendants(entityId);
        } else {
            descendants.remove(entityId);
        }
    }

    /** Removes only the root body while retaining live descendants until exact zero. */
    public void markRootBodyGone(UUID rootId) {
        RootRecord root = roots.get(rootId);
        if (root == null) {
            return;
        }
        // A tombstone preserves ancestry for already-created descendants and blocks
        // phase clear until they disappear. Root bodies are counted by liveRootCount,
        // which checks this explicit set through a synthetic descendant-free marker.
        roots.put(rootId, new RootRecord(root.rootId(), root.lineageId(), root.role(),
                root.phase(), root.slot(), root.replacementsUsed()));
        goneRootBodies.put(rootId, Boolean.TRUE);
    }

    private final Map<UUID, Boolean> goneRootBodies = new LinkedHashMap<>();

    public int liveRootCount() {
        return roots.size() - goneRootBodies.size();
    }

    public int liveHostileRootCount() {
        int count = 0;
        for (RootRecord root : roots.values()) {
            if (root.role() == RootRole.HOSTILE && !goneRootBodies.containsKey(root.rootId())) {
                count++;
            }
        }
        return count;
    }

    public int hostileDescendantCount() {
        int count = 0;
        for (DescendantRecord descendant : descendants.values()) {
            RootRecord root = roots.get(descendant.rootId());
            if (root != null && root.role() == RootRole.HOSTILE) {
                count++;
            }
        }
        return count;
    }

    public int hostileDescendantCount(DescendantKind kind) {
        int count = 0;
        for (DescendantRecord descendant : descendants.values()) {
            RootRecord root = roots.get(descendant.rootId());
            if (descendant.kind() == kind && root != null && root.role() == RootRole.HOSTILE) {
                count++;
            }
        }
        return count;
    }

    public boolean hostileExactZero() {
        return liveHostileRootCount() == 0 && hostileDescendantCount() == 0;
    }

    public int projectileCount() {
        return count(DescendantKind.PROJECTILE);
    }

    public int effectCount() {
        return count(DescendantKind.EFFECT);
    }

    public int hitboxCount() {
        return count(DescendantKind.HITBOX);
    }

    private int count(DescendantKind kind) {
        int count = 0;
        for (DescendantRecord record : descendants.values()) {
            if (record.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    public int rejectedProjectiles() {
        return rejectedProjectiles;
    }

    public int rejectedEffects() {
        return rejectedEffects;
    }

    public List<UUID> ownedIds() {
        List<UUID> result = new ArrayList<>(roots.keySet());
        result.addAll(descendants.keySet());
        return List.copyOf(result);
    }

    public List<UUID> descendantIds(UUID rootId) {
        List<UUID> result = new ArrayList<>();
        for (DescendantRecord record : descendants.values()) {
            if (record.rootId().equals(rootId)) {
                result.add(record.entityId());
            }
        }
        return List.copyOf(result);
    }

    private void removeRootAndDescendants(UUID rootId) {
        roots.remove(rootId);
        goneRootBodies.remove(rootId);
        descendants.entrySet().removeIf(entry -> entry.getValue().rootId().equals(rootId));
    }
}
