package grcmcs.minecraft.mods.pomkotsmechs.arena;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * Builds and registers the {@code /arena} command tree. All handlers live in
 * {@link ArenaManager}; this class only wires Brigadier nodes to them.
 */
public final class ArenaCommands {
    private ArenaCommands() {
    }

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) ->
                dispatcher.register(build()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("arena")
                // --- player commands (no permission) ---
                .then(Commands.literal("join")
                        .executes(ctx -> ArenaManager.commandJoin(ctx.getSource(), ArenaManager.DEFAULT_MECH))
                        .then(Commands.argument("mech", StringArgumentType.word())
                                .executes(ctx -> ArenaManager.commandJoin(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "mech")))))
                .then(Commands.literal("leave")
                        .executes(ctx -> ArenaManager.commandLeave(ctx.getSource())))
                .then(Commands.literal("status")
                        .executes(ctx -> ArenaManager.commandStatus(ctx.getSource())))
                // --- admin commands (permission level 2) ---
                .then(Commands.literal("setlobby")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> ArenaManager.commandSetLobby(ctx.getSource())))
                .then(Commands.literal("addpad")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> ArenaManager.commandAddPad(ctx.getSource())))
                .then(Commands.literal("clearpads")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> ArenaManager.commandClearPads(ctx.getSource())))
                .then(Commands.literal("info")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> ArenaManager.commandInfo(ctx.getSource())))
                .then(Commands.literal("start")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> ArenaManager.commandStart(ctx.getSource())))
                .then(Commands.literal("stop")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> ArenaManager.commandStop(ctx.getSource())))
                .then(Commands.literal("mode")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("duel")
                                .executes(ctx -> ArenaManager.commandMode(ctx.getSource(), Mode.DUEL)))
                        .then(Commands.literal("royale")
                                .executes(ctx -> ArenaManager.commandMode(ctx.getSource(), Mode.ROYALE))))
                .then(Commands.literal("royale")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("setcenter")
                                .executes(ctx -> ArenaManager.commandRoyaleSetCenter(ctx.getSource())))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("radius", IntegerArgumentType.integer(50, 1000))
                                        .executes(ctx -> ArenaManager.commandRoyaleRadius(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius")))))
                        .then(Commands.literal("mechs")
                                .then(Commands.argument("count", IntegerArgumentType.integer(2, 64))
                                        .executes(ctx -> ArenaManager.commandRoyaleMechs(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count"))))));
    }
}
