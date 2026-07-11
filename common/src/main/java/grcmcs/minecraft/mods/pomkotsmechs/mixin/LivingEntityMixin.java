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

        // The royale protection windows read server-only static match state; never
        // touch them from the integrated server's client thread (client-side hurt
        // calls would otherwise race the server statics). A pure client no-op.
        if (livingEntity.level().isClientSide) {
            return;
        }

        // ROYALE protection (cage HOLD + GRACE + ENDING): block ALL non-bypass
        // damage when EITHER the victim is protected (a fighter on foot during the
        // drop-in scramble, or a current-match loot mech) OR the attacker is — a
        // protected fighter/mech must not deal damage either. Covers player, mob,
        // and fall damage alike so nobody is eliminated until the fight starts and
        // the winner survives the wind-down. A pure no-op outside a live royale
        // protection window, so DUEL and normal play are unchanged.
        if (ArenaManager.isGraceProtected(livingEntity)
                || ArenaManager.isAttackerGraceProtected(source)) {
            cir.setReturnValue(false);
        }
    }
}
