package grcmcs.minecraft.mods.pomkotsmechs.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * One glass block the royale cage actually placed (dimension + position).
 * Persisted in {@link ArenaData} so a crash mid-match cannot leave glass in the
 * city: every placed position is recorded, and the recorded positions are set
 * back to air on removal / on the next {@code SERVER_STARTED}. Only positions
 * that were AIR before placement are ever recorded, so removal never clobbers
 * real terrain.
 */
public class CageBlock {
    public final ResourceLocation dimension;
    public final BlockPos pos;

    public CageBlock(ResourceLocation dimension, BlockPos pos) {
        this.dimension = dimension;
        this.pos = pos;
    }

    public ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(Registries.DIMENSION, dimension);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", dimension.toString());
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        return tag;
    }

    public static CageBlock load(CompoundTag tag) {
        return new CageBlock(
                new ResourceLocation(tag.getString("dim")),
                new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
    }
}
