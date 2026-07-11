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

    // Royale mode configuration. Defaults chosen so an un-migrated world reads
    // exactly as DUEL with sane royale settings if the admin never touched them.
    private Mode mode = Mode.DUEL;
    @Nullable
    private ArenaPoint royaleCenter;
    private int royaleRadius = 200;
    private int mechCount = 12;
    // Royale start gates + match tuning (all admin-set, all persisted). Defaults
    // chosen so an un-migrated world reads as sane royale settings untouched.
    private int royaleMinPlayers = 8;      // sustained-queue floor for a delayed auto-start
    private int royaleStartAtPlayers = 16; // queue size that triggers an immediate auto-start
    private int royaleGraceTicks = 1200;   // no-combat GRACE window after the cages drop (60s)
    private RoyalePve royalePve = RoyalePve.OFF; // PvE pressure at GRACE end
    // Glass positions the current royale cage actually placed. Persisted so a
    // crash cannot leave a glass box in the city; emptied when the cage is
    // removed (cage break, cleanup, or the SERVER_STARTED boot sweep).
    private final List<CageBlock> cageBlocks = new ArrayList<>();
    // Monotonic royale match sequence. Persisted (and force-flushed before any
    // scatter spawns) so a restart can never reuse a match id that crash-leftover
    // mechs in unloaded chunks still carry.
    private int nextMatchId = 1;

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
        if (tag.contains("mode")) {
            try {
                data.mode = Mode.valueOf(tag.getString("mode"));
            } catch (IllegalArgumentException ignored) {
                data.mode = Mode.DUEL; // unrecognised value -> safe default
            }
        }
        if (tag.contains("royaleCenter")) {
            data.royaleCenter = ArenaPoint.load(tag.getCompound("royaleCenter"));
        }
        if (tag.contains("royaleRadius")) {
            data.royaleRadius = tag.getInt("royaleRadius");
        }
        if (tag.contains("mechCount")) {
            data.mechCount = tag.getInt("mechCount");
        }
        if (tag.contains("royaleMinPlayers")) {
            data.royaleMinPlayers = tag.getInt("royaleMinPlayers");
        }
        if (tag.contains("royaleStartAtPlayers")) {
            data.royaleStartAtPlayers = tag.getInt("royaleStartAtPlayers");
        }
        if (tag.contains("royaleGraceTicks")) {
            data.royaleGraceTicks = tag.getInt("royaleGraceTicks");
        }
        if (tag.contains("royalePve")) {
            try {
                data.royalePve = RoyalePve.valueOf(tag.getString("royalePve"));
            } catch (IllegalArgumentException ignored) {
                data.royalePve = RoyalePve.OFF; // unrecognised value -> safe default
            }
        }
        ListTag cageList = tag.getList("cageBlocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < cageList.size(); i++) {
            data.cageBlocks.add(CageBlock.load(cageList.getCompound(i)));
        }
        if (tag.contains("nextMatchId")) {
            data.nextMatchId = tag.getInt("nextMatchId");
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
        tag.putString("mode", mode.name());
        if (royaleCenter != null) {
            tag.put("royaleCenter", royaleCenter.save());
        }
        tag.putInt("royaleRadius", royaleRadius);
        tag.putInt("mechCount", mechCount);
        tag.putInt("royaleMinPlayers", royaleMinPlayers);
        tag.putInt("royaleStartAtPlayers", royaleStartAtPlayers);
        tag.putInt("royaleGraceTicks", royaleGraceTicks);
        tag.putString("royalePve", royalePve.name());
        ListTag cageList = new ListTag();
        for (CageBlock cb : cageBlocks) {
            cageList.add(cb.save());
        }
        tag.put("cageBlocks", cageList);
        tag.putInt("nextMatchId", nextMatchId);
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

    // ---------------------------------------------------------------------
    // Royale configuration
    // ---------------------------------------------------------------------

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        setDirty();
    }

    @Nullable
    public ArenaPoint getRoyaleCenter() {
        return royaleCenter;
    }

    public void setRoyaleCenter(ArenaPoint royaleCenter) {
        this.royaleCenter = royaleCenter;
        setDirty();
    }

    public int getRoyaleRadius() {
        return royaleRadius;
    }

    public void setRoyaleRadius(int royaleRadius) {
        this.royaleRadius = royaleRadius;
        setDirty();
    }

    public int getMechCount() {
        return mechCount;
    }

    public void setMechCount(int mechCount) {
        this.mechCount = mechCount;
        setDirty();
    }

    public int getRoyaleMinPlayers() {
        return royaleMinPlayers;
    }

    public void setRoyaleMinPlayers(int royaleMinPlayers) {
        this.royaleMinPlayers = royaleMinPlayers;
        setDirty();
    }

    public int getRoyaleStartAtPlayers() {
        return royaleStartAtPlayers;
    }

    public void setRoyaleStartAtPlayers(int royaleStartAtPlayers) {
        this.royaleStartAtPlayers = royaleStartAtPlayers;
        setDirty();
    }

    public int getRoyaleGraceTicks() {
        return royaleGraceTicks;
    }

    public void setRoyaleGraceTicks(int royaleGraceTicks) {
        this.royaleGraceTicks = royaleGraceTicks;
        setDirty();
    }

    public RoyalePve getRoyalePve() {
        return royalePve;
    }

    public void setRoyalePve(RoyalePve royalePve) {
        this.royalePve = royalePve;
        setDirty();
    }

    // ---------------------------------------------------------------------
    // Royale cage block ledger (durable, crash-safe)
    // ---------------------------------------------------------------------

    public List<CageBlock> getCageBlocks() {
        return cageBlocks;
    }

    public void addCageBlock(CageBlock block) {
        cageBlocks.add(block);
        setDirty();
    }

    public void setCageBlocks(List<CageBlock> blocks) {
        cageBlocks.clear();
        cageBlocks.addAll(blocks);
        setDirty();
    }

    /** Claims the next royale match id, durably advancing the sequence. */
    public int claimMatchId() {
        int id = nextMatchId++;
        setDirty();
        return id;
    }
}
