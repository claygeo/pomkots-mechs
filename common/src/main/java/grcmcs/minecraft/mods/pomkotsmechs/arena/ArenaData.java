package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent arena configuration (lobby return point + spawn pads). Stored via
 * vanilla {@link SavedData} on the overworld's data storage so it survives
 * restarts. Match/queue state is intentionally NOT persisted here.
 */
public class ArenaData extends SavedData {
    public static final String DATA_NAME = "pomkotsmechs_arena";

    @Nullable
    private ArenaPoint lobby;
    private final List<ArenaPoint> pads = new ArrayList<>();

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
}
