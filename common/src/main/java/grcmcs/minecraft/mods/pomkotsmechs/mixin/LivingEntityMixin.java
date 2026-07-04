package grcmcs.minecraft.mods.pomkotsmechs.mixin;

import grcmcs.minecraft.mods.pomkotsmechs.util.Utils;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class LivingEntityMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    public void onHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity livingEntity = (LivingEntity) (Object) this;

        if (livingEntity instanceof Player player) {
            // Let bypass-invulnerability damage through (e.g. /kill, void) so admins can act and void doesn't soft-lock.
            if (Utils.isRidingPomkotsVehicle(player) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
                cir.setReturnValue(false);
            }
        }
    }
}
