package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent arena configuration (lobby return point + spawn pads) plus the
 * durable {@code pendingRestores} table. Stored via vanilla {@link SavedData}
 * on the overworld's data storage so it survives restarts. Match/queue state is
 * intentionally NOT persisted here; the pending-restore records are, so that no
 * player is ever stranded in the wrong gamemode/position by a crash, a
 * disconnect, or a death-screen respawn.
 */
public class ArenaData extends SavedData {
    public static final String DATA_NAME = "pomkotsmechs_arena";

    @Nullable
    private ArenaPoint lobby;
    private final List<ArenaPoint> pads = new ArrayList<>();
    // UUID -> the fighter's ORIGINAL pre-match snapshot. A record exists exactly
    // while a player still owes a restore; it is removed once actually applied.
    private final Map<UUID, RestoreRecord> pendingRestores = new LinkedHashMap<>();

    public ArenaData() {
    }

    public static ArenaData load(CompoundTag tag) {
        ArenaData data = new ArenaData();
        if (tag.contains("lobby")) {
            data.lobby = ArenaPoint.load(tag.getCompound("lobby"));
        }
        ListTag padList = tag.getList("pads", Tag.TAG_COMPOUND);
        for (int i = 0; i < padList.size(); i++) {
            data.pads.add(ArenaPoint.load(padList.getCompound(i)));
        }
        ListTag restoreList = tag.getList("pendingRestores", Tag.TAG_COMPOUND);
        for (int i = 0; i < restoreList.size(); i++) {
            CompoundTag entry = restoreList.getCompound(i);
            if (entry.hasUUID("uuid")) {
                data.pendingRestores.put(entry.getUUID("uuid"), RestoreRecord.load(entry));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (lobby != null) {
            tag.put("lobby", lobby.save());
        }
        ListTag padList = new ListTag();
        for (ArenaPoint pad : pads) {
            padList.add(pad.save());
        }
        tag.put("pads", padList);
        ListTag restoreList = new ListTag();
        for (Map.Entry<UUID, RestoreRecord> e : pendingRestores.entrySet()) {
            CompoundTag entry = e.getValue().save();
            entry.putUUID("uuid", e.getKey());
            restoreList.add(entry);
        }
        tag.put("pendingRestores", restoreList);
        return tag;
    }

    /** Fetch (or lazily create) the arena data attached to the overworld. */
    public static ArenaData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(ArenaData::load, ArenaData::new, DATA_NAME);
    }

    public static ArenaData get(MinecraftServer server) {
        return get(server.overworld());
    }

    @Nullable
    public ArenaPoint getLobby() {
        return lobby;
    }

    public void setLobby(ArenaPoint lobby) {
        this.lobby = lobby;
        setDirty();
    }

    public List<ArenaPoint> getPads() {
        return pads;
    }

    public void addPad(ArenaPoint pad) {
        pads.add(pad);
        setDirty();
    }

    public void clearPads() {
        pads.clear();
        setDirty();
    }

    // ---------------------------------------------------------------------
    // Pending-restore table (durable, crash-safe)
    // ---------------------------------------------------------------------

    @Nullable
    public RestoreRecord getPendingRestore(UUID uuid) {
        return pendingRestores.get(uuid);
    }

    public void putPendingRestore(UUID uuid, RestoreRecord record) {
        pendingRestores.put(uuid, record);
        setDirty();
    }

    public void removePendingRestore(UUID uuid) {
        if (pendingRestores.remove(uuid) != null) {
            setDirty();
        }
    }

    /** Defensive copy for safe iteration while records may be mutated. */
    public Map<UUID, RestoreRecord> copyPendingRestores() {
        return new LinkedHashMap<>(pendingRestores);
    }
}
