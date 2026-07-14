package grcmcs.minecraft.mods.pomkotsmechs.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("effectActive")
    boolean pomkotsmechs$isEffectActive();
}
