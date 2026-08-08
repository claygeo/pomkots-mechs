package grcmcs.minecraft.mods.pomkotsmechs.qualification;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entrypoint for the unshipped qualification artifact.  Loading the artifact
 * alone is inert: mission automation is constructed only when the external
 * runner supplies the explicit JVM property.
 */
@Mod(AshenSpanQualificationMod.MOD_ID)
public final class AshenSpanQualificationMod {
    public static final String MOD_ID = "ashen_span_qualification";
    public static final String ENABLE_PROPERTY = "ashenSpan.qualification";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AshenSpanQualificationMod() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            LOGGER.warn("ASHEN_SPAN_QUALIFICATION_INERT property {} is not true", ENABLE_PROPERTY);
            return;
        }
        LOGGER.info("ASHEN_SPAN_QUALIFICATION_ARMED");
        MinecraftForge.EVENT_BUS.register(new AshenSpanAcceptanceProbe());
    }
}
