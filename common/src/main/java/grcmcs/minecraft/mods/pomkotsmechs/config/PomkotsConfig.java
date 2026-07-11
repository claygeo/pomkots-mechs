package grcmcs.minecraft.mods.pomkotsmechs.config;

import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

@Config(name = PomkotsMechs.MODID)
public class PomkotsConfig implements ConfigData {
    public boolean enableEntityBlockDestruction = false;
    public boolean enablePlayerVehicleBlockDestruction = true;
    public String nonDestructiveBlocks = "minecraft:bedrock,minecraft:structure_void,minecraft:structure_block";
    public boolean enableHudHealthBar = true;
    public boolean consumeBlocksWhenPlacing = true;
    public boolean dropItemsWhenDestroyBlock = false;
    // Upstream compat flag: true = legacy literal-"Level" NBT reads (broken
    // upgrade ladder, kept for old worlds); false = namespaced key, levels work.
    // Defaults FALSE here (unlike upstream): our pack ships this jar to server
    // AND clients, and a split default would desync displayed vs actual stats.
    public boolean enablePartsLevelCompatibility = false;
    // Treasure Cube debug: when true, opening red/purple cubes does NOT consume
    // the cube key (for testing). Production default is false (keys are consumed).
    public boolean debugModeEnabled = false;
}
