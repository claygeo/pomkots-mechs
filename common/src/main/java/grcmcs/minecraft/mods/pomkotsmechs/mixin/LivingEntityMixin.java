package grcmcs.minecraft.mods.pomkotsmechs.mixin;

import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaManager;
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

        // Let bypass-invulnerability damage through (e.g. /kill, void) so admins can act and void doesn't soft-lock.
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return;
        }

        if (livingEntity instanceof Player player) {
            if (Utils.isRidingPomkotsVehicle(player)) {
                cir.setReturnValue(false);
                return;
            }
        }

        // ROYALE GRACE: block ALL non-bypass damage to protected fighters (on foot
        // during the drop-in scramble) and their loot mechs — player, mob, and fall
        // damage alike — so nobody can be eliminated until the fight starts. A pure
        // no-op outside a live royale grace, so DUEL and normal play are unchanged.
        if (ArenaManager.isGraceProtected(livingEntity)) {
            cir.setReturnValue(false);
        }
    }
}
