package grcmcs.minecraft.mods.pomkotsmechs.qualification.mixin;

import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaHooks;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaOwnershipRegistry;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Qualification-only read access to the active authored ownership graph. */
@Mixin(ArenaHooks.class)
public interface AshenSpanHooksAccessor {
    @Accessor("ownership")
    static ArenaOwnershipRegistry ashenSpan$getOwnership() {
        throw new AssertionError("mixin accessor was not transformed");
    }

    @Accessor("lastKnownPositions")
    static Map<UUID, BlockPos> ashenSpan$getLastKnownPositions() {
        throw new AssertionError("mixin accessor was not transformed");
    }
}
