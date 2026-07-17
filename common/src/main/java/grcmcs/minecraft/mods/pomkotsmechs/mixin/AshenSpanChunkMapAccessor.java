package grcmcs.minecraft.mods.pomkotsmechs.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Read-only access to persisted chunk NBT without tickets or generation. */
@Mixin(ChunkMap.class)
public interface AshenSpanChunkMapAccessor {
    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> pomkotsmechs$readChunk(ChunkPos pos);
}
