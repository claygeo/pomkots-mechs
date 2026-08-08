package grcmcs.minecraft.mods.pomkotsmechs.qualification.mixin;

import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaOwnershipRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Qualification-only proof that every internal ownership index was cleared. */
@Mixin(ArenaOwnershipRegistry.class)
public interface AshenSpanOwnershipAccessor {
    @Accessor("roots")
    Map<UUID, ArenaOwnershipRegistry.RootRecord> ashenSpan$getRoots();

    @Accessor("descendants")
    Map<UUID, ArenaOwnershipRegistry.DescendantRecord> ashenSpan$getDescendants();

    @Accessor("goneRootBodies")
    Map<UUID, Boolean> ashenSpan$getGoneRootBodies();
}
