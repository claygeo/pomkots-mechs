package grcmcs.minecraft.mods.pomkotsmechs.forge;

import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
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

        if (client.level == null || client.player == null || !reload.available()) {
            if (ownsCurrent) {
                client.gameRenderer.shutdownEffect();
            }
            return;
        }

        // GameRenderer exposes one global post-chain slot. A chain that is not
        // ours always wins, including vanilla and Forge spectator shaders.
        if (current != null || spectatorShaderRequested(client) || failedEpoch == reload.epoch()) {
            return;
        }

        client.gameRenderer.loadEffect(EFFECT);
        if (!isArenaEffect(client.gameRenderer.currentEffect())) {
            // loadEffect logs parse and IO errors itself. Latching prevents the
            // same malformed resource from producing another warning each tick.
            failedEpoch = reload.epoch();
        }
    }

    private static boolean isArenaEffect(PostChain effect) {
        return effect != null && EFFECT.toString().equals(effect.getName());
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
}
