package dev.umb.bridge.api;

/**
 * The outcome of one legacy onItemUse (func_77648_a) call: an item used ON a targeted block.
 * 1.7.10 returns a boolean AND mutates the passed ItemStack in place (FM-4) - both facts have to
 * cross the boundary, the same reasoning {@link ActivationResult} already applies to
 * onBlockActivated's boolean+GUI pair.
 */
public final class ItemUseResult {
    public final boolean handled;
    /** The stack AFTER legacy code ran; null only when the bridge could not run at all (unknown
     *  legacy item, not booted, or a throw) - the caller must then leave the held stack untouched. */
    public final StackData stack;

    public ItemUseResult(boolean handled, StackData stack) {
        this.handled = handled;
        this.stack = stack;
    }

    /** The legacy call declined (returned false), or could not run at all. */
    public static final ItemUseResult DECLINED = new ItemUseResult(false, null);
}
