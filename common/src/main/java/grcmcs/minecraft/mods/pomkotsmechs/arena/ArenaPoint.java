package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * An immutable dimension + position + yaw used for the lobby return point and
 * for each spawn pad. Serializable to NBT so it survives server restarts.
 */
public class ArenaPoint {
    public final ResourceLocation dimension;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;

    public ArenaPoint(ResourceLocation dimension, double x, double y, double z, float yaw) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    public ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(Registries.DIMENSION, dimension);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.toString());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putFloat("yaw", yaw);
        return tag;
    }

    public static ArenaPoint load(CompoundTag tag) {
        return new ArenaPoint(
                new ResourceLocation(tag.getString("dim")),
                tag.getDouble("x"),
                tag.getDouble("y"),
                tag.getDouble("z"),
                tag.getFloat("yaw"));
    }

    @Override
    public String toString() {
        return String.format("%s [%.1f, %.1f, %.1f] yaw %.0f", dimension, x, y, z, yaw);
    }
}
