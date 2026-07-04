package grcmcs.minecraft.mods.pomkotsmechs.entity.projectile;

import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.PomkotsVehicleBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

public abstract class PomkotsThrowableProjectile extends ThrowableProjectile {
    private LivingEntity shooter;

    public PomkotsThrowableProjectile(EntityType<? extends ThrowableProjectile> entityType, LivingEntity shooter, Level world) {
        super(entityType, world);
        this.shooter = shooter;

        // Attribute damage to the pilot when a mech is driven, else to the shooter entity itself,
        // so vanilla canHarmPlayer/pvp/team checks and kill credit engage (getOwner() was always null before).
        if (shooter instanceof PomkotsVehicleBase vehicle && vehicle.getDrivingPassenger() != null) {
            this.setOwner(vehicle.getDrivingPassenger());
        } else if (shooter != null) {
            this.setOwner(shooter);
        }
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        if (!super.canHitEntity(entity)) {
            return false;
        }
        // Owner is the pilot, not the firing mech, so vanilla only excludes the pilot; exclude the mech and
        // its passengers too so a projectile does not immediately self-collide with the vehicle it was fired from.
        if (shooter != null && (entity == shooter || entity.getVehicle() == shooter || shooter.hasPassenger(entity))) {
            return false;
        }
        return true;
    }

    public void onHitEntityPublic(Entity entity) {
        this.onHitEntity(new EntityHitResult(entity));
    }

    public float getHitDamage() {
        return 0;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean ignoreExplosion() {
        return true;
    }

    public LivingEntity getShooter() {
        return this.shooter;
    }
}
