package grcmcs.minecraft.mods.pomkotsmechs.mixin;

import grcmcs.minecraft.mods.pomkotsmechs.arena.AshenSpanSpawnPreparationPolicy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents vanilla's 11-chunk start ticket from expanding the bounded mission. */
@Mixin(MinecraftServer.class)
public abstract class AshenSpanSpawnPreparationMixin {
    @Redirect(
            method = "prepareLevels",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;addRegionTicket("
                            + "Lnet/minecraft/server/level/TicketType;"
                            + "Lnet/minecraft/world/level/ChunkPos;ILjava/lang/Object;)V"))
    private <T> void pomkotsmechs$skipInitialSpawnTicket(
            ServerChunkCache chunks, TicketType<T> type, ChunkPos pos,
            int distance, T value) {
        boolean applies = AshenSpanSpawnPreparationPolicy.applies(
                (MinecraftServer) (Object) this);
        if (AshenSpanSpawnPreparationPolicy.addVanillaStartTicket(applies)) {
            chunks.addRegionTicket(type, pos, distance, value);
        }
    }

    @ModifyConstant(method = "prepareLevels", constant = @Constant(intValue = 441))
    private int pomkotsmechs$boundInitialGeneratedTarget(int vanillaTarget) {
        return AshenSpanSpawnPreparationPolicy.generatedTarget(
                AshenSpanSpawnPreparationPolicy.applies((MinecraftServer) (Object) this),
                vanillaTarget);
    }
}
