package grcmcs.minecraft.mods.pomkotsmechs.arena;

import dev.architectury.registry.registries.RegistrySupplier;
import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.Pmvc01Entity;
import grcmcs.minecraft.mods.pomkotsmechs.items.parts.BasePartsItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The ROYALE "garage fleet": a fixed stable of preset {@link Pmvc01Entity custom-mech}
 * loadouts that the royale scatter deploys as unowned loot in place of the three plain
 * fixed-frame mechs (pmv01/pmv01b/pmv02).
 *
 * <p>Each {@link Loadout} is a complete, drivable build: a full frame (head/body/arm/legs),
 * a generator + booster, a fuel supply, and a weapon set with its matching ammo already
 * loaded. The scatter cycles through the fleet by index so distinct builds end up spread
 * across the city — a player who finds a mech gets a coherent archetype (brawler, tank,
 * missile boat, duelist, …) rather than one of three identical stock frames.
 *
 * <p>All parts are given at their default (max) level, matching how a creative-given part
 * behaves ({@link grcmcs.minecraft.mods.pomkotsmechs.items.parts.BasePartsItem#getLevel}),
 * so every build fights at full stats. Fuel only depletes while a mech is being ridden
 * ({@link grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.PomkotsVehicleBase#travel}
 * gates on {@code isVehicle()}), so a mech can sit as loot indefinitely and still start
 * with a full tank the moment a fighter mounts it.
 *
 * <p>This class is server-only and stateless; it builds and configures the entity but never
 * adds it to the world — {@code ArenaManager.scatterMechs} owns positioning, tagging, and
 * spawning so the mechs stay inside the existing match-tag ADD guard / stray-sweep flow.
 */
public final class GarageFleet {
    private GarageFleet() {
    }

    // Magazines loaded per armed weapon slot. Clamped per-item to its own max stack size
    // (e.g. the gigantic-missile magazine only stacks to 1), so this is an upper bound.
    private static final int AMMO_MAGAZINES = 6;
    // Fuel pellets per mech. Sized so the thirstiest generator (Chiba: ~10s of drive time
    // per pellet) still outlasts a full 8-minute ACTIVE match on a single tank.
    private static final int FUEL_PELLETS = 48;

    /**
     * The stable, in scatter-cycle order. Frames span light -> super-heavy and the weapon
     * mixes (ballistic / missile / gatling / melee) are deliberately distinct so adjacent
     * scatter points read as different machines.
     */
    private static final List<Loadout> FLEET = List.of(
            // 1. VANGUARD - balanced mid-weight rifleman with a blade sidearm and homing missiles.
            Loadout.named("Vanguard")
                    .frame(PomkotsMechs.ALTAIR_HEAD, PomkotsMechs.ALTAIR_BODY,
                            PomkotsMechs.ALTAIR_ARM, PomkotsMechs.ALTAIR_LEGS)
                    .power(PomkotsMechs.SHIGA_GENERATOR, PomkotsMechs.NARITA_BOOSTER)
                    .rightHand(PomkotsMechs.SHAKUJI_WEAPON, PomkotsMechs.RIFLE_MAGAZINE)
                    .leftHand(PomkotsMechs.TSURUGI_WEAPON, null) // blade: melee, no ammo
                    .rightShoulder(PomkotsMechs.KAWASEMI_WEAPON, PomkotsMechs.MISSILE_MAGAZINE)
                    .extensions(PomkotsMechs.SOFT_LOCK_CIRCUIT, null),

            // 2. SIEGE - tank frame, area-denial gatling plus grenade and a gigantic missile.
            Loadout.named("Siege")
                    .frame(PomkotsMechs.ALDEBARAN_HEAD, PomkotsMechs.ALDEBARAN_BODY,
                            PomkotsMechs.ALDEBARAN_ARM, PomkotsMechs.ALDEBARAN_LEGS)
                    .power(PomkotsMechs.CHIBA_GENERATOR, PomkotsMechs.KANSAI_BOOSTER)
                    .rightHand(PomkotsMechs.KASUMI_WEAPON, PomkotsMechs.GATLING_MAGAZINE)
                    .rightShoulder(PomkotsMechs.BIWA_WEAPON, PomkotsMechs.GRENADE_MAGAZINE)
                    .leftShoulder(PomkotsMechs.DODO_WEAPON, PomkotsMechs.MISSILE_LARGE_MAGAZINE)
                    .extensions(PomkotsMechs.HARD_LOCK_CIRCUIT, null),

            // 3. SKIRMISHER - light, fast, hovering shotgunner with a pile-bunker finisher.
            Loadout.named("Skirmisher")
                    .frame(PomkotsMechs.DENEB_HEAD, PomkotsMechs.DENEB_BODY,
                            PomkotsMechs.DENEB_ARM, PomkotsMechs.DENEB_LEGS)
                    .power(PomkotsMechs.SAGA_GENERATOR, PomkotsMechs.HANEDA_BOOSTER)
                    .rightHand(PomkotsMechs.SENZOKU_WEAPON, PomkotsMechs.SHOTGUN_MAGAZINE)
                    .leftHand(PomkotsMechs.KAGENOBU_WEAPON, null) // pile bunker: melee, no ammo
                    .extensions(PomkotsMechs.SOFT_LOCK_CIRCUIT, PomkotsMechs.HOVER_UNIT),

            // 4. ARTILLERY - heavy missile boat: dual multi-missile shoulders plus a backup rifle.
            Loadout.named("Artillery")
                    .frame(PomkotsMechs.VEGA_HEAD, PomkotsMechs.VEGA_BODY,
                            PomkotsMechs.VEGA_ARM, PomkotsMechs.VEGA_LEGS)
                    .power(PomkotsMechs.CHIBA_GENERATOR, PomkotsMechs.NARITA_BOOSTER)
                    .rightHand(PomkotsMechs.SHAKUJI_WEAPON, PomkotsMechs.RIFLE_MAGAZINE)
                    .rightShoulder(PomkotsMechs.MUKUDORI_WEAPON, PomkotsMechs.MISSILE_MAGAZINE)
                    .leftShoulder(PomkotsMechs.NOSURI_WEAPON, PomkotsMechs.MISSILE_MAGAZINE)
                    .extensions(PomkotsMechs.HARD_LOCK_CIRCUIT, null),

            // 5. DUELIST - reverse-joint high-jump melee bruiser: beam rifle plus blade, hover-capable.
            Loadout.named("Duelist")
                    .frame(PomkotsMechs.SIRIUS_HEAD, PomkotsMechs.SIRIUS_BODY,
                            PomkotsMechs.SIRIUS_ARM, PomkotsMechs.SIRIUS_LEGS)
                    .power(PomkotsMechs.SAGA_GENERATOR, PomkotsMechs.HANEDA_BOOSTER)
                    .rightHand(PomkotsMechs.MASHU_WEAPON, null) // beam rifle: melee-style, no ammo
                    .leftHand(PomkotsMechs.TSURUGI_WEAPON, null) // blade: melee, no ammo
                    .extensions(PomkotsMechs.SOFT_LOCK_CIRCUIT, PomkotsMechs.HOVER_UNIT),

            // 6. TROOPER - super-heavy all-rounder: SMG, lance, and a shoulder gatling.
            Loadout.named("Trooper")
                    .frame(PomkotsMechs.MUKNVALI_HEAD, PomkotsMechs.MUKNVALI_BODY,
                            PomkotsMechs.MUKNVALI_ARM, PomkotsMechs.MUKNVALI_LEGS)
                    .power(PomkotsMechs.CHIBA_GENERATOR, PomkotsMechs.KANSAI_BOOSTER)
                    .rightHand(PomkotsMechs.SHINOBAZU_WEAPON, PomkotsMechs.MACHINE_GUN_MAGAZINE)
                    .leftHand(PomkotsMechs.GASSAN_WEAPON, null) // lance: melee, no ammo
                    .rightShoulder(PomkotsMechs.SUWA_WEAPON, PomkotsMechs.GATLING_MAGAZINE)
                    .extensions(PomkotsMechs.HARD_LOCK_CIRCUIT, null));

    /** Number of distinct builds in the fleet (scatter cycles modulo this). */
    public static int size() {
        return FLEET.size();
    }

    /** Human-readable archetype name for the build at {@code index} (scatter-cycled). */
    public static String name(int index) {
        return FLEET.get(Math.floorMod(index, FLEET.size())).name;
    }

    /**
     * Builds a fully-assembled, unowned custom mech for the fleet slot at {@code index}
     * (cycled modulo the fleet size). The entity is configured but NOT added to the world:
     * the caller positions, tags, and spawns it. Returns {@code null} only if the custom-mech
     * entity type fails to instantiate (registry-level failure), which the caller skips.
     */
    @Nullable
    public static LivingEntity build(ServerLevel level, int index) {
        Entity created = PomkotsMechs.PMVC01.get().create(level);
        if (!(created instanceof Pmvc01Entity mech)) {
            if (created != null) {
                created.discard();
            }
            return null;
        }
        FLEET.get(Math.floorMod(index, FLEET.size())).applyTo(mech);
        return mech;
    }

    /**
     * Performs the one-shot authored service reset for a selected Garage Fleet build.
     * Unlike scatter selection, invalid indices fail closed instead of cycling.
     */
    public static boolean service(@Nullable Pmvc01Entity mech, int zeroBasedBuild) {
        if (mech == null || zeroBasedBuild < 0 || zeroBasedBuild >= FLEET.size()) {
            return false;
        }
        mech.clearContent();
        FLEET.get(zeroBasedBuild).writeInventoryTo(mech);
        mech.resetForArenaService();
        return true;
    }

    public static final int GATEKEEPER_MAX_HEALTH = 400;
    public static final int GATEKEEPER_RIFLE_LEVEL = 2;
    public static final int GATEKEEPER_SMG_LEVEL = 1;
    public static final int GATEKEEPER_SUWA_LEVEL = 1;
    public static final int GATEKEEPER_RIFLE_MAGAZINES = 2;
    public static final int GATEKEEPER_SMG_MAGAZINES = 2;
    public static final int GATEKEEPER_SUWA_MAGAZINES = 1;
    public static final int GATEKEEPER_FUEL_PELLETS = 24;
    public static final int GATEKEEPER_DARK_GRAY_TEXTURE = 1;

    /**
     * Applies the locked Gatekeeper R-01 assembly. This is deliberately separate from
     * the six selectable fleet presets: R-01 is a mission enemy, never a seventh player
     * build. Every slot is cleared first so recovery/replacement cannot inherit loot.
     */
    public static void applyGatekeeperLoadout(Pmvc01Entity mech) {
        mech.clearContent();

        put(mech, Pmvc01Entity.INV_PARTS_HEAD, PomkotsMechs.MUKNVALI_HEAD.get(), 1);
        put(mech, Pmvc01Entity.INV_PARTS_BODY, PomkotsMechs.MUKNVALI_BODY.get(), 1);
        put(mech, Pmvc01Entity.INV_PARTS_ARMS, PomkotsMechs.MUKNVALI_ARM.get(), 1);
        put(mech, Pmvc01Entity.INV_PARTS_LEGS, PomkotsMechs.ALDEBARAN_LEGS.get(), 1);
        put(mech, Pmvc01Entity.INV_PARTS_GENERATOR, PomkotsMechs.SHIGA_GENERATOR.get(), 1);
        put(mech, Pmvc01Entity.INV_PARTS_BOOSTER, PomkotsMechs.NARITA_BOOSTER.get(), 1);

        putAtLevel(mech, Pmvc01Entity.INV_WEAPON_RIGHT_HAND,
                PomkotsMechs.SHAKUJI_WEAPON.get(), GATEKEEPER_RIFLE_LEVEL);
        putAtLevel(mech, Pmvc01Entity.INV_WEAPON_LEFT_HAND,
                PomkotsMechs.SHINOBAZU_WEAPON.get(), GATEKEEPER_SMG_LEVEL);
        putAtLevel(mech, Pmvc01Entity.INV_WEAPON_RIGHT_SHOULDER,
                PomkotsMechs.SUWA_WEAPON.get(), GATEKEEPER_SUWA_LEVEL);

        put(mech, Pmvc01Entity.INV_AMMO_RA, PomkotsMechs.RIFLE_MAGAZINE.get(), GATEKEEPER_RIFLE_MAGAZINES);
        put(mech, Pmvc01Entity.INV_AMMO_LA, PomkotsMechs.MACHINE_GUN_MAGAZINE.get(), GATEKEEPER_SMG_MAGAZINES);
        put(mech, Pmvc01Entity.INV_AMMO_RS, PomkotsMechs.GATLING_MAGAZINE.get(), GATEKEEPER_SUWA_MAGAZINES);
        put(mech, Pmvc01Entity.INV_FUEL, PomkotsMechs.PELLET.get(), GATEKEEPER_FUEL_PELLETS);

        mech.setTextureColor(GATEKEEPER_DARK_GRAY_TEXTURE);
        // Parts are complete before the one synchronization/reload reset. This gives
        // every equipped gun a full initial magazine rather than a pending reload.
        mech.resetForArenaService();
        mech.getAttribute(Attributes.MAX_HEALTH).setBaseValue(GATEKEEPER_MAX_HEALTH);
        mech.setHealth(GATEKEEPER_MAX_HEALTH);
    }

    private static void putAtLevel(Pmvc01Entity mech, int slot, Item item, int level) {
        ItemStack stack = new ItemStack(item);
        if (item instanceof BasePartsItem part) {
            part.setLevel(stack, level);
            // Keep the authored enemy level exact even if a legacy-compatible server
            // reads the historical literal key instead of the namespaced one.
            stack.getOrCreateTag().putInt("Level", level);
        }
        mech.setItem(slot, stack);
    }

    private static void put(Pmvc01Entity mech, int slot, Item item, int count) {
        mech.setItem(slot, new ItemStack(item, Math.min(count, item.getMaxStackSize())));
    }

    /**
     * An immutable preset. Item references are the mod's registered {@link Item} suppliers;
     * {@code null} in an ammo slot means the paired weapon is melee (consumes no magazine).
     */
    private static final class Loadout {
        private final String name;
        private Item head, body, arm, legs, generator, booster;
        private Item rightHand, leftHand, rightShoulder, leftShoulder, ext1, ext2;
        private Item ammoRightHand, ammoLeftHand, ammoRightShoulder, ammoLeftShoulder;

        private Loadout(String name) {
            this.name = name;
        }

        static Loadout named(String name) {
            return new Loadout(name);
        }

        Loadout frame(RegistrySupplier<Item> head, RegistrySupplier<Item> body,
                      RegistrySupplier<Item> arm, RegistrySupplier<Item> legs) {
            this.head = head.get();
            this.body = body.get();
            this.arm = arm.get();
            this.legs = legs.get();
            return this;
        }

        Loadout power(RegistrySupplier<Item> generator, RegistrySupplier<Item> booster) {
            this.generator = generator.get();
            this.booster = booster.get();
            return this;
        }

        Loadout rightHand(RegistrySupplier<Item> weapon, @Nullable RegistrySupplier<Item> ammo) {
            this.rightHand = weapon.get();
            this.ammoRightHand = (ammo == null) ? null : ammo.get();
            return this;
        }

        Loadout leftHand(RegistrySupplier<Item> weapon, @Nullable RegistrySupplier<Item> ammo) {
            this.leftHand = weapon.get();
            this.ammoLeftHand = (ammo == null) ? null : ammo.get();
            return this;
        }

        Loadout rightShoulder(RegistrySupplier<Item> weapon, @Nullable RegistrySupplier<Item> ammo) {
            this.rightShoulder = weapon.get();
            this.ammoRightShoulder = (ammo == null) ? null : ammo.get();
            return this;
        }

        Loadout leftShoulder(RegistrySupplier<Item> weapon, @Nullable RegistrySupplier<Item> ammo) {
            this.leftShoulder = weapon.get();
            this.ammoLeftShoulder = (ammo == null) ? null : ammo.get();
            return this;
        }

        Loadout extensions(@Nullable RegistrySupplier<Item> ext1, @Nullable RegistrySupplier<Item> ext2) {
            this.ext1 = (ext1 == null) ? null : ext1.get();
            this.ext2 = (ext2 == null) ? null : ext2.get();
            return this;
        }

        /**
         * Writes every configured part into the mech's inventory slots, then commits with a
         * single {@link Pmvc01Entity#setChanged()} so the derived stats, health, weapons, and
         * ammo counts all recompute once from the finished inventory. Finally tops the mech to
         * full health so a fresh loot mech starts pristine rather than at the default health
         * pool of a bare frame.
         */
        void applyTo(Pmvc01Entity mech) {
            writeInventoryTo(mech);
            mech.setChanged();
            mech.setHealth(mech.getMaxHealth());
        }

        private void writeInventoryTo(Pmvc01Entity mech) {
            put(mech, Pmvc01Entity.INV_PARTS_HEAD, head, 1);
            put(mech, Pmvc01Entity.INV_PARTS_BODY, body, 1);
            put(mech, Pmvc01Entity.INV_PARTS_ARMS, arm, 1);
            put(mech, Pmvc01Entity.INV_PARTS_LEGS, legs, 1);
            put(mech, Pmvc01Entity.INV_PARTS_GENERATOR, generator, 1);
            put(mech, Pmvc01Entity.INV_PARTS_BOOSTER, booster, 1);

            put(mech, Pmvc01Entity.INV_WEAPON_RIGHT_HAND, rightHand, 1);
            put(mech, Pmvc01Entity.INV_WEAPON_LEFT_HAND, leftHand, 1);
            put(mech, Pmvc01Entity.INV_WEAPON_RIGHT_SHOULDER, rightShoulder, 1);
            put(mech, Pmvc01Entity.INV_WEAPON_LEFT_SHOULDER, leftShoulder, 1);
            put(mech, Pmvc01Entity.INV_WEAPON_EXT1, ext1, 1);
            put(mech, Pmvc01Entity.INV_WEAPON_EXT2, ext2, 1);

            put(mech, Pmvc01Entity.INV_AMMO_RA, ammoRightHand, AMMO_MAGAZINES);
            put(mech, Pmvc01Entity.INV_AMMO_LA, ammoLeftHand, AMMO_MAGAZINES);
            put(mech, Pmvc01Entity.INV_AMMO_RS, ammoRightShoulder, AMMO_MAGAZINES);
            put(mech, Pmvc01Entity.INV_AMMO_LS, ammoLeftShoulder, AMMO_MAGAZINES);

            put(mech, Pmvc01Entity.INV_FUEL, PomkotsMechs.PELLET.get(), FUEL_PELLETS);
        }

        /** Sets a slot to {@code count} of {@code item}, clamped to the item's max stack; no-op for null. */
        private static void put(Pmvc01Entity mech, int slot, @Nullable Item item, int count) {
            if (item == null) {
                return;
            }
            mech.setItem(slot, new ItemStack(item, Math.min(count, item.getMaxStackSize())));
        }
    }
}
