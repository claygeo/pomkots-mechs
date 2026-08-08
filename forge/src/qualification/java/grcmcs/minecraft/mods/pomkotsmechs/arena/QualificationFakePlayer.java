package grcmcs.minecraft.mods.pomkotsmechs.qualification;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraftforge.common.util.FakePlayer;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.Set;

/**
 * FakePlayer's Forge network handler intentionally ignores teleports.  The
 * acceptance pilot needs the server-side movement effect a real connection
 * would apply so production restore distance checks can complete.  No packet,
 * client, input, or production pilot behavior is simulated here.
 */
final class QualificationFakePlayer extends FakePlayer {
    private static final Logger LOGGER = LogUtils.getLogger();
    QualificationFakePlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public void sendSystemMessage(Component message) {
        LOGGER.info("ASHEN_SPAN_QUALIFICATION_COMMAND_MESSAGE {}", message.getString());
    }

    @Override
    public void teleportTo(ServerLevel level, double x, double y, double z,
                           float yaw, float pitch) {
        if (serverLevel() == level) {
            stopRiding();
            moveTo(x, y, z, yaw, pitch);
            setYHeadRot(yaw);
            return;
        }
        super.teleportTo(level, x, y, z, yaw, pitch);
    }

    @Override
    public boolean teleportTo(ServerLevel level, double x, double y, double z,
                              Set<RelativeMovement> relative, float yaw, float pitch) {
        if (serverLevel() == level) {
            double targetX = relative.contains(RelativeMovement.X) ? getX() + x : x;
            double targetY = relative.contains(RelativeMovement.Y) ? getY() + y : y;
            double targetZ = relative.contains(RelativeMovement.Z) ? getZ() + z : z;
            float targetYaw = relative.contains(RelativeMovement.Y_ROT) ? getYRot() + yaw : yaw;
            float targetPitch = relative.contains(RelativeMovement.X_ROT) ? getXRot() + pitch : pitch;
            stopRiding();
            moveTo(targetX, targetY, targetZ, targetYaw, targetPitch);
            setYHeadRot(targetYaw);
            return true;
        }
        return super.teleportTo(level, x, y, z, relative, yaw, pitch);
    }
}
