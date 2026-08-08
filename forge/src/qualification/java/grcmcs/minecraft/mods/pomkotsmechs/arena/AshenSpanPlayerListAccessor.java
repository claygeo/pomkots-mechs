package grcmcs.minecraft.mods.pomkotsmechs.qualification.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.List;
import java.util.UUID;

/** Qualification-only accessor used to register the synthetic pilot as online. */
@Mixin(PlayerList.class)
public interface AshenSpanPlayerListAccessor {
    @Accessor("players")
    List<ServerPlayer> ashenSpan$getPlayers();

    @Accessor("playersByUUID")
    Map<UUID, ServerPlayer> ashenSpan$getPlayersByUuid();
}
