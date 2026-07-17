package grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom;

import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaRivalController;
import grcmcs.minecraft.mods.pomkotsmechs.arena.GarageFleet;
import grcmcs.minecraft.mods.pomkotsmechs.client.input.DriverInput;
import grcmcs.minecraft.mods.pomkotsmechs.client.input.UserInteractionManager;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.PomkotsVehicleBase;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Mission-owned, server-controlled PMVC used only for Gatekeeper R-01. */
public final class ArenaRivalPmvc01Entity extends Pmvc01Entity {
    private static final String NBT_ACTIVE = "AshenSpanAutonomousActive";
    private static final String NBT_TARGET = "AshenSpanTarget";
    private static final String NBT_SEED = "AshenSpanControllerSeed";

    private static final EntityDataAccessor<Boolean> AUTONOMOUS_ACTIVE =
            SynchedEntityData.defineId(ArenaRivalPmvc01Entity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> AUTONOMOUS_TACTIC =
            SynchedEntityData.defineId(ArenaRivalPmvc01Entity.class, EntityDataSerializers.INT);

    private final ArenaRivalController controller = new ArenaRivalController();
    private UUID targetUuid;
    private long controllerSeed;
    private float autonomousForward;
    private float autonomousStrafe;
    private float autonomousYaw;
    private float autonomousPitch;
    private boolean releasing;
    private boolean controlsReleased;

    public ArenaRivalPmvc01Entity(EntityType<? extends LivingEntity> entityType, Level level) {
        super(entityType, level);
    }

    /** Applies the exact authored assembly after construction and before world admission. */
    public void configureLoadout() {
        GarageFleet.applyGatekeeperLoadout(this);
        this.setCustomName(Component.literal("GATEKEEPER R-01").withStyle(ChatFormatting.RED));
        this.setCustomNameVisible(true);
    }

    public void activateAutonomy(LivingEntity activePlayerMech, long missionSeed) {
        if (activePlayerMech == null) {
            throw new IllegalArgumentException("activePlayerMech is required");
        }
        if (this.getHeadParts().isEmpty()) {
            configureLoadout();
        }
        this.targetUuid = activePlayerMech.getUUID();
        this.controllerSeed = missionSeed;
        this.controller.activate(missionSeed);
        this.entityData.set(AUTONOMOUS_TACTIC, ArenaRivalController.Tactic.INACTIVE.ordinal());
        this.entityData.set(AUTONOMOUS_ACTIVE, true);
        releaseAllControls();
    }

    public void deactivateAutonomy() {
        this.entityData.set(AUTONOMOUS_ACTIVE, false);
        this.entityData.set(AUTONOMOUS_TACTIC, ArenaRivalController.Tactic.INACTIVE.ordinal());
        this.targetUuid = null;
        this.controller.reset();
        releaseAllControls();
        discardOwnedProjectiles();
    }

    /** Relocation is a full control boundary; the director reactivates after moving. */
    public void prepareForRelocation() {
        deactivateAutonomy();
        this.setDeltaMovement(Vec3.ZERO);
    }

    public boolean isAutonomousActive() {
        return this.entityData.get(AUTONOMOUS_ACTIVE);
    }

    public ArenaRivalController.Tactic getAutonomousTactic() {
        int value = this.entityData.get(AUTONOMOUS_TACTIC);
        ArenaRivalController.Tactic[] values = ArenaRivalController.Tactic.values();
        return value >= 0 && value < values.length ? values[value] : ArenaRivalController.Tactic.HOLD;
    }

    /** True only when movement, weapon edges, evasion, and target locks are all released. */
    public boolean areAutonomousControlsReleased() {
        return controlsReleased;
    }

    @Nullable
    public UUID getTargetUuid() {
        return targetUuid;
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide && isAutonomousActive() && this.isAlive()) {
            tickAutonomousControl();
        }
        super.tick();
    }

    private void tickAutonomousControl() {
        LivingEntity target = resolveTarget();
        boolean valid = isValidTarget(target);
        double distance = valid ? Math.sqrt(this.distanceToSqr(target)) : Double.POSITIVE_INFINITY;
        double healthRatio = this.getMaxHealth() <= 0.0F ? 0.0D : this.getHealth() / this.getMaxHealth();

        ArenaRivalController.Decision decision = controller.tick(distance, healthRatio, valid);
        this.entityData.set(AUTONOMOUS_TACTIC, decision.tactic().ordinal());

        if (!valid) {
            haltHorizontalMotion();
            releaseAllControls();
            return;
        }

        this.lockTargets.lockTargetHard(target);
        aimAt(target);

        Vec3 direction = localMovementDirection(decision.forward(), decision.strafe());
        boolean supported = direction.lengthSqr() < 1.0E-8D || hasSafeFootingFor(direction, 2.0D);
        Vec3 momentum = new Vec3(this.getDeltaMovement().x, 0.0D, this.getDeltaMovement().z);
        if (momentum.lengthSqr() > 0.0025D) {
            supported &= hasSafeFootingFor(momentum, Math.min(3.0D, 1.0D + momentum.length()));
        }
        boolean evasionSupported = !decision.evade() || hasSafeFootingFor(direction, 8.0D);
        if (!supported || !evasionSupported) {
            haltHorizontalMotion();
        }
        decision = decision.withSafety(supported, evasionSupported);
        applyDecision(decision);
    }

    @Nullable
    private LivingEntity resolveTarget() {
        if (targetUuid == null || !(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        Entity entity = serverLevel.getEntity(targetUuid);
        return entity instanceof LivingEntity living ? living : null;
    }

    private boolean isValidTarget(@Nullable LivingEntity target) {
        boolean activePlayerMech = false;
        if (target instanceof PomkotsVehicleBase mech) {
            LivingEntity passenger = mech.getDrivingPassenger();
            activePlayerMech = passenger instanceof Player && passenger.isAlive();
        }
        return ArenaRivalController.targetContract(
                target != null && target.isAlive(),
                target != null && target.level() == this.level(),
                activePlayerMech,
                target == null ? Double.POSITIVE_INFINITY : this.distanceToSqr(target),
                target != null && this.hasLineOfSight(target));
    }

    private void aimAt(LivingEntity target) {
        Vec3 delta = target.getBoundingBox().getCenter().subtract(this.getBoundingBox().getCenter());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        this.autonomousYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        this.autonomousPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
    }

    private Vec3 localMovementDirection(float forward, float strafe) {
        Vec3 local = new Vec3(strafe, 0.0D, forward);
        if (local.lengthSqr() < 1.0E-8D) {
            return Vec3.ZERO;
        }
        return local.normalize().yRot((float) Math.toRadians(-this.autonomousYaw));
    }

    private void applyDecision(ArenaRivalController.Decision decision) {
        if (decision.releasedForTransition()) {
            releaseAllControls();
        }
        this.autonomousForward = decision.forward();
        this.autonomousStrafe = decision.strafe();

        short input = 0;
        if (autonomousForward > 0.0F) {
            input |= UserInteractionManager.Keys.FORWARD.getKeyID();
        } else if (autonomousForward < 0.0F) {
            input |= UserInteractionManager.Keys.BACK.getKeyID();
        }
        if (autonomousStrafe > 0.0F) {
            input |= UserInteractionManager.Keys.LEFT.getKeyID();
        } else if (autonomousStrafe < 0.0F) {
            input |= UserInteractionManager.Keys.RIGHT.getKeyID();
        }
        if (decision.evade()) {
            input |= UserInteractionManager.Keys.EVASION.getKeyID();
        }
        input |= switch (decision.weapon()) {
            case RIGHT_HAND_RIFLE -> UserInteractionManager.Keys.WEAPON_ARM_R.getKeyID();
            case LEFT_HAND_SMG -> UserInteractionManager.Keys.WEAPON_ARM_L.getKeyID();
            case RIGHT_SHOULDER_SUWA -> UserInteractionManager.Keys.WEAPON_SHOULDER_R.getKeyID();
            case NONE -> 0;
        };
        this.setDriverInput(new DriverInput(input));
        this.controlsReleased = input == 0
                && autonomousForward == 0.0F
                && autonomousStrafe == 0.0F;
    }

    /**
     * Full-footprint collision/support probe used for ordinary movement and the longer
     * evasion look-ahead. It reads loaded chunks only and never asks the level to load one.
     */
    public boolean hasSafeFootingFor(Vec3 worldDirection, double distance) {
        if (distance < 0.0D || distance > 10.0D) {
            return false;
        }
        Vec3 direction = new Vec3(worldDirection.x, 0.0D, worldDirection.z);
        if (direction.lengthSqr() < 1.0E-8D) {
            return hasSupport(this.getBoundingBox());
        }
        direction = direction.normalize();
        int samples = Math.max(1, (int) Math.ceil(distance));
        for (int i = 0; i <= samples; i++) {
            double offset = Math.min(distance, i);
            AABB moved = this.getBoundingBox().move(direction.scale(offset));
            if (!this.level().noCollision(this, moved) || !hasSupport(moved)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSupport(AABB box) {
        double inset = Math.min(0.40D, Math.min(box.getXsize(), box.getZsize()) * 0.20D);
        double[] xs = {box.minX + inset, box.maxX - inset};
        double[] zs = {box.minZ + inset, box.maxZ - inset};
        for (double x : xs) {
            for (double z : zs) {
                BlockPos supportPos = BlockPos.containing(x, box.minY - 0.05D, z);
                if (!this.level().hasChunkAt(supportPos)) {
                    return false;
                }
                var shape = this.level().getBlockState(supportPos)
                        .getCollisionShape(this.level(), supportPos);
                if (shape.isEmpty()) {
                    return false;
                }
                double top = supportPos.getY() + shape.max(Direction.Axis.Y);
                if (top < box.minY - 0.75D || top > box.minY + 0.20D) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Clears movement, every weapon edge, boost/evasion state, and target locks. */
    public void releaseAllControls() {
        if (releasing) {
            return;
        }
        releasing = true;
        try {
            // This boundary is deliberately idempotent. Even if the last input was
            // already zero, action state or target locks may have changed since then;
            // every transition/deactivation/removal must clear them unconditionally.
            this.autonomousForward = 0.0F;
            this.autonomousStrafe = 0.0F;
            haltHorizontalMotion();
            this.setDriverInput(new DriverInput((short) 0));
            this.actionController.reset();
            this.lockTargets.clearLockTargets();
            this.controlsReleased = true;
        } finally {
            releasing = false;
        }
    }

    private void haltHorizontalMotion() {
        Vec3 motion = this.getDeltaMovement();
        this.setDeltaMovement(0.0D, motion.y, 0.0D);
    }

    public void discardOwnedProjectiles() {
        if (this.level().isClientSide) {
            return;
        }
        for (Projectile projectile : this.level().getEntitiesOfClass(Projectile.class,
                this.getBoundingBox().inflate(ArenaRivalController.MAX_TARGET_DISTANCE + 16.0D),
                projectile -> projectile.getOwner() == this)) {
            projectile.discard();
        }
    }

    @Override
    protected boolean hasControlAuthority() {
        return isAutonomousActive();
    }

    @Override
    protected boolean hasMotionControlAuthority() {
        return isAutonomousActive() && !this.level().isClientSide;
    }

    @Override
    protected float getControlYaw() {
        return autonomousYaw;
    }

    @Override
    protected float getControlPitch() {
        return autonomousPitch;
    }

    @Override
    protected float getControlSideways() {
        return autonomousStrafe;
    }

    @Override
    protected float getControlForward() {
        return autonomousForward;
    }

    @Override
    protected boolean hasAnimationControl() {
        return isAutonomousActive();
    }

    @Override
    protected void syncAllParameter2Client(boolean updateHealth) {
        super.syncAllParameter2Client(updateHealth);
        var maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(GarageFleet.GATEKEEPER_MAX_HEALTH);
            this.setHealth(updateHealth
                    ? GarageFleet.GATEKEEPER_MAX_HEALTH
                    : Math.min(this.getHealth(), GarageFleet.GATEKEEPER_MAX_HEALTH));
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return InteractionResult.FAIL;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return false;
    }

    @Override
    public void openCustomInventoryScreen(Player player) {
        // Mission enemy: never expose the workbench/inventory UI.
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return null;
    }

    @Override
    protected boolean shouldDropLoadout(Entity.RemovalReason removalReason) {
        return false;
    }

    @Override
    protected boolean shouldSpawnDeathExplosion() {
        return false;
    }

    @Override
    public boolean shouldShowName() {
        return true;
    }

    @Override
    public void die(DamageSource damageSource) {
        deactivateAutonomy();
        super.die(damageSource);
    }

    @Override
    public void remove(RemovalReason reason) {
        releaseAllControls();
        discardOwnedProjectiles();
        super.remove(reason);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(AUTONOMOUS_ACTIVE, false);
        this.entityData.define(AUTONOMOUS_TACTIC, ArenaRivalController.Tactic.INACTIVE.ordinal());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(NBT_ACTIVE, isAutonomousActive());
        tag.putLong(NBT_SEED, controllerSeed);
        if (targetUuid != null) {
            tag.putUUID(NBT_TARGET, targetUuid);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.controllerSeed = tag.getLong(NBT_SEED);
        this.targetUuid = tag.hasUUID(NBT_TARGET) ? tag.getUUID(NBT_TARGET) : null;
        boolean active = tag.getBoolean(NBT_ACTIVE);
        this.entityData.set(AUTONOMOUS_ACTIVE, active);
        if (active) {
            this.controller.activate(controllerSeed);
        } else {
            this.controller.reset();
        }
    }
}
