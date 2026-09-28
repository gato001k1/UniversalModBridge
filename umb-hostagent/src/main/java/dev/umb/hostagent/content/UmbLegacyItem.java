package dev.umb.hostagent.content;

import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.UmbThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A real, native 26.2 Item that carries its 1.7.10 provenance.
 *
 * <p>PART 2 (INTERACTION-BRIDGE.md / GENERALIZATION-PLAN.md GAP 3): bridges the ITEM-side vanilla
 * interaction callbacks - igniters, detonators, mining charges, artillery remotes, wrenches,
 * screwdrivers and every other 1.7.10 item whose behaviour lives in the item's OWN
 * {@code onItemRightClick}/{@code onItemUse}, not in a block's {@code onBlockActivated}. Matches
 * PART E1's dispatch order exactly: {@link #useOn} (targeting a block, func_77648_a) is tried
 * before {@link #use} (general/air right-click, func_77659_a) by 26.2's own
 * {@code ServerPlayerGameMode.useItemOn}, the same order 1.7.10 uses.</p>
 */
public class UmbLegacyItem extends Item {

    private final ItemRec record;
    private final String legacyId;
    private static final Map<String, List<Component>> TOOLTIP_CACHE = new ConcurrentHashMap<>();

    public UmbLegacyItem(Item.Properties properties, ItemRec record) {
        super(properties);
        this.record = record;
        this.legacyId = record == null ? null : record.id;
    }

    public ItemRec getLegacyRecord() {
        return record;
    }

    public String getLegacyId() {
        return legacyId;
    }

    /** Item.func_77624_a -> Item.appendHoverText; works on client and server once the shared
     * legacy universe is booted. § formatting is split into native styled Components. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        if (record == null) {
            super.appendHoverText(stack, context, display, tooltip, flag);
            return;
        }
        LegacyBridge bridge = UmbBridgeHost.get();
        try {
            if (bridge == null || !bridge.isBooted()) {
                return;
            }
            // Creative search-tree construction calls this hook for thousands of stacks. An era
            // that is still behind BridgeRouter's readiness gate must not be booted or cached as
            // an empty answer from the render thread.
            if (bridge instanceof BridgeRouter router && !router.tooltipReady(legacyId)) {
                return;
            }
            CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
            String cacheKey = legacyId + "|" + stack.getDamageValue() + "|"
                    + (flag == TooltipFlag.ADVANCED) + "|" + (custom == null ? 0 : custom.hashCode());
            List<Component> cached = TOOLTIP_CACHE.get(cacheKey);
            if (cached != null) {
                cached.forEach(tooltip);
                return;
            }
            StackData data = LegacyStackConv.toLegacy(stack);
            List<String> lines = bridge.itemTooltip(legacyId, data, flag == TooltipFlag.ADVANCED);
            List<Component> converted = new ArrayList<>();
            for (String line : lines) {
                if (line != null) {
                    converted.add(legacyText(line));
                }
            }
            List<Component> stable = List.copyOf(converted);
            TOOLTIP_CACHE.putIfAbsent(cacheKey, stable);
            stable.forEach(tooltip);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.appendHoverText", t, 4);
        }
    }

    private static Component legacyText(String text) {
        MutableComponent out = Component.literal("");
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                if (plain.length() > 0) {
                    out.append(plain.toString());
                    plain.setLength(0);
                }
                ChatFormatting formatting = ChatFormatting.getByCode(text.charAt(++i));
                if (formatting != null) {
                    out = out.withStyle(formatting);
                }
            } else {
                plain.append(c);
            }
        }
        if (plain.length() > 0) {
            out.append(plain.toString());
        }
        return out;
    }

    /** Item.func_77663_a -> Item.inventoryTick. The bridge caches the legacy override check once
     * per item id and returns the post-callback stack snapshot. */
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        if (record == null || level == null) {
            return;
        }
        LegacyBridge bridge = UmbBridgeHost.get();
        if (bridge == null || !bridge.isBooted()) {
            return;
        }
        try {
            HostPlayerImpl player = entity instanceof ServerPlayer
                    ? new HostPlayerImpl((ServerPlayer) entity) : null;
            StackData after = bridge.itemInventoryTick(legacyId, LegacyStackConv.toLegacy(stack), player,
                    -1, slot != null);
            applyReturnedStack(stack, after);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.inventoryTick", t, 4);
        }
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        LegacyBridge bridge = UmbBridgeHost.get();
        if (record == null || bridge == null || !bridge.isBooted()) return 0;
        try {
            return bridge.itemUseDuration(legacyId, LegacyStackConv.toLegacy(stack));
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.getUseDuration", t, 4);
            return 0;
        }
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        LegacyBridge bridge = UmbBridgeHost.get();
        if (record == null || bridge == null || !bridge.isBooted()) return ItemUseAnimation.NONE;
        try {
            String action = bridge.itemUseAction(legacyId, LegacyStackConv.toLegacy(stack));
            return ItemUseAnimation.valueOf(action == null ? "NONE" : action.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.getUseAnimation", t, 4);
            return ItemUseAnimation.NONE;
        }
    }

    /**
     * 26.2 Item.getDestroySpeed -> the live legacy Item.func_150893_a answer.  The block id is
     * obtained only from the native state's registered provenance; an unmapped native block is
     * deliberately left to vanilla's implementation rather than guessed.
     */
    @Override
    public float getDestroySpeed(ItemStack stack, BlockState state) {
        LegacyBridge bridge = UmbBridgeHost.get();
        String blockId = legacyBlockId(state);
        if (record == null || bridge == null || !bridge.isBooted() || blockId == null) {
            return super.getDestroySpeed(stack, state);
        }
        try {
            float answer = bridge.itemDestroySpeed(legacyId, LegacyStackConv.toLegacy(stack), blockId);
            return Float.isFinite(answer) ? answer : super.getDestroySpeed(stack, state);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.getDestroySpeed", t, 4);
            return super.getDestroySpeed(stack, state);
        }
    }

    /**
     * 26.2 Item.isCorrectToolForDrops -> the live legacy Item.func_150897_b answer.  This is a
     * direct callback, not a harvest-tool reconstruction from snapshot metadata.
     */
    @Override
    public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        LegacyBridge bridge = UmbBridgeHost.get();
        String blockId = legacyBlockId(state);
        if (record == null || bridge == null || !bridge.isBooted() || blockId == null) {
            return super.isCorrectToolForDrops(stack, state);
        }
        try {
            return bridge.itemCanHarvestBlock(legacyId, LegacyStackConv.toLegacy(stack), blockId);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.isCorrectToolForDrops", t, 4);
            return super.isCorrectToolForDrops(stack, state);
        }
    }

    private static String legacyBlockId(BlockState state) {
        if (state == null) return null;
        Block block = state.getBlock();
        if (block instanceof UmbLegacyBlock legacy) return legacy.getLegacyId();
        String[] vanilla = HostWorldImpl.vanillaLegacyKey(block);
        return vanilla == null ? null : vanilla[0];
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (record == null) return;
        LegacyBridge bridge = UmbBridgeHost.get();
        if (bridge == null || !bridge.isBooted()) return;
        try {
            HostPlayerImpl player = entity instanceof ServerPlayer
                    ? new HostPlayerImpl((ServerPlayer) entity) : null;
            bridge.itemUsingTick(legacyId, LegacyStackConv.toLegacy(stack), player, remaining);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.onUseTick", t, 4);
        }
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        if (record == null) return false;
        LegacyBridge bridge = UmbBridgeHost.get();
        if (bridge == null || !bridge.isBooted()) return false;
        try {
            HostPlayerImpl player = entity instanceof ServerPlayer
                    ? new HostPlayerImpl((ServerPlayer) entity) : null;
            bridge.itemStoppedUsing(legacyId, LegacyStackConv.toLegacy(stack), player, remaining);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.releaseUsing", t, 4);
        }
        return false;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (record == null) return stack;
        LegacyBridge bridge = UmbBridgeHost.get();
        if (bridge == null || !bridge.isBooted()) return stack;
        try {
            HostPlayerImpl player = entity instanceof ServerPlayer
                    ? new HostPlayerImpl((ServerPlayer) entity) : null;
            StackData after = bridge.itemEaten(legacyId, LegacyStackConv.toLegacy(stack), player);
            return LegacyStackConv.toNative(after);
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.finishUsingItem", t, 4);
            return stack;
        }
    }

    private static void applyReturnedStack(ItemStack target, StackData after) {
        if (target == null || after == null) {
            return;
        }
        target.setCount(Math.max(0, after.count));
        if (target.isDamageableItem()) target.setDamageValue(Math.max(0, after.damage));
        ItemStack converted = LegacyStackConv.toNative(after);
        CustomData custom = converted.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            target.set(DataComponents.CUSTOM_DATA, custom);
        } else {
            target.remove(DataComponents.CUSTOM_DATA);
        }
    }

    /**
     * onItemUse (func_77648_a): fired when this item is used ON a targeted block, after that
     * block's own onBlockActivated declined (PART E1/E3 - {@link UmbLegacyBlock#useItemOn} already
     * ran onBlockActivated for the SAME click and returned {@code TRY_WITH_EMPTY_HAND} on decline,
     * which is what routes control here). hitX/hitY/hitZ are converted block-relative (FM-1, same
     * convention/helper as block activation).
     *
     * <p>When legacy onItemUse declines, the bridge additionally runs legacy onItemRightClick on
     * the same stack in the same dispatch (see {@code LegacyBridgeImpl.runDeclinedAirFallback}) -
     * 1.7.10's {@code Minecraft.func_147121_ag} (rightClickMouse) always runs the air use after a
     * declined block path in the same click, but 26.2 sends only USE_ITEM_ON for a ground click
     * and never synthesises that second half. Without it every right-click-only legacy item
     * (vehicles, crates, stations, bows, food, buckets, throwables) silently does nothing when
     * aimed at a block.</p>
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (record == null) {
            return super.useOn(context);
        }
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (context.getHand() != InteractionHand.MAIN_HAND) {
            // 1.7.10 models exactly one held item; off-hand has no legacy concept at this boundary -
            // documented gap, not a crash.
            return InteractionResult.PASS;
        }
        Player ctxPlayer = context.getPlayer();
        if (ctxPlayer == null) {
            // 1.7.10's onItemUse always receives a real EntityPlayer at this boundary (e.g. a
            // dispenser "using" an item is not modeled) - honest gap, fall through to vanilla.
            return super.useOn(context);
        }
        try {
            UmbThread.assertServer();
            if (!(level instanceof ServerLevel serverLevel) || !(ctxPlayer instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return InteractionResult.PASS;
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            HostPlayerImpl hostPlayer = new HostPlayerImpl(serverPlayer);
            BlockPos pos = context.getClickedPos();
            int side = context.getClickedFace() != null ? context.getClickedFace().get3DDataValue() : 0;
            Vec3 hitLoc = context.getClickLocation();
            float hitX = HitCoordsUtil.relative(hitLoc.x, pos.getX());
            float hitY = HitCoordsUtil.relative(hitLoc.y, pos.getY());
            float hitZ = HitCoordsUtil.relative(hitLoc.z, pos.getZ());
            ItemUseResult result = bridge.useItemOnBlock(legacyId, hostPlayer, pos.getX(), pos.getY(),
                    pos.getZ(), side, hitX, hitY, hitZ);
            if (result == null) {
                return InteractionResult.PASS;
            }
            if (result.stack != null) {
                // FM-4: func_77648_a mutates the passed ItemStack IN PLACE (damage/count) and never
                // returns a different one - apply onto the SAME native stack object context handed
                // us (a full hand replacement, as onItemRightClick below needs, is not applicable).
                // EXCEPTION: the declined-use air fallback (1.7.10 rightClickMouse order - a
                // declined onItemUse still runs onItemRightClick in the same click, see
                // LegacyBridgeImpl.runDeclinedAirFallback) may return a DIFFERENT item (empty
                // bucket -> water bucket). 1.7.10 writes the whole slot then
                // (PlayerControllerMP.func_78769_a), so replace the hand - but only when the new
                // id resolves to a real native stack, never guess-erase (FM-6).
                if (result.stack.legacyId != null && !result.stack.legacyId.equals(legacyId)
                        && !LegacyStackConv.toNative(result.stack).isEmpty()) {
                    hostPlayer.setHeldItem(result.stack);
                } else {
                    applyInPlaceMutation(context.getItemInHand(), result.stack);
                }
            }
            return result.handled ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.useOn", t, 4);
            return InteractionResult.PASS;
        }
    }

    /**
     * onItemRightClick (func_77659_a): fired when the player right-clicks holding this item and is
     * NOT targeting a block (or the targeted block declined - see {@link #useOn}'s javadoc for the
     * exact dispatch order). Unlike onBlockActivated there is no boolean "declined" concept in
     * 1.7.10 here - the method always returns SOME ItemStack, even a default no-op body just returns
     * the stack unchanged - so a real (non-throwing) bridge call always maps to a consuming result.
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (record == null) {
            return super.use(level, player, hand);
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        try {
            UmbThread.assertServer();
            if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            HostWorldImpl world = new HostWorldImpl(serverLevel);
            if (!UmbBridgeHost.ensureBooted(world)) {
                return InteractionResult.PASS;
            }
            LegacyBridge bridge = UmbBridgeHost.get();
            HostPlayerImpl hostPlayer = new HostPlayerImpl(serverPlayer);
            StackData result = bridge.useItemRightClick(legacyId, hostPlayer);
            if (result == null) {
                // FM-6-shaped: the bridge could not run at all (unknown legacy item, universe not
                // booted, or a throw) - leave the held stack exactly as it is, never guess-erase it.
                return InteractionResult.PASS;
            }
            // FM-5: the legacy call may have returned a DIFFERENT stack (e.g. a full bucket becoming
            // an empty one) - write the WHOLE hand slot back through setHeldItem, which already
            // carries the FM-6 anti-erasure guard (see its javadoc) for the "genuinely empty" case.
            hostPlayer.setHeldItem(result);
            return InteractionResult.SUCCESS_SERVER;
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyItem.use", t, 4);
            return InteractionResult.PASS;
        }
    }

    /** FM-4: apply the post-call legacy stack's count/damage onto the SAME native stack in hand. */
    private static void applyInPlaceMutation(ItemStack nativeStack, StackData legacyAfter) {
        if (nativeStack == null || nativeStack.isEmpty()) {
            return;
        }
        nativeStack.setCount(Math.max(0, legacyAfter.count));
        if (nativeStack.isDamageableItem()) {
            nativeStack.setDamageValue(Math.max(0, legacyAfter.damage));
        }
    }
}
