package grcmcs.minecraft.mods.pomkotsmechs.block;

import grcmcs.minecraft.mods.pomkotsmechs.PomkotsMechs;
import grcmcs.minecraft.mods.pomkotsmechs.config.datapack.PomkotsDataPackManager;
import grcmcs.minecraft.mods.pomkotsmechs.config.datapack.raid.RaidDefinition;
import grcmcs.minecraft.mods.pomkotsmechs.entity.event.RaidControllerEntity;
import grcmcs.minecraft.mods.pomkotsmechs.entity.projectile.custom.AlertEntity;
import grcmcs.minecraft.mods.pomkotsmechs.util.Utils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.NotNull;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.*;

public class PomkotsCubeBlockEntity extends ChestBlockEntity implements GeoBlockEntity {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private static final long REFILL_INTERVAL = 24000L;

    // ====================== NBT系 ======================

    protected int mode = getDefaultMode();

    private int summonActionTickCount = 0;

    private Player opener = null;

    private boolean openedOnce = false;

    private long lastRefillGameTime = -1;

    private ResourceLocation refillLootTable = null;

    private UUID raidControllerUUID;
    private RaidControllerEntity raidControllerEntity;

    // Key escrow: the key is removed from the player's hand when the countdown
    // starts and only truly consumed when the raid controller spawns. Every
    // cancellation path (opener leaves, controller spawn fails, reload) refunds it.
    private ItemStack escrowedKey = ItemStack.EMPTY;
    private boolean refundEscrowOnTick = false;

    // Lazy resolution of the raid controller after a restart. The guard UUID is
    // only cleared once the controller has been unresolvable for this many ticks
    // (its chunk may simply not have loaded yet).
    private int raidControllerResolveGrace = 0;
    private static final int RAID_CONTROLLER_RESOLVE_GRACE_TICKS = 100;

    @Override
    public void saveAdditional(@NotNull CompoundTag tag) {
        super.saveAdditional(tag);

        tag.putInt(PomkotsMechs.nbtName("CubeMode"), mode);
        tag.putBoolean(PomkotsMechs.nbtName("OpenedOnce"), openedOnce);
        tag.putLong(PomkotsMechs.nbtName("LastRefillTime"), lastRefillGameTime);

        if (refillLootTable != null) {
            tag.putString(PomkotsMechs.nbtName("RefillLootTable"), refillLootTable.toString());
        }

        if (this.raidControllerUUID != null) {
            tag.putUUID(PomkotsMechs.nbtName("RaidControllerUUID"), raidControllerUUID);
        } else {
            tag.remove(PomkotsMechs.nbtName("RaidControllerUUID"));
        }

        if (!escrowedKey.isEmpty()) {
            tag.put(PomkotsMechs.nbtName("EscrowedKey"), escrowedKey.save(new CompoundTag()));
        } else {
            tag.remove(PomkotsMechs.nbtName("EscrowedKey"));
        }
    }

    @Override
    public void load(@NotNull CompoundTag tag) {
        super.load(tag);

        this.mode = tag.getInt(PomkotsMechs.nbtName("CubeMode"));
        this.openedOnce = tag.getBoolean(PomkotsMechs.nbtName("OpenedOnce"));
        this.lastRefillGameTime = tag.getLong(PomkotsMechs.nbtName("LastRefillTime"));
        if (tag.contains(PomkotsMechs.nbtName("RefillLootTable"))) {
            refillLootTable = new ResourceLocation(tag.getString(PomkotsMechs.nbtName("RefillLootTable")));
        }
        if (tag.contains(PomkotsMechs.nbtName("RaidControllerUUID"))) {
            // Keep the persisted guard UUID even if the controller entity is not
            // resolvable yet (its chunk may load after this block entity). tick()
            // resolves it lazily with a grace period; nulling it here would void
            // the double-raid guard across a restart.
            raidControllerUUID = tag.getUUID(PomkotsMechs.nbtName("RaidControllerUUID"));
            raidControllerEntity = null;
            raidControllerResolveGrace = 0;
        }

        if (tag.contains(PomkotsMechs.nbtName("EscrowedKey"))) {
            escrowedKey = ItemStack.of(tag.getCompound(PomkotsMechs.nbtName("EscrowedKey")));
        }

        // A pending countdown cannot be safely resumed across a reload (the opener
        // reference is gone), so treat any escrowed key as a cancelled attempt and
        // refund it at the cube on the next server tick. Reset the countdown.
        this.summonActionTickCount = 0;
        this.opener = null;
        if (!escrowedKey.isEmpty()) {
            this.refundEscrowOnTick = true;
        }

        tryRefill();
    }

    @Override
    public @NotNull CompoundTag getUpdateTag() {
        return this.saveWithoutMetadata();
    }

    // ====================== コンストラクタ ======================

    public PomkotsCubeBlockEntity(BlockPos pos, BlockState state) {
        this(PomkotsMechs.POMKOTS_CUBE_BLOCK_ENTITY.get(), pos, state);
    }

    protected PomkotsCubeBlockEntity(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);
    }

    // ====================== モード関連 ======================

    public static final int MODE_BLUE = 0;
    public static final int MODE_YELLOW = 1;
    public static final int MODE_RED = 2;
    public static final int MODE_PURPLE = 3;

    public int getMode() {
        return mode;
    }

    public void incrementMode() {
        mode++;
        if (mode > MODE_PURPLE) {
            mode = MODE_BLUE;
        }
        updateMode(mode);
    }

    public void updateMode(int mode) {
        updateMode(mode, this.level);
    }

    public void updateMode(int mode, Level level) {
        this.mode = mode;

        this.setChanged();
        BlockState state = this.getBlockState();
        level.sendBlockUpdated(this.worldPosition, state, state, 3);
    }

    public int getDefaultMode() {
        return MODE_BLUE;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ====================== 基本機能 ======================

    public void openBox(Player p) {
        switch (getMode()) {
            case PomkotsCubeBlockEntity.MODE_BLUE:
                if (!level.isClientSide) {
                    // Commit: a blue cube open is a real open.
                    this.opener = p;
                    if (!openedOnce) {
                        openedOnce = true;
                        lastRefillGameTime = level.dayTime();
                        setChanged();
                    }
                }
                break;

            case PomkotsCubeBlockEntity.MODE_YELLOW:
                if (!this.level.isClientSide) {
                    // Free raid trigger. Only commit (set opener + start countdown)
                    // when nothing is already pending/live; a second click during
                    // the countdown must not hijack ownership.
                    if (raidControllerUUID == null && summonActionTickCount == 0) {
                        this.opener = p;
                        this.summonActionTickCount = 1;
                        setChanged();
                    }
                } else {
                    playOpenFailAnimation();
                }
                break;

            case PomkotsCubeBlockEntity.MODE_RED:
                if (!this.level.isClientSide) {
                    tryStartKeyedRaid(p, PomkotsMechs.CUBEKEY_ITEM.get(),
                            "{text.pomkotsmechs.messages.pomkotscube.02}",
                            !PomkotsMechs.CONFIG.debugModeEnabled);
                } else {
                    playOpenFailAnimation();
                }
                break;

            case PomkotsCubeBlockEntity.MODE_PURPLE:
                if (!this.level.isClientSide) {
                    tryStartKeyedRaid(p, PomkotsMechs.CUBEKEY_ITEM_PURPLE.get(),
                            "{text.pomkotsmechs.messages.pomkotscube.03}", true);
                } else {
                    playOpenFailAnimation();
                }
                break;

            default:
                playOpenFailAnimation();
                break;
        }
    }

    /**
     * Red/purple keyed-raid open. Runs all checks BEFORE committing anything, so a
     * missing chest.json mapping, missing raid, wrong key, or an already-pending
     * countdown never consumes the key or overwrites the raid owner.
     */
    private void tryStartKeyedRaid(Player p, Item requiredKey, String wrongKeyMsg, boolean consumeKey) {
        // Wrong key: message the clicker, consume nothing.
        if (!p.getMainHandItem().is(requiredKey)) {
            sendMessageTo(p, wrongKeyMsg);
            return;
        }

        // A countdown is already pending or a raid controller is live: ignore this
        // click entirely (no opener hijack, no key consumed).
        if (raidControllerUUID != null || summonActionTickCount != 0) {
            return;
        }

        if (this.lootTable == null) {
            sendMessageTo(p, "{text.pomkotsmechs.messages.pomkotscube.02}");
            PomkotsMechs.LOGGER.error("Cube at {} has no loot table; cannot start raid.", worldPosition);
            return;
        }

        var dp = PomkotsDataPackManager.getInstance().getDataPack();
        var chestData = dp.getChestData(this.lootTable.toString());
        if (chestData == null) {
            // 16 of 22 shipped loot tables have no chest.json mapping. A null-deref
            // here used to NPE-crash the server; instead warn + log + do nothing.
            sendMessageTo(p, "{text.pomkotsmechs.messages.pomkotscube.02}");
            PomkotsMechs.LOGGER.error("No chest.json mapping for loot table {} (cube at {}); ignoring open.",
                    this.lootTable, worldPosition);
            return;
        }
        var raidData = dp.getRaidData(chestData.raid_id);
        if (raidData == null) {
            sendMessageTo(p, "{text.pomkotsmechs.messages.pomkotscube.02}");
            PomkotsMechs.LOGGER.error("No raid.json entry '{}' for cube loot table {}; ignoring open.",
                    chestData.raid_id, this.lootTable);
            return;
        }

        // ACTIVATE raids need an inactive boss nearby to be winnable.
        if ("activate".equals(raidData.type)) {
            var bosses = RaidControllerEntity.getInactiveBossesAroundPos(this.level, this.getBlockPos());
            if (bosses.isEmpty()) {
                sendMessageTo(p, "{text.pomkotsmechs.messages.pomkotscube.01}");
                return;
            }
        }

        // Commit: escrow the key (removed from hand, refunded on any cancellation),
        // set the raid owner, and start the countdown.
        if (consumeKey) {
            this.escrowedKey = p.getMainHandItem().split(1);
        }
        this.opener = p;
        this.summonActionTickCount = 1;
        setChanged();
    }

    public static void serverTick(Level level, BlockPos blockPos, BlockState blockState, PomkotsCubeBlockEntity entity) {
        entity.tick();
    }

    public void tick() {
        if (level == null || level.isClientSide) return;

        // Refund a key escrowed by a countdown that a reload interrupted.
        if (refundEscrowOnTick) {
            refundEscrowOnTick = false;
            refundEscrowedKey();
        }

        // Maintain the double-raid guard every tick: lazily resolve the controller
        // UUID with a grace period so a not-yet-loaded controller is not mistaken
        // for a finished raid (which would let a second raid start after a restart).
        tickRaidControllerGuard();

        if (this.mode == MODE_BLUE) {
            tryRefill();
            return;
        }

        if (summonActionTickCount > 0) {
            if (opener == null || !opener.isAlive()) {
                // Opener left/died during the countdown: cancel and refund the key.
                cancelPendingCountdown();
                return;
            }

            summonActionTickCount++;

            if (summonActionTickCount == 10) {
                spawnAlertEffect();
            } else if (summonActionTickCount > 50) {
                startRaid();
                summonActionTickCount = 0;
            }
        }
    }

    /** Lazily resolve the raid controller after a restart; only conclude it is gone
     *  once it has been unresolvable for the whole grace window. */
    private void tickRaidControllerGuard() {
        if (raidControllerUUID == null) {
            raidControllerEntity = null;
            raidControllerResolveGrace = 0;
            return;
        }
        if (!(level instanceof ServerLevel sl)) return;

        if (raidControllerEntity != null && raidControllerEntity.isAlive()) {
            raidControllerResolveGrace = 0;
            return;
        }
        if (sl.getEntity(raidControllerUUID) instanceof RaidControllerEntity rce && rce.isAlive()) {
            raidControllerEntity = rce;
            raidControllerResolveGrace = 0;
            return;
        }

        // Unresolved this tick: it may just be in an unloaded chunk. Only clear the
        // guard once the grace window elapses.
        raidControllerEntity = null;
        if (++raidControllerResolveGrace > RAID_CONTROLLER_RESOLVE_GRACE_TICKS) {
            raidControllerUUID = null;
            raidControllerResolveGrace = 0;
            setChanged();
        }
    }

    private void cancelPendingCountdown() {
        summonActionTickCount = 0;
        refundEscrowedKey();
        opener = null;
        setChanged();
    }

    /** Return the escrowed key to the opener if online, otherwise drop it at the cube. */
    private void refundEscrowedKey() {
        if (escrowedKey.isEmpty()) return;
        if (!(level instanceof ServerLevel)) return;

        ItemStack toReturn = escrowedKey;
        escrowedKey = ItemStack.EMPTY;

        if (opener instanceof ServerPlayer sp && sp.isAlive()) {
            sp.getInventory().placeItemBackInInventory(toReturn);
        } else {
            Block.popResource(level, worldPosition.above(), toReturn);
        }
        setChanged();
    }

    private void spawnAlertEffect() {
        AlertEntity e;

        if (getMode() == MODE_YELLOW) {
            e = new AlertEntity(PomkotsMechs.ALERT.get(), level);
        } else {
            e = new AlertEntity(PomkotsMechs.ALERTRED.get(), level);
        }

        var bp = this.getBlockPos();
        e.setPos(bp.getX() + 0.5, bp.getY(), bp.getZ() + 0.5);
        level.addFreshEntity(e);
    }

    private void startRaid() {
        // Any failure before the controller is spawned refunds the escrowed key.
        if (opener == null
                || this.lootTable == null
                || !this.lootTable.getNamespace().equals("pomkotsmechs")) {
            failRaidStart();
            return;
        }

        var dp = PomkotsDataPackManager.getInstance().getDataPack();
        var chestData = dp.getChestData(this.lootTable.toString());
        if (chestData == null) {
            PomkotsMechs.LOGGER.error("startRaid: no chest.json mapping for {}", this.lootTable);
            failRaidStart();
            return;
        }
        var raidData = dp.getRaidData(chestData.raid_id);
        if (raidData == null) {
            PomkotsMechs.LOGGER.error("startRaid: raid '{}' does not exist", chestData.raid_id);
            failRaidStart();
            return;
        }

        RaidControllerEntity rce = PomkotsMechs.RAID_CONTROLLER.get().create(level);
        if (rce == null) {
            PomkotsMechs.LOGGER.error("startRaid: failed to create raid controller");
            failRaidStart();
            return;
        }

        var raidEntityPos = getRaidEntitySpawnPos(raidData);
        rce.setPos(raidEntityPos.getX(), raidEntityPos.getY(), raidEntityPos.getZ());

        BlockPos cubePos = this.getBlockPos();
        // On success, flip the cube back to blue via a vanilla data command. The
        // ACTIVATE boss reset (formerly the unported pomkots:reset_boss_pos command)
        // is now handled directly in code on the controller, so no fail command.
        String successCommand = "data modify block " + cubePos.getX() + " " + cubePos.getY() + " " + cubePos.getZ()
                + " pomkotsmechsCubeMode set value " + MODE_BLUE;

        // Tell the controller which key to drop if it must fail closed at runtime
        // (unknown/invalid mob). Null for free (keyless) raids.
        String keyItemId = escrowedKey.isEmpty()
                ? null
                : BuiltInRegistries.ITEM.getKey(escrowedKey.getItem()).toString();

        CompoundTag tag = RaidControllerEntity.buildCompoundTag(
                chestData.raid_id, opener.getUUID(), successCommand, null);
        if (keyItemId != null) {
            tag.putString(PomkotsMechs.nbtName("RaidRefundKeyItem"), keyItemId);
        }
        rce.readAdditionalSaveData(tag);

        if (!level.addFreshEntity(rce)) {
            PomkotsMechs.LOGGER.error("startRaid: addFreshEntity rejected the raid controller");
            failRaidStart();
            return;
        }

        // Commit point: the controller exists. The escrowed key is now spent.
        this.escrowedKey = ItemStack.EMPTY;
        this.raidControllerUUID = rce.getUUID();
        this.raidControllerEntity = rce;
        this.raidControllerResolveGrace = 0;
        this.setChanged();
    }

    /** Abort a raid start before commit: refund the escrowed key, clear pending state. */
    private void failRaidStart() {
        refundEscrowedKey();
        this.opener = null;
        this.setChanged();
    }

    private BlockPos getRaidEntitySpawnPos(RaidDefinition raidData) {
        if (RaidControllerEntity.RaidType.SWEEP_BOSS_BOX.getId().equals(raidData.type)) {
            Direction facing = this.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction behind = facing.getOpposite();

            return this.getBlockPos().relative(behind, 15);
        } else {
            return this.getBlockPos();
        }

    }

    @Override
    public void unpackLootTable(Player player) {
        // Only a blue (unlocked) cube may ever materialize its loot. This is the
        // container-level gate: hoppers / hopper-minecarts call getItem ->
        // unpackLootTable(null) directly, bypassing the GUI, so a locked cube must
        // refuse to unpack no matter who asks.
        if (getMode() != MODE_BLUE) return;

        if (!level.isClientSide && refillLootTable == null) {
            refillLootTable = this.lootTable;

            super.unpackLootTable(player);

            compactStacksRespectNBT();

            setChanged();
        }
    }

    // Defense in depth against automation draining a locked cube: while the cube is
    // not blue, present an empty container to every reader/mutator.

    @Override
    public @NotNull ItemStack getItem(int slot) {
        if (getMode() != MODE_BLUE) return ItemStack.EMPTY;
        return super.getItem(slot);
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        if (getMode() != MODE_BLUE) return ItemStack.EMPTY;
        return super.removeItem(slot, amount);
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        if (getMode() != MODE_BLUE) return ItemStack.EMPTY;
        return super.removeItemNoUpdate(slot);
    }

    private void compactStacksRespectNBT() {
        NonNullList<ItemStack> items = this.getItems();

        List<ItemStack> merged = new ArrayList<>();

        // --- スタック統合 ---
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;

            for (ItemStack existing : merged) {
                if (ItemStack.isSameItemSameTags(existing, stack)
                        && existing.getCount() < existing.getMaxStackSize()) {

                    int move = Math.min(
                            stack.getCount(),
                            existing.getMaxStackSize() - existing.getCount()
                    );

                    existing.grow(move);
                    stack.shrink(move);

                    if (stack.isEmpty()) {
                        break;
                    }
                }
            }

            if (!stack.isEmpty()) {
                merged.add(stack.copy());
            }
        }

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, new Random());

        items.clear();

        for (int i = 0; i < merged.size() && i < slots.size(); i++) {
            items.set(slots.get(i), merged.get(i));
        }
    }

    private void compactStacksRespectNBT2() {
        List<ItemStack> merged = new ArrayList<>();

        for (int i = 0; i < this.getContainerSize(); i++) {
            ItemStack stack = this.getItem(i);
            if (stack.isEmpty()) continue;

            boolean mergedFlag = false;

            for (ItemStack existing : merged) {
                if (ItemStack.isSameItemSameTags(existing, stack)
                        && existing.getCount() < existing.getMaxStackSize()) {

                    int move = Math.min(
                            stack.getCount(),
                            existing.getMaxStackSize() - existing.getCount()
                    );

                    existing.grow(move);
                    stack.shrink(move);

                    if (stack.isEmpty()) {
                        mergedFlag = true;
                        break;
                    }
                }
            }

            if (!stack.isEmpty()) {
                merged.add(stack.copy());
            }
        }

        // 一旦全スロットクリア
        for (int i = 0; i < this.getContainerSize(); i++) {
            this.setItem(i, ItemStack.EMPTY);
        }

        // 再配置
        int slot = 0;
        for (ItemStack stack : merged) {
            while (!stack.isEmpty() && slot < this.getContainerSize()) {
                ItemStack split = stack.split(stack.getMaxStackSize());
                this.setItem(slot++, split);
            }
        }
    }

    private void tryRefill() {
        if (!openedOnce) return;
        if (level == null || level.isClientSide) return;

        long now = level.dayTime();

        if (now - lastRefillGameTime >= REFILL_INTERVAL) {
            refillLoot();
            lastRefillGameTime = now;
            openedOnce = false;
            updateMode(getDefaultMode());
            setChanged();
        }
    }

    private void refillLoot() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (refillLootTable == null) return;

        // 中身クリア
        this.clearContent();
        this.setLootTable(refillLootTable, new Random().nextLong());
        refillLootTable = null;

//        LootTable lootTable = serverLevel.getServer()
//                .getLootData()
//                .getLootTable(this.refillLootTable);
//
//        LootParams params = new LootParams.Builder(serverLevel)
//                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(this.worldPosition))
//                .create(LootContextParamSets.CHEST);
//
//        lootTable.fill(this, params, serverLevel.random.nextLong());
    }

    // ====================== アニメーション関係 ======================

    private boolean playLockedAnimation = false;

    public void playOpenFailAnimation() {
        if (this.level.isClientSide) {
            this.playLockedAnimation = true;
        }
    }

    private float prevOpeness = 0;

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, event -> {
            float curOpenness = event.getAnimatable().getOpenNess(event.getPartialTick());
            float prevOpenness = this.prevOpeness;

            this.prevOpeness = curOpenness;

            if (playLockedAnimation) {
                this.playLockedAnimation = false;
                event.getController().forceAnimationReset();
                return event.setAndContinue(RawAnimation.begin().thenPlay("animation.pomkotscube.openfail"));
            } else if (prevOpenness == 0 && curOpenness> 0) {
                return event.setAndContinue(RawAnimation.begin().thenPlayAndHold("animation.pomkotscube.open"));
            } else if (prevOpenness == 1 && curOpenness < 1) {
                return event.setAndContinue(RawAnimation.begin().thenPlayAndHold("animation.pomkotscube.close"));
            } else {
                return PlayState.CONTINUE;
            }
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public double getTick(Object blockEntity) {
        return level != null ? level.getGameTime() : 0;
    }

    // ====================== メッセージ関係 ======================

    /** Immediate feedback to the clicking player (not the raid owner). */
    private void sendMessageTo(Player p, String message) {
        if (p instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Utils.string2Component(message));
        }
    }
}
