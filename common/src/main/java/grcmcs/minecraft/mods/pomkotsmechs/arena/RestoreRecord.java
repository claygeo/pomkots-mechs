package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * A fighter's ORIGINAL pre-match snapshot (gamemode + dimension + position +
 * yaw). Persisted in {@link ArenaData} so that a player can always be restored
 * to exactly where and how they were before the match — even across a
 * disconnect, a death-screen respawn, or a full server crash. Serializable to
 * NBT the same way {@link ArenaPoint} is.
 */
public class RestoreRecord {
    public final GameType gameMode;
    public final ResourceKey<Level> dimension;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;

    public RestoreRecord(GameType gameMode, ResourceKey<Level> dimension,
                         double x, double y, double z, float yaw) {
        this.gameMode = gameMode;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("gamemode", gameMode.getId());
        tag.putString("dim", dimension.location().toString());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putFloat("yaw", yaw);
        return tag;
    }

    public static RestoreRecord load(CompoundTag tag) {
        return new RestoreRecord(
                GameType.byId(tag.getInt("gamemode")),
                ResourceKey.create(Registries.DIMENSION, new ResourceLocation(tag.getString("dim"))),
                tag.getDouble("x"),
                tag.getDouble("y"),
                tag.getDouble("z"),
                tag.getFloat("yaw"));
    }
}
