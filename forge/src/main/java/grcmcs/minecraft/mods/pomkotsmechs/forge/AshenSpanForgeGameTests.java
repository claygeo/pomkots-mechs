package grcmcs.minecraft.mods.pomkotsmechs.forge;

import com.mojang.authlib.GameProfile;
import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaData;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaGameTestAccess;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaHooks;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaOwnershipRegistry;
import grcmcs.minecraft.mods.pomkotsmechs.arena.ArenaRivalController;
import grcmcs.minecraft.mods.pomkotsmechs.arena.AshenSpanDefinition;
import grcmcs.minecraft.mods.pomkotsmechs.arena.AshenSpanMapContract;
import grcmcs.minecraft.mods.pomkotsmechs.arena.AshenSpanMapData;
import grcmcs.minecraft.mods.pomkotsmechs.arena.AshenSpanMissionModel;
import grcmcs.minecraft.mods.pomkotsmechs.arena.GarageFleet;
import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss.BaseBossEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss.BossHitBoxEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.monster.boss.Pmb04Entity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.EarthraiseEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.custom.BulletRifleEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.ArenaRivalPmvc01Entity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.vehicle.custom.Pmvc01Entity;
import grcmcs.minecraft.mods.pomkotsmechs.items.parts.BasePartsItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import software.bernie.geckolib.animatable.GeoItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Headless, world-backed coverage for the authored Ashen Span runtime seams. */
@GameTestHolder(PomkotsMechs.MODID)
@PrefixGameTestTemplate(false)
public final class AshenSpanForgeGameTests {
    private static final String EMPTY_TEMPLATE = "gametest/empty";

    private AshenSpanForgeGameTests() {
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void soloStartAndServiceRestoreAllSixGarageBuilds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int build = 0; build < GarageFleet.size(); build++) {
            Pmvc01Entity actual = buildMech(helper, level, build);
            Pmvc01Entity expected = buildMech(helper, level, build);
            try {
                // A freshly staged mission template has every combat resource ready,
                // not the legacy inventory-only build state used by ROYALE scatter.
                expected.resetForArenaService();
                helper.assertTrue(ArenaGameTestAccess.prepareSoloGarageBuild(actual),
                        "SOLO start setup rejected Garage build " + build);
                assertCombatReady(helper, actual, expected, build, "SOLO start");
                actual.setPos(helper.absoluteVec(new Vec3(0.5D, 0.0D, 0.5D)));
                helper.assertTrue(level.addFreshEntity(actual),
                        "build " + build + " could not enter the GameTest world");
                actual.clearContent();
                actual.setHealth(1.0F);

                helper.assertTrue(GarageFleet.service(actual, build),
                        "service rejected Garage build " + build);
                helper.assertTrue(actual.getContainerSize() == Pmvc01Entity.CONTAINER_SIZE,
                        "service changed the PMVC01 container size for build " + build);
                for (int slot = 0; slot < Pmvc01Entity.CONTAINER_SIZE; slot++) {
                    ItemStack wanted = expected.getItem(slot);
                    ItemStack restored = actual.getItem(slot);
                    helper.assertTrue(matchesServiceTemplate(wanted, restored),
                            "Garage build " + build + " slot " + slot
                                    + " was not restored exactly: expected " + wanted
                                    + " " + wanted.getTag() + ", got " + restored
                                    + " " + restored.getTag());
                }
                helper.assertTrue(Math.abs(actual.getHealth() - actual.getMaxHealth()) < 0.001F,
                        "service did not restore full health for Garage build " + build);
                helper.assertTrue(Math.abs(actual.getMaxHealth() - expected.getMaxHealth()) < 0.001F,
                        "service changed derived max health for Garage build " + build);
                for (int weaponSlot = Pmvc01Entity.INV_WEAPON_RIGHT_HAND;
                     weaponSlot <= Pmvc01Entity.INV_WEAPON_LEFT_SHOULDER; weaponSlot++) {
                    Pmvc01Entity.AmmoManager restoredAmmo = actual.getAmmoManager(weaponSlot);
                    Pmvc01Entity.AmmoManager templateAmmo = expected.getAmmoManager(weaponSlot);
                    helper.assertTrue(restoredAmmo.getBulletNum()
                                    == templateAmmo.getBulletNum()
                                    && restoredAmmo.getBulletNumPerMagazine()
                                    == templateAmmo.getBulletNumPerMagazine()
                                    && restoredAmmo.getMagazineNum()
                                    == templateAmmo.getMagazineNum()
                                    && restoredAmmo.getReloadTicks() == 0
                                    && templateAmmo.getReloadTicks() == 0,
                            "Garage build " + build + " weapon slot " + weaponSlot
                                    + " did not restore exact ready ammo/reload state");
                }
                helper.assertTrue(actual.getFuelNow() == expected.getFuelNow()
                                && actual.getMaxFuel() == expected.getMaxFuel()
                                && actual.getFuelNow() > 0,
                        "service did not restore exact fuel state for Garage build " + build);
                helper.assertTrue(actual.getEnergy() == expected.getEnergy()
                                && actual.getMaxEnergy() == expected.getMaxEnergy()
                                && actual.getEnergy() == actual.getMaxEnergy(),
                        "service did not restore full exact energy for Garage build " + build);
            } finally {
                actual.discard();
                expected.discard();
            }
        }
        helper.assertTrue(GarageFleet.size() == 6, "Garage Fleet roster is not locked to six builds");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 200)
    public static void soloDeploymentAtPlayerPadExcludesPilotButRejectsNonPlayerBlocker(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 pad = new Vec3(AshenSpanDefinition.PLAYER_PAD.x(),
                AshenSpanDefinition.PLAYER_PAD.y(), AshenSpanDefinition.PLAYER_PAD.z());
        Pmvc01Entity mech = buildMech(helper, level, 0);
        CollidableGameTestPlayer pilot = new CollidableGameTestPlayer(level,
                new GameProfile(UUID.fromString("00000000-0000-0000-0000-000000002505"),
                        "ashen-span-pad-pilot"));
        Entity blocker = EntityType.BOAT.create(level);
        helper.assertTrue(blocker != null, "vanilla Boat blocker was unavailable");
        Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
        Set<UUID> createdIds = Set.of(mech.getUUID(), pilot.getUUID(), blocker.getUUID());

        ArenaGameTestAccess.endOwnership();
        mech.setPos(pad);
        prepareDeploymentPad(level, mech.getBoundingBox(), originalBlocks);
        try {
            pilot.setPos(pad);
            level.addNewPlayer(pilot);
            helper.assertTrue(!level.noCollision(mech, mech.getBoundingBox()),
                    "GameTest fixture did not reproduce the caller entity collision");
            helper.assertTrue(ArenaGameTestAccess.isSoloDeploymentPadClear(level, mech, pilot),
                    "player at the authored PLAYER_PAD incorrectly blocked SOLO deployment");

            helper.assertTrue(ArenaGameTestAccess.prepareSoloGarageBuild(mech),
                    "Garage build was not ready for the deployment mount");
            helper.assertTrue(level.addFreshEntity(mech),
                    "player mech could not enter the world after pad validation");
            helper.assertTrue(pilot.startRiding(mech, true)
                            && mech.getDrivingPassenger() == pilot,
                    "player at PLAYER_PAD could not mount the validated Garage build");

            blocker.setPos(pad);
            helper.assertTrue(blocker.canBeCollidedWith(),
                    "Boat fixture was not a collidable non-player obstruction");
            helper.assertTrue(level.addFreshEntity(blocker),
                    "non-player blocker could not enter the deployment pad");
            helper.assertTrue(!ArenaGameTestAccess.isSoloDeploymentPadClear(level, mech, pilot),
                    "collidable non-player entity did not block SOLO deployment");
        } finally {
            pilot.stopRiding();
            blocker.discard();
            mech.discard();
            if (!pilot.isRemoved()) {
                level.removePlayerImmediately(pilot, Entity.RemovalReason.DISCARDED);
            }
            ArenaGameTestAccess.endOwnership();
            restoreBlocks(level, originalBlocks);
        }

        helper.assertTrue(createdIds.stream().allMatch(id -> level.getEntity(id) == null)
                        && !ArenaHooks.isActive(),
                "deployment regression GameTest did not clean its entities to exact zero");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void gateLedgerRestoresExactStateIdempotently(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        BlockState original = Blocks.ORANGE_GLAZED_TERRACOTTA.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST);
        AshenSpanDefinition.BlockVolume oneBlock = new AshenSpanDefinition.BlockVolume(
                pos.getX(), pos.getX(), pos.getY(), pos.getY(), pos.getZ(), pos.getZ());
        ArenaData data = ArenaData.get(server);

        data.setMissionGateBlocks(List.of());
        level.setBlock(pos, original, 3);
        try {
            helper.assertTrue(ArenaGameTestAccess.openGate(server, level, oneBlock),
                    "one-block gate did not open");
            helper.assertTrue(level.getBlockState(pos).isAir(),
                    "opened gate was not air");
            helper.assertTrue(data.getMissionGateBlocks().size() == 1,
                    "gate ledger did not retain exactly one original state");

            helper.assertTrue(ArenaGameTestAccess.restoreGates(server),
                    "gate restore retained a supposedly restorable entry");
            helper.assertTrue(level.getBlockState(pos).equals(original),
                    "gate restore lost the exact glazed-terracotta facing state");
            helper.assertTrue(data.getMissionGateBlocks().isEmpty(),
                    "successful gate restore did not clear its ledger entry");

            helper.assertTrue(ArenaGameTestAccess.restoreGates(server),
                    "empty second restore was not idempotently successful");
            helper.assertTrue(level.getBlockState(pos).equals(original),
                    "idempotent second restore changed the original block state");
        } finally {
            ArenaGameTestAccess.restoreGates(server);
            data.setMissionGateBlocks(List.of());
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void stagingActivationAndOwnershipCleanupUseLiveEntities(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        ArmorStand root = EntityType.ARMOR_STAND.create(level);
        ArmorStand descendant = EntityType.ARMOR_STAND.create(level);
        helper.assertTrue(root != null && descendant != null,
                "vanilla ArmorStand entities were unavailable");

        ArenaGameTestAccess.endOwnership();
        try {
            root.setPos(helper.absoluteVec(new Vec3(0.25D, 0.0D, 0.25D)));
            root.setInvulnerable(true);
            ArenaGameTestAccess.beginOwnership(registry, 2501, level.dimension());
            ArenaGameTestAccess.registerRoot(root, ArenaOwnershipRegistry.RootRole.HOSTILE,
                    "GAMETEST", 0, true);
            helper.assertTrue(level.addFreshEntity(root), "owned staged root ADD was rejected");

            descendant.setPos(helper.absoluteVec(new Vec3(0.75D, 0.0D, 0.75D)));
            helper.assertTrue(ArenaHooks.beforeOwnedAdd(root, descendant,
                            ArenaOwnershipRegistry.DescendantKind.HITBOX),
                    "owned descendant registration was rejected");
            helper.assertTrue(level.addFreshEntity(descendant),
                    "owned staged descendant ADD was rejected");
            helper.assertTrue(registry.owns(root.getUUID()) && registry.owns(descendant.getUUID()),
                    "live entities were missing from the ownership graph");
            helper.assertTrue(ArenaHooks.isStaged(root) && ArenaHooks.isStaged(descendant),
                    "staging did not propagate to the owned descendant");
            helper.assertTrue(root.isInvulnerable() && descendant.isInvulnerable(),
                    "staged root/descendant were not both invulnerable");

            ArenaHooks.activate(root);
            helper.assertTrue(!ArenaHooks.isStaged(root) && !ArenaHooks.isStaged(descendant),
                    "root activation did not unstage its complete lineage");
            helper.assertTrue(!root.isInvulnerable() && !descendant.isInvulnerable(),
                    "root activation left an owned entity invulnerable");

            ArenaGameTestAccess.onDeath(descendant);
            descendant.discard();
            helper.assertTrue(!registry.owns(descendant.getUUID()),
                    "dead descendant remained in the ownership graph");
            root.discard();
            ArenaGameTestAccess.reconcile(level);
            helper.assertTrue(registry.hostileExactZero(),
                    "discarded root plus retired descendant did not reach exact zero");
            ArenaGameTestAccess.unregisterRoot(root.getUUID());
            helper.assertTrue(registry.ownedIds().isEmpty(),
                    "phase retirement left owned UUIDs behind");
        } finally {
            root.discard();
            descendant.discard();
            ArenaGameTestAccess.endOwnership();
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 100)
    public static void mapValidationFailureDoesNotMutateWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        BlockState sentinel = Blocks.DIAMOND_BLOCK.defaultBlockState();
        level.setBlock(pos, sentinel, 3);

        long dayTime = level.getDayTime();
        boolean mobGriefing = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        boolean mobSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
        boolean mobLoot = level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT);
        AshenSpanMapData beforeData = level.getDataStorage().get(
                AshenSpanMapData::load, AshenSpanMapData.DATA_NAME);
        CompoundTag beforeDataTag = beforeData == null
                ? null : beforeData.save(new CompoundTag());

        AshenSpanMapContract.Result result = AshenSpanMapContract.validate(server, level);
        helper.assertTrue(!result.valid(),
                "generic GameTest world unexpectedly passed the authored map contract");

        AshenSpanMapData afterData = level.getDataStorage().get(
                AshenSpanMapData::load, AshenSpanMapData.DATA_NAME);
        CompoundTag afterDataTag = afterData == null
                ? null : afterData.save(new CompoundTag());
        helper.assertTrue(level.getBlockState(pos).equals(sentinel),
                "map validation changed a world block");
        helper.assertTrue(level.getDayTime() == dayTime,
                "map validation changed world time");
        helper.assertTrue(level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) == mobGriefing
                        && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) == mobSpawning
                        && level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT) == mobLoot,
                "map validation changed a mission-relevant gamerule");
        helper.assertTrue(java.util.Objects.equals(beforeDataTag, afterDataTag),
                "map validation created or changed Sector 01 SavedData");
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 200)
    public static void gatekeeperRegisteredEntityHasAuthoredLoadoutAutonomyAndLineage(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        ArenaRivalPmvc01Entity rival = PomkotsMechs.ARENA_RIVAL_PMVC01.get().create(level);
        Pmvc01Entity playerMech = buildMech(helper, level, 0);
        FakePlayer pilot = FakePlayerFactory.getMinecraft(level);
        BulletRifleEntity projectile = null;
        helper.assertTrue(rival != null,
                "registered arena_rival_pmvc01 did not create its dedicated entity");

        buildSupportPlatform(helper, 2, 17, 2, 17);
        ArenaGameTestAccess.endOwnership();
        try {
            rival.configureLoadout();
            assertStack(helper, rival, Pmvc01Entity.INV_PARTS_HEAD,
                    PomkotsMechs.MUKNVALI_HEAD.get(), 1, "Muknvali head");
            assertStack(helper, rival, Pmvc01Entity.INV_PARTS_BODY,
                    PomkotsMechs.MUKNVALI_BODY.get(), 1, "Muknvali body");
            assertStack(helper, rival, Pmvc01Entity.INV_PARTS_ARMS,
                    PomkotsMechs.MUKNVALI_ARM.get(), 1, "Muknvali arms");
            assertStack(helper, rival, Pmvc01Entity.INV_PARTS_LEGS,
                    PomkotsMechs.ALDEBARAN_LEGS.get(), 1, "Aldebaran legs");
            assertStack(helper, rival, Pmvc01Entity.INV_PARTS_GENERATOR,
                    PomkotsMechs.SHIGA_GENERATOR.get(), 1, "Shiga generator");
            assertStack(helper, rival, Pmvc01Entity.INV_PARTS_BOOSTER,
                    PomkotsMechs.NARITA_BOOSTER.get(), 1, "Narita booster");
            assertStackAtLevel(helper, rival, Pmvc01Entity.INV_WEAPON_RIGHT_HAND,
                    PomkotsMechs.SHAKUJI_WEAPON.get(), GarageFleet.GATEKEEPER_RIFLE_LEVEL,
                    "right-hand Shakuji rifle");
            assertStackAtLevel(helper, rival, Pmvc01Entity.INV_WEAPON_LEFT_HAND,
                    PomkotsMechs.SHINOBAZU_WEAPON.get(), GarageFleet.GATEKEEPER_SMG_LEVEL,
                    "left-hand Shinobazu SMG");
            assertStackAtLevel(helper, rival, Pmvc01Entity.INV_WEAPON_RIGHT_SHOULDER,
                    PomkotsMechs.SUWA_WEAPON.get(), GarageFleet.GATEKEEPER_SUWA_LEVEL,
                    "right-shoulder Suwa");
            assertStack(helper, rival, Pmvc01Entity.INV_AMMO_RA,
                    PomkotsMechs.RIFLE_MAGAZINE.get(),
                    GarageFleet.GATEKEEPER_RIFLE_MAGAZINES, "rifle magazines");
            assertStack(helper, rival, Pmvc01Entity.INV_AMMO_LA,
                    PomkotsMechs.MACHINE_GUN_MAGAZINE.get(),
                    GarageFleet.GATEKEEPER_SMG_MAGAZINES, "SMG magazines");
            assertStack(helper, rival, Pmvc01Entity.INV_AMMO_RS,
                    PomkotsMechs.GATLING_MAGAZINE.get(),
                    GarageFleet.GATEKEEPER_SUWA_MAGAZINES, "Suwa magazines");
            assertStack(helper, rival, Pmvc01Entity.INV_FUEL,
                    PomkotsMechs.PELLET.get(), GarageFleet.GATEKEEPER_FUEL_PELLETS,
                    "fuel pellets");
            for (int slot : new int[]{Pmvc01Entity.INV_WEAPON_LEFT_SHOULDER,
                    Pmvc01Entity.INV_WEAPON_EXT1, Pmvc01Entity.INV_WEAPON_EXT2,
                    Pmvc01Entity.INV_AMMO_LS}) {
                helper.assertTrue(rival.getItem(slot).isEmpty(),
                        "Gatekeeper unexpected authored item in slot " + slot);
            }
            for (int slot = Pmvc01Entity.INV_FUEL + 1;
                 slot < Pmvc01Entity.CONTAINER_SIZE; slot++) {
                helper.assertTrue(rival.getItem(slot).isEmpty(),
                        "Gatekeeper storage slot " + slot + " was not empty");
            }
            assertFullMagazine(helper, rival, Pmvc01Entity.INV_WEAPON_RIGHT_HAND,
                    GarageFleet.GATEKEEPER_RIFLE_MAGAZINES, "rifle");
            assertFullMagazine(helper, rival, Pmvc01Entity.INV_WEAPON_LEFT_HAND,
                    GarageFleet.GATEKEEPER_SMG_MAGAZINES, "SMG");
            assertFullMagazine(helper, rival, Pmvc01Entity.INV_WEAPON_RIGHT_SHOULDER,
                    GarageFleet.GATEKEEPER_SUWA_MAGAZINES, "Suwa");
            helper.assertTrue(rival.getTextureColor() == GarageFleet.GATEKEEPER_DARK_GRAY_TEXTURE,
                    "Gatekeeper texture is not the locked dark-gray variant");
            helper.assertTrue(Math.abs(rival.getMaxHealth()
                            - GarageFleet.GATEKEEPER_MAX_HEALTH) < 0.001F
                            && Math.abs(rival.getHealth()
                            - GarageFleet.GATEKEEPER_MAX_HEALTH) < 0.001F,
                    "Gatekeeper did not start at its authored 400/400 HP");
            helper.assertTrue(rival.getEnergy() == rival.getMaxEnergy()
                            && rival.getMaxEnergy() > 0,
                    "Gatekeeper energy was not fully serviced");
            helper.assertTrue(rival.getFuelNow() > 0,
                    "Gatekeeper fuel sync did not include its 24 pellets");
            helper.assertTrue(rival.getCustomName() != null
                            && "GATEKEEPER R-01".equals(rival.getCustomName().getString()),
                    "Gatekeeper mission identity was not configured");
            helper.assertTrue(!rival.isAutonomousActive()
                            && rival.getAutonomousTactic()
                            == ArenaRivalController.Tactic.INACTIVE,
                    "Gatekeeper was autonomous before mission activation");

            playerMech.setPos(helper.absoluteVec(new Vec3(15.0D, 1.0D, 8.0D)));
            rival.setPos(helper.absoluteVec(new Vec3(7.0D, 1.0D, 8.0D)));
            ArenaGameTestAccess.beginOwnership(registry, 2502, level.dimension());
            ArenaGameTestAccess.registerRoot(playerMech,
                    ArenaOwnershipRegistry.RootRole.PLAYER, "PLAYER", 0, false);
            helper.assertTrue(level.addFreshEntity(playerMech),
                    "registered player PMVC01 was rejected by the live ADD guard");
            ArenaGameTestAccess.registerRoot(rival,
                    ArenaOwnershipRegistry.RootRole.HOSTILE, "GATEKEEPER", 0, false);
            helper.assertTrue(level.addFreshEntity(rival),
                    "registered Gatekeeper was rejected by the live ADD guard");
            pilot.setPos(playerMech.position());
            helper.assertTrue(pilot.startRiding(playerMech, true)
                            && playerMech.getDrivingPassenger() == pilot,
                    "mock server pilot could not mount the active player PMVC01");

            rival.activateAutonomy(playerMech, 0xA55E_2502L);
            helper.assertTrue(rival.isAutonomousActive()
                            && playerMech.getUUID().equals(rival.getTargetUuid())
                            && rival.areAutonomousControlsReleased(),
                    "autonomy activation did not sync target and a released initial state");
            rival.tick();
            helper.assertTrue(rival.getAutonomousTactic()
                            == ArenaRivalController.Tactic.RETREAT,
                    "live Gatekeeper did not select the authored close-range retreat tactic");
            helper.assertTrue(rival.getDriverInput() != null
                            && rival.getDriverInput().isBackPressed()
                            && !rival.areAutonomousControlsReleased(),
                    "live Gatekeeper did not translate its safe retreat into server input");
            rival.travel(Vec3.ZERO);
            helper.assertTrue(horizontalLengthSqr(rival.getDeltaMovement()) > 1.0E-6D,
                    "server-authoritative Gatekeeper input did not produce movement");

            projectile = new BulletRifleEntity(PomkotsMechs.BULLET_RIFLE.get(),
                    level, rival);
            projectile.setPos(rival.position().add(0.0D, 2.0D, 0.0D));
            helper.assertTrue(level.addFreshEntity(projectile),
                    "real Gatekeeper rifle projectile was rejected by the ADD guard");
            ArenaOwnershipRegistry.DescendantRecord shotRecord =
                    registry.descendant(projectile.getUUID());
            helper.assertTrue(shotRecord != null
                            && shotRecord.kind()
                            == ArenaOwnershipRegistry.DescendantKind.PROJECTILE
                            && shotRecord.rootId().equals(rival.getUUID())
                            && projectile.getTags().contains(ArenaHooks.TAG_OWNED),
                    "real Gatekeeper rifle projectile did not inherit exact root lineage");
            projectile.discard();
            ArenaGameTestAccess.reconcile(level);
            helper.assertTrue(registry.projectileCount() == 0,
                    "discarded Gatekeeper projectile remained in the ownership graph");

            rival.prepareForRelocation();
            rival.setPos(helper.absoluteVec(new Vec3(15.0D, 1.0D, 8.0D)));
            playerMech.setPos(helper.absoluteVec(new Vec3(7.0D, 1.0D, 8.0D)));
            helper.assertTrue(!rival.hasSafeFootingFor(new Vec3(1.0D, 0.0D, 0.0D), 2.0D),
                    "Gatekeeper full-footprint probe accepted movement beyond the platform ledge");
            rival.activateAutonomy(playerMech, 0xA55E_2502L);
            rival.tick();
            helper.assertTrue(rival.getAutonomousTactic()
                            == ArenaRivalController.Tactic.RETREAT,
                    "ledge probe did not exercise the retreat tactic");
            helper.assertTrue(noMovementInput(rival)
                            && rival.areAutonomousControlsReleased()
                            && horizontalLengthSqr(rival.getDeltaMovement()) < 1.0E-8D,
                    "Gatekeeper attempted horizontal motion across an unsupported ledge");

            pilot.stopRiding();
            rival.setDeltaMovement(0.5D, 0.0D, 0.5D);
            rival.tick();
            helper.assertTrue(rival.getAutonomousTactic() == ArenaRivalController.Tactic.HOLD
                            && noMovementInput(rival)
                            && rival.areAutonomousControlsReleased()
                            && horizontalLengthSqr(rival.getDeltaMovement()) < 1.0E-8D,
                    "loss of the active player-mech contract did not release every control");
            rival.deactivateAutonomy();
            helper.assertTrue(!rival.isAutonomousActive()
                            && rival.getAutonomousTactic()
                            == ArenaRivalController.Tactic.INACTIVE
                            && rival.getTargetUuid() == null
                            && noMovementInput(rival)
                            && rival.areAutonomousControlsReleased(),
                    "Gatekeeper deactivation retained autonomous state or input");
        } finally {
            if (pilot.getVehicle() != null) {
                pilot.stopRiding();
            }
            if (projectile != null) {
                projectile.discard();
            }
            rival.discard();
            playerMech.discard();
            ArenaGameTestAccess.endOwnership();
            clearSupportPlatform(helper, 2, 17, 2, 17);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY_TEMPLATE, timeoutTicks = 200)
    public static void spanWardenRegisteredLineageDrivesExactZeroOutcomesAndRetry(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArenaOwnershipRegistry registry = new ArenaOwnershipRegistry();
        Pmb04Entity boss = PomkotsMechs.PMB04.get().create(level);
        BossHitBoxEntity hitBox = null;
        EarthraiseEntity earthraise = null;
        helper.assertTrue(boss != null,
                "registered pmb04 did not create the Span Warden entity");

        buildSupportPlatform(helper, 2, 17, 2, 17);
        ArenaGameTestAccess.endOwnership();
        try {
            boss.setPos(helper.absoluteVec(new Vec3(8.0D, 1.0D, 8.0D)));
            boss.deactivate();
            boss.setNoAi(true);
            boss.setInvulnerable(true);
            ArenaGameTestAccess.beginOwnership(registry, 2503, level.dimension());
            ArenaGameTestAccess.registerRoot(boss,
                    ArenaOwnershipRegistry.RootRole.HOSTILE, "POWER_DECK", 0, true);
            helper.assertTrue(level.addFreshEntity(boss),
                    "registered staged Span Warden was rejected by the live ADD guard");
            boss.tick();

            List<BossHitBoxEntity> hitBoxes = level.getEntitiesOfClass(
                    BossHitBoxEntity.class, boss.getBoundingBox().inflate(4.0D),
                    candidate -> candidate.getParentEntity() == boss);
            helper.assertTrue(hitBoxes.size() == 1,
                    "Span Warden did not create exactly one real supplemental hitbox");
            hitBox = hitBoxes.get(0);
            ArenaOwnershipRegistry.DescendantRecord hitBoxRecord =
                    registry.descendant(hitBox.getUUID());
            helper.assertTrue(hitBoxRecord != null
                            && hitBoxRecord.kind()
                            == ArenaOwnershipRegistry.DescendantKind.HITBOX
                            && hitBoxRecord.rootId().equals(boss.getUUID()),
                    "Span Warden hitbox did not inherit its actual PMB04 root lineage");
            helper.assertTrue(ArenaHooks.isStaged(boss) && ArenaHooks.isStaged(hitBox)
                            && boss.isInvulnerable() && hitBox.isInvulnerable(),
                    "Span Warden staging did not propagate to its real modular hitbox");
            float stagedHitBoxHealth = hitBox.getHealth();
            helper.assertTrue(!hitBox.hurt(level.damageSources().generic(), 10.0F)
                            && hitBox.getHealth() == stagedHitBoxHealth,
                    "staged Span Warden hitbox accepted combat damage");

            ArenaHooks.activate(boss);
            boss.setNoAi(false);
            boss.boot();
            helper.assertTrue(!ArenaHooks.isStaged(boss) && !ArenaHooks.isStaged(hitBox)
                            && !boss.isInvulnerable() && !hitBox.isInvulnerable(),
                    "Span Warden activation did not unstage its full real lineage");

            earthraise = new EarthraiseEntity(PomkotsMechs.EARTHRAISE.get(), level,
                    new Vec3(1.0D, 0.0D, 0.0D), boss, 0);
            earthraise.setPos(boss.position().add(0.0D, 2.0D, 0.0D));
            helper.assertTrue(level.addFreshEntity(earthraise),
                    "real PMB04 Earthraise was rejected by the live ADD guard");
            ArenaOwnershipRegistry.DescendantRecord earthraiseRecord =
                    registry.descendant(earthraise.getUUID());
            helper.assertTrue(earthraise.getOwner() == boss
                            && earthraiseRecord != null
                            && earthraiseRecord.kind()
                            == ArenaOwnershipRegistry.DescendantKind.PROJECTILE
                            && earthraiseRecord.rootId().equals(boss.getUUID())
                            && !ArenaHooks.isStaged(earthraise),
                    "real PMB04 Earthraise did not inherit active Span Warden lineage");

            ArenaGameTestAccess.clearTransientDescendantsForRelocation(
                    level, registry, boss.getUUID());
            helper.assertTrue(earthraise.isRemoved() && registry.projectileCount() == 0,
                    "same-root recovery did not remove the old PMB04 projectile lineage");
            ArenaOwnershipRegistry.DescendantRecord recoveredHitBoxRecord =
                    registry.descendant(hitBox.getUUID());
            helper.assertTrue(hitBox.isAlive() && !hitBox.isRemoved()
                            && recoveredHitBoxRecord != null
                            && recoveredHitBoxRecord.kind()
                            == ArenaOwnershipRegistry.DescendantKind.HITBOX
                            && recoveredHitBoxRecord.rootId().equals(boss.getUUID()),
                    "same-root recovery removed or unregistered the live PMB04 hitbox");

            boss.setPos(helper.absoluteVec(new Vec3(11.0D, 1.0D, 8.0D)));
            boss.tick();
            helper.assertTrue(hitBox.position().distanceToSqr(boss.position()) < 1.0E-8D,
                    "preserved PMB04 hitbox did not follow its relocated parent");
            for (int tick = 0;
                 tick < 180 && boss.getAiMode() == BaseBossEntity.AI_MODE_INACTIVE;
                 tick++) {
                boss.tick();
            }
            helper.assertTrue(boss.getAiMode() != BaseBossEntity.AI_MODE_INACTIVE,
                    "recovered PMB04 never completed its authored boot sequence");
            float healthBeforeHitBoxDamage = boss.getHealth();
            helper.assertTrue(hitBox.hurt(level.damageSources().generic(), 10.0F)
                            && boss.getHealth() < healthBeforeHitBoxDamage,
                    "preserved PMB04 hitbox no longer forwarded damage after recovery");

            earthraise = new EarthraiseEntity(PomkotsMechs.EARTHRAISE.get(), level,
                    new Vec3(1.0D, 0.0D, 0.0D), boss, 0);
            earthraise.setPos(boss.position().add(0.0D, 2.0D, 0.0D));
            helper.assertTrue(level.addFreshEntity(earthraise),
                    "post-recovery PMB04 Earthraise was rejected by the live ADD guard");

            AshenSpanMissionModel victoryModel = reachPowerDeckActive(helper,
                    0xA55E_2503L, 4);
            AshenSpanMissionModel.HostileCounts liveCounts = hostileCounts(registry);
            helper.assertTrue(liveCounts.roots() == 1 && liveCounts.projectiles() == 1
                            && liveCounts.effects() == 0
                            && liveCounts.supplementalHitboxes() == 1,
                    "director-facing counts did not reflect PMB04 + hitbox + Earthraise");
            helper.assertTrue(victoryModel.observe(
                            AshenSpanMissionModel.TickInput.healthy(88.0D, liveCounts)).status()
                            == AshenSpanMissionModel.Status.ACTIVE,
                    "live hostile lineage incorrectly cleared the Power Deck phase");

            boss.discard();
            ArenaGameTestAccess.reconcile(level);
            AshenSpanMissionModel.HostileCounts descendantsRemain = hostileCounts(registry);
            helper.assertTrue(descendantsRemain.roots() == 0
                            && descendantsRemain.descendants() == 2
                            && !descendantsRemain.exactZero(),
                    "PMB04 root retirement lost its live descendant tombstone gate");
            helper.assertTrue(victoryModel.observe(
                            AshenSpanMissionModel.TickInput.healthy(88.0D,
                                    descendantsRemain)).status()
                            == AshenSpanMissionModel.Status.ACTIVE,
                    "Power Deck cleared before supplemental PMB04 entities reached zero");

            hitBox.discard();
            earthraise.discard();
            ArenaGameTestAccess.reconcile(level);
            AshenSpanMissionModel.HostileCounts exactZero = hostileCounts(registry);
            helper.assertTrue(exactZero.exactZero(),
                    "PMB04 lineage did not reach exact zero after complete cleanup");
            AshenSpanMissionModel.Snapshot victory = victoryModel.observe(
                    AshenSpanMissionModel.TickInput.healthy(88.0D, exactZero));
            helper.assertTrue(victory.status() == AshenSpanMissionModel.Status.VICTORY
                            && victory.outcome() == AshenSpanMissionModel.Outcome.VICTORY,
                    "exact-zero PMB04 cleanup did not produce mission victory");
            AshenSpanMissionModel victoryRetry = victoryModel.retry();
            assertCleanRetry(helper, victoryRetry, 0xA55E_2503L, 4,
                    "victory retry");

            AshenSpanMissionModel doubleKoModel = reachPowerDeckActive(helper,
                    0xA55E_D0BEL, 5);
            AshenSpanMissionModel.Snapshot doubleKo = doubleKoModel.observe(
                    new AshenSpanMissionModel.TickInput(88.0D,
                            AshenSpanMissionModel.HostileCounts.ZERO,
                            false, false, true, false, false));
            helper.assertTrue(doubleKo.status() == AshenSpanMissionModel.Status.DEFEAT
                            && doubleKo.outcome() == AshenSpanMissionModel.Outcome.DEFEAT
                            && doubleKo.defeatReason()
                            == AshenSpanMissionModel.DefeatReason.PLAYER_DESTROYED,
                    "same-tick PMB04/player double KO did not resolve loss before clear");
            assertCleanRetry(helper, doubleKoModel.retry(), 0xA55E_D0BEL, 5,
                    "double-KO retry");

            ArenaGameTestAccess.unregisterRoot(boss.getUUID());
            helper.assertTrue(registry.ownedIds().isEmpty(),
                    "PMB04 exact-zero retirement retained owned UUIDs");
            ArenaGameTestAccess.endOwnership();

            Pmvc01Entity directorMech = buildMech(helper, level, 2);
            try {
                ArenaGameTestAccess.DirectorOutcomeProbe probe =
                        ArenaGameTestAccess.probeForcedDirectorOutcome(
                                level, 2504, 0xA55E_2504L, 2,
                                java.util.UUID.fromString(
                                        "00000000-0000-0000-0000-000000002504"),
                                directorMech,
                                AshenSpanMissionModel.DefeatReason.STOPPED,
                                "GameTest forced stop");
                helper.assertTrue(probe.prepared()
                                && probe.status().startsWith("DEFEAT")
                                && probe.summary().contains("outcome defeat")
                                && "GameTest forced stop".equals(probe.failureReason())
                                && !probe.ownershipActiveAfterReset(),
                        "world-backed director prepare/outcome/report/reset seam failed");
            } finally {
                directorMech.discard();
            }
        } finally {
            if (hitBox != null) {
                hitBox.discard();
            }
            if (earthraise != null) {
                earthraise.discard();
            }
            boss.discard();
            ArenaGameTestAccess.endOwnership();
            clearSupportPlatform(helper, 2, 17, 2, 17);
        }
        helper.succeed();
    }

    private static Pmvc01Entity buildMech(GameTestHelper helper, ServerLevel level, int build) {
        var built = GarageFleet.build(level, build);
        helper.assertTrue(built instanceof Pmvc01Entity,
                "Garage build " + build + " did not create PMVC01");
        return (Pmvc01Entity) built;
    }

    private static void assertStack(GameTestHelper helper, Pmvc01Entity mech, int slot,
                                    Item item, int count, String label) {
        ItemStack stack = mech.getItem(slot);
        helper.assertTrue(stack.is(item) && stack.getCount() == count,
                "Gatekeeper " + label + " mismatch in slot " + slot + ": " + stack);
    }

    private static void assertStackAtLevel(GameTestHelper helper, Pmvc01Entity mech,
                                           int slot, Item item, int level, String label) {
        assertStack(helper, mech, slot, item, 1, label);
        ItemStack stack = mech.getItem(slot);
        helper.assertTrue(stack.getItem() instanceof BasePartsItem part
                        && part.getLevel(stack) == level
                        && stack.getOrCreateTag().getInt("Level") == level,
                "Gatekeeper " + label + " did not retain authored level " + level);
    }

    private static void assertFullMagazine(GameTestHelper helper, Pmvc01Entity mech,
                                           int weaponSlot, int magazines, String label) {
        Pmvc01Entity.AmmoManager ammo = mech.getAmmoManager(weaponSlot);
        helper.assertTrue(ammo.getBulletNumPerMagazine() > 0
                        && ammo.getBulletNum() == ammo.getBulletNumPerMagazine()
                        && ammo.getMagazineNum() == magazines,
                "Gatekeeper " + label + " did not start with a full loaded magazine and "
                        + magazines + " authored magazines");
    }

    private static void assertCombatReady(GameTestHelper helper, Pmvc01Entity actual,
                                          Pmvc01Entity expected, int build,
                                          String context) {
        helper.assertTrue(Math.abs(actual.getHealth() - actual.getMaxHealth()) < 0.001F
                        && Math.abs(actual.getMaxHealth() - expected.getMaxHealth()) < 0.001F,
                context + " did not provide full derived health for Garage build " + build);
        for (int weaponSlot = Pmvc01Entity.INV_WEAPON_RIGHT_HAND;
             weaponSlot <= Pmvc01Entity.INV_WEAPON_LEFT_SHOULDER; weaponSlot++) {
            Pmvc01Entity.AmmoManager actualAmmo = actual.getAmmoManager(weaponSlot);
            Pmvc01Entity.AmmoManager expectedAmmo = expected.getAmmoManager(weaponSlot);
            helper.assertTrue(actualAmmo.getBulletNum() == expectedAmmo.getBulletNum()
                            && actualAmmo.getBulletNumPerMagazine()
                            == expectedAmmo.getBulletNumPerMagazine()
                            && actualAmmo.getMagazineNum() == expectedAmmo.getMagazineNum()
                            && actualAmmo.getReloadTicks() == 0,
                    context + " did not provide exact ready ammo for Garage build "
                            + build + " weapon slot " + weaponSlot);
        }
        helper.assertTrue(actual.getFuelNow() == expected.getFuelNow()
                        && actual.getMaxFuel() == expected.getMaxFuel()
                        && actual.getFuelNow() > 0,
                context + " did not sync full fuel for Garage build " + build);
        helper.assertTrue(actual.getEnergy() == expected.getEnergy()
                        && actual.getMaxEnergy() == expected.getMaxEnergy()
                        && actual.getEnergy() == actual.getMaxEnergy(),
                context + " did not provide full energy for Garage build " + build);
    }

    private static boolean noMovementInput(ArenaRivalPmvc01Entity rival) {
        return rival.getDriverInput() != null
                && !rival.getDriverInput().isForwardPressed()
                && !rival.getDriverInput().isBackPressed()
                && !rival.getDriverInput().isLeftPressed()
                && !rival.getDriverInput().isRightPressed()
                && !rival.getDriverInput().isEvasionPressed();
    }

    private static double horizontalLengthSqr(Vec3 movement) {
        return movement.x * movement.x + movement.z * movement.z;
    }

    private static AshenSpanMissionModel.HostileCounts hostileCounts(
            ArenaOwnershipRegistry registry) {
        return new AshenSpanMissionModel.HostileCounts(
                registry.liveHostileRootCount(),
                registry.hostileDescendantCount(
                        ArenaOwnershipRegistry.DescendantKind.PROJECTILE),
                registry.hostileDescendantCount(
                        ArenaOwnershipRegistry.DescendantKind.EFFECT),
                registry.hostileDescendantCount(
                        ArenaOwnershipRegistry.DescendantKind.HITBOX));
    }

    private static AshenSpanMissionModel reachPowerDeckActive(
            GameTestHelper helper, long seed, int build) {
        AshenSpanMissionModel model = new AshenSpanMissionModel(seed, build);
        model.onBuildMounted();
        model.admitCurrentPhase(3);
        clearActive(helper, model, -150.0D);
        stageTriggerAndClear(helper, model, 3, -132.0D);
        stageTriggerAndClear(helper, model, 3, -84.0D);
        stageTriggerAndClear(helper, model, 4, -28.0D);
        stageTriggerAndClear(helper, model, 2, 28.0D);
        model.admitCurrentPhase(1);
        helper.assertTrue(model.status() == AshenSpanMissionModel.Status.ACTIVE
                        && model.currentPhase().orElseThrow().id()
                        == AshenSpanDefinition.PhaseId.GATEKEEPER,
                "mission model did not reach the live Gatekeeper phase");
        clearActive(helper, model, 44.0D);
        helper.assertTrue(model.status() == AshenSpanMissionModel.Status.SERVICE,
                "Gatekeeper exact zero did not enter the service checkpoint");
        helper.assertTrue(model.completeService(),
                "captured gantry service did not complete exactly once");
        model.admitCurrentPhase(1);
        model.observe(AshenSpanMissionModel.TickInput.healthy(88.0D, roots(1)));
        helper.assertTrue(model.status() == AshenSpanMissionModel.Status.ACTIVE
                        && model.currentPhase().orElseThrow().id()
                        == AshenSpanDefinition.PhaseId.POWER_DECK,
                "mission model did not reach active Power Deck PMB04 combat");
        return model;
    }

    private static void stageTriggerAndClear(GameTestHelper helper,
                                             AshenSpanMissionModel model,
                                             int expectedRoots, double triggerX) {
        helper.assertTrue(model.status() == AshenSpanMissionModel.Status.STAGING,
                "phase was not staged before root admission");
        model.admitCurrentPhase(expectedRoots);
        if (model.status() == AshenSpanMissionModel.Status.WAITING_FOR_TRIGGER) {
            model.observe(AshenSpanMissionModel.TickInput.healthy(
                    triggerX, roots(expectedRoots)));
        }
        helper.assertTrue(model.status() == AshenSpanMissionModel.Status.ACTIVE,
                "phase did not activate at its authored trigger");
        clearActive(helper, model, triggerX);
    }

    private static void clearActive(GameTestHelper helper, AshenSpanMissionModel model,
                                    double playerX) {
        helper.assertTrue(model.status() == AshenSpanMissionModel.Status.ACTIVE,
                "attempted exact-zero clear outside an active phase");
        model.observe(AshenSpanMissionModel.TickInput.healthy(
                playerX, AshenSpanMissionModel.HostileCounts.ZERO));
    }

    private static AshenSpanMissionModel.HostileCounts roots(int roots) {
        return new AshenSpanMissionModel.HostileCounts(roots, 0, 0, 0);
    }

    private static void assertCleanRetry(GameTestHelper helper,
                                         AshenSpanMissionModel retry,
                                         long seed, int build, String label) {
        AshenSpanMissionModel.Snapshot snapshot = retry.snapshot();
        helper.assertTrue(snapshot.seed() == seed && snapshot.selectedBuild() == build
                        && snapshot.status() == AshenSpanMissionModel.Status.GARAGE
                        && snapshot.outcome() == AshenSpanMissionModel.Outcome.RUNNING
                        && snapshot.defeatReason() == AshenSpanMissionModel.DefeatReason.NONE
                        && snapshot.currentPhase() == null
                        && snapshot.openedGates().isEmpty()
                        && !snapshot.serviceCompleted(),
                label + " did not restore a clean deterministic garage state");
    }

    private static void prepareDeploymentPad(ServerLevel level, AABB box,
                                             Map<BlockPos, BlockState> originalBlocks) {
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.floor(Math.nextDown(box.maxX));
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(Math.nextDown(box.maxZ));
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.ceil(box.maxY) - 1;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                setTemporaryBlock(level, new BlockPos(x, minY - 1, z),
                        Blocks.STONE.defaultBlockState(), originalBlocks);
                for (int y = minY; y <= maxY; y++) {
                    setTemporaryBlock(level, new BlockPos(x, y, z),
                            Blocks.AIR.defaultBlockState(), originalBlocks);
                }
            }
        }
    }

    private static void setTemporaryBlock(ServerLevel level, BlockPos pos,
                                          BlockState state,
                                          Map<BlockPos, BlockState> originalBlocks) {
        originalBlocks.putIfAbsent(pos.immutable(), level.getBlockState(pos));
        level.setBlock(pos, state, 3);
    }

    private static void restoreBlocks(ServerLevel level,
                                      Map<BlockPos, BlockState> originalBlocks) {
        originalBlocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
    }

    private static void buildSupportPlatform(GameTestHelper helper,
                                             int minX, int maxX, int minZ, int maxZ) {
        ServerLevel level = helper.getLevel();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    private static void clearSupportPlatform(GameTestHelper helper,
                                             int minX, int maxX, int minZ, int maxZ) {
        ServerLevel level = helper.getLevel();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static boolean matchesServiceTemplate(ItemStack expected, ItemStack actual) {
        // GeckoLib assigns every animated stack a unique, world-local identity when
        // PMVC01 registers its actions. It is intentionally different for two otherwise
        // identical loadouts and is not service/loadout state.
        ItemStack normalizedExpected = withoutAnimationIdentity(expected);
        ItemStack normalizedActual = withoutAnimationIdentity(actual);
        return ItemStack.matches(normalizedExpected, normalizedActual);
    }

    private static ItemStack withoutAnimationIdentity(ItemStack stack) {
        ItemStack copy = stack.copy();
        if (copy.hasTag()) {
            copy.getTag().remove(GeoItem.ID_NBT_KEY);
            if (copy.getTag().isEmpty()) {
                copy.setTag(null);
            }
        }
        return copy;
    }

    private static final class CollidableGameTestPlayer extends FakePlayer {
        private CollidableGameTestPlayer(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean canBeCollidedWith() {
            return true;
        }
    }
}
