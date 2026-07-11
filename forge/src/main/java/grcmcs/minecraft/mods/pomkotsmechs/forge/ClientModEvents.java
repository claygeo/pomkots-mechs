package grcmcs.minecraft.mods.pomkotsmechs.forge;

import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry;
import dev.architectury.registry.menu.MenuRegistry;
import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.client.gui.MechWorkbenchScreen;
import grcmcs.minecraft.mods.pomkotsmechs.client.gui.PartsWorkbenchScreen;
import grcmcs.minecraft.mods.pomkotsmechs.client.renderer.PomkotsCubeRenderer;
import grcmcs.minecraft.mods.pomkotsmechs.client.renderer.PomkotsCubeYellowRenderer;
import grcmcs.minecraft.mods.pomkotsmechs.client.renderer.PomkotsCubeRedRenderer;
import grcmcs.minecraft.mods.pomkotsmechs.client.renderer.PomkotsCubePurpleRenderer;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.PomkotsVehicle;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.Pmvc01Entity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = PomkotsMechs.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuRegistry.registerScreenFactory(
                    PomkotsMechs.MECH_WORKBENCH_GUI.get(), MechWorkbenchScreen::new
            );

            MenuRegistry.registerScreenFactory(
                    PomkotsMechs.PARTS_WORKBENCH_GUI.get(), PartsWorkbenchScreen::new
            );

            BlockEntityRendererRegistry.register(PomkotsMechs.POMKOTS_CUBE_BLOCK_ENTITY.get(), (context)->{
                return new PomkotsCubeRenderer(context);
            });
            BlockEntityRendererRegistry.register(PomkotsMechs.POMKOTS_CUBE_BLOCK_ENTITY_YELLOW.get(), (context)->{
                return new PomkotsCubeYellowRenderer(context);
            });
            BlockEntityRendererRegistry.register(PomkotsMechs.POMKOTS_CUBE_BLOCK_ENTITY_RED.get(), (context)->{
                return new PomkotsCubeRedRenderer(context);
            });
            BlockEntityRendererRegistry.register(PomkotsMechs.POMKOTS_CUBE_BLOCK_ENTITY_PURPLE.get(), (context)->{
                return new PomkotsCubePurpleRenderer(context);
            });
        });
    }

    @SubscribeEvent
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("hide_mech_hud", (gui, poseStack, partialTick, screenWidth, screenHeight) -> {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;

            if (player != null && player.getVehicle() instanceof Pmvc01Entity) {
                gui.leftHeight = 0; // 体力バーを非表示
                gui.rightHeight = 0; // 空腹バーを非表示
            }
        });
    }
}
