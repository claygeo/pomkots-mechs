package grcmcs.minecraft.mods.pomkotsmechs.arena;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            dispatcher.register(build());
            // Standalone control-card command (no permission), re-sends the card a
            // player first sees on mount.
            dispatcher.register(Commands.literal("mechhelp")
                    .executes(ctx -> ArenaManager.commandMechHelp(ctx.getSource())));
        });
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
                // --- solo combat (no permission; the caller owns their run) ---
                .then(Commands.literal("solo")
                        .then(Commands.literal("start")
                                .executes(ctx -> ArenaManager.commandSoloStart(ctx.getSource(), 1, null))
                                .then(Commands.argument("build", IntegerArgumentType.integer(1, GarageFleet.size()))
                                        .executes(ctx -> ArenaManager.commandSoloStart(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "build"), null))
                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(ctx -> ArenaManager.commandSoloStart(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "build"),
                                                        LongArgumentType.getLong(ctx, "seed"))))))
                        .then(Commands.literal("retry")
                                .executes(ctx -> ArenaManager.commandSoloRetry(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> ArenaManager.commandSoloStatus(ctx.getSource())))
                        .then(Commands.literal("validate")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> ArenaManager.commandSoloValidate(ctx.getSource())))
                        .then(Commands.literal("stop")
                                .executes(ctx -> ArenaManager.commandSoloStop(ctx.getSource())))
                        .then(Commands.literal("debug")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("next")
                                        .executes(ctx -> ArenaManager.commandSoloDebugNext(ctx.getSource())))))
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
                .then(Commands.literal("garage")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("build", IntegerArgumentType.integer(0))
                                .executes(ctx -> ArenaManager.commandGarage(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "build")))))
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
                                                IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(Commands.literal("minplayers")
                                .then(Commands.argument("min", IntegerArgumentType.integer(2, 64))
                                        .executes(ctx -> ArenaManager.commandRoyaleMinPlayers(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "min")))))
                        .then(Commands.literal("startat")
                                .then(Commands.argument("startat", IntegerArgumentType.integer(2, 64))
                                        .executes(ctx -> ArenaManager.commandRoyaleStartAt(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "startat")))))
                        .then(Commands.literal("grace")
                                .then(Commands.argument("ticks", IntegerArgumentType.integer(200, 2400))
                                        .executes(ctx -> ArenaManager.commandRoyaleGrace(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "ticks")))))
                        .then(Commands.literal("pve")
                                .then(Commands.literal("off")
                                        .executes(ctx -> ArenaManager.commandRoyalePve(ctx.getSource(), RoyalePve.OFF)))
                                .then(Commands.literal("light")
                                        .executes(ctx -> ArenaManager.commandRoyalePve(ctx.getSource(), RoyalePve.LIGHT)))
                                .then(Commands.literal("heavy")
                                        .executes(ctx -> ArenaManager.commandRoyalePve(ctx.getSource(), RoyalePve.HEAVY)))));
    }
}
