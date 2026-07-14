package grcmcs.minecraft.mods.pomkotsmechs.forge;

import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Spider;
import net.minecraftforge.client.EntitySpectatorShaderManager;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the optional Arena Grade post chain without interfering with vanilla or
 * modded spectator effects. The effect JSON intentionally lives only in an
 * external resource pack, making pack presence the feature switch.
 */
final class ArenaGradeController {
    static final ResourceLocation EFFECT = PomkotsMechs.id("shaders/post/arena_grade.json");

    private static final AtomicReference<ReloadState> RELOAD_STATE =
            new AtomicReference<>(new ReloadState(0, false));
    private static int failedEpoch = Integer.MIN_VALUE;
    private static ReportedState reportedState;

    private ArenaGradeController() {
    }

    static void onResourceReload(ResourceManager resourceManager) {
        boolean available = resourceManager.getResource(EFFECT).isPresent();
        RELOAD_STATE.updateAndGet(previous -> new ReloadState(previous.epoch() + 1, available));
    }

    static void reconcile(Minecraft client) {
        ReloadState reload = RELOAD_STATE.get();
        PostChain current = client.gameRenderer.currentEffect();
        boolean ownsCurrent = isArenaEffect(current);

        if (client.level == null || client.player == null) {
            if (ownsCurrent) {
                client.gameRenderer.shutdownEffect();
                current = null;
            }
            if (reload.available()) {
                report(reload, RuntimeStatus.BLOCKED, current, "no_world");
            } else {
                reportUnavailable(reload, current);
            }
            return;
        }

        if (!reload.available()) {
            if (ownsCurrent) {
                client.gameRenderer.shutdownEffect();
                current = null;
            }
            reportUnavailable(reload, current);
            return;
        }

        if (ownsCurrent) {
            boolean rendering = effectActive(client);
            report(
                    reload,
                    rendering ? RuntimeStatus.ACTIVE : RuntimeStatus.BLOCKED,
                    current,
                    rendering ? "owned" : "render_disabled"
            );
            return;
        }

        // GameRenderer exposes one global post-chain slot. A chain that is not
        // ours always wins, including vanilla and Forge spectator shaders.
        if (failedEpoch == reload.epoch()) {
            report(reload, RuntimeStatus.FAILED, current, "load_failed");
            return;
        }
        if (current != null) {
            report(reload, RuntimeStatus.BLOCKED, current, "foreign_effect");
            return;
        }
        if (spectatorShaderRequested(client)) {
            report(reload, RuntimeStatus.BLOCKED, current, "spectator_requested");
            return;
        }

        client.gameRenderer.loadEffect(EFFECT);
        current = client.gameRenderer.currentEffect();
        if (!isArenaEffect(current)) {
            // loadEffect logs parse and IO errors itself. Latching prevents the
            // same malformed resource from producing another warning each tick.
            failedEpoch = reload.epoch();
            report(reload, RuntimeStatus.FAILED, current, "load_failed");
            return;
        }
        boolean rendering = effectActive(client);
        report(
                reload,
                rendering ? RuntimeStatus.ACTIVE : RuntimeStatus.BLOCKED,
                current,
                rendering ? "owned" : "render_disabled"
        );
    }

    private static boolean isArenaEffect(PostChain effect) {
        return effect != null && EFFECT.toString().equals(effect.getName());
    }

    private static boolean effectActive(Minecraft client) {
        return ((GameRendererAccessor) client.gameRenderer).pomkotsmechs$isEffectActive();
    }

    private static void reportUnavailable(ReloadState reload, PostChain current) {
        if (current != null) {
            report(reload, RuntimeStatus.BLOCKED, current, "foreign_effect");
        } else if (reportedState != null) {
            // Do not emit anything for a normal non-Arena profile. If a prior
            // Arena state existed, this terminal transition prevents a stale
            // ACTIVE line from surviving a pack removal or failed reload.
            report(reload, RuntimeStatus.BLOCKED, null, "resource_unavailable");
        }
    }

    private static void report(
            ReloadState reload,
            RuntimeStatus status,
            PostChain effect,
            String reason
    ) {
        String effectName = effect != null ? effect.getName() : "none";
        ReportedState next = new ReportedState(reload.epoch(), status, effectName, reason);
        if (next.equals(reportedState)) {
            return;
        }
        reportedState = next;
        String marker = "MECH_ARENA_ARENA_GRADE status={} epoch={} current={} reason={}";
        if (status == RuntimeStatus.FAILED) {
            PomkotsMechs.LOGGER.warn(marker, status, reload.epoch(), effectName, reason);
        } else {
            PomkotsMechs.LOGGER.info(marker, status, reload.epoch(), effectName, reason);
        }
    }

    private static boolean spectatorShaderRequested(Minecraft client) {
        // Vanilla only requests an entity spectator shader in first person.
        // Keeping this fallback perspective-aware lets Arena Grade return in
        // either third-person view after F5 clears the spectator chain.
        if (!client.options.getCameraType().isFirstPerson()) {
            return false;
        }
        Entity camera = client.getCameraEntity();
        if (camera == null) {
            return false;
        }

        return camera instanceof Creeper
                || camera instanceof Spider
                || camera instanceof EnderMan
                || EntitySpectatorShaderManager.get(camera.getType()) != null;
    }

    private record ReloadState(int epoch, boolean available) {
    }

    private record ReportedState(int epoch, RuntimeStatus status, String current, String reason) {
    }

    private enum RuntimeStatus {
        BLOCKED,
        FAILED,
        ACTIVE
    }
}
