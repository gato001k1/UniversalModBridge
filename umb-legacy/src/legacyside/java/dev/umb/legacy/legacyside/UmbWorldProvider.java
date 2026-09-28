package dev.umb.legacy.legacyside;

import net.minecraft.world.WorldProvider;

/**
 * Seeds {@code World.field_73011_w} .
 * Unlike {@code World} itself, {@code WorldProvider}'s own constructor (: {@code public WorldProvider()}, body only allocates two small float arrays) is trivial and safe to run normally - no {@code Unsafe.allocateInstance}...
 */
public final class UmbWorldProvider extends WorldProvider {

    public UmbWorldProvider(int dimensionId) {
        super();
        this.field_76574_g = dimensionId;
    }

    @Override
    public String func_80007_l() {
        return "umb";
    }

    /** Forge's patched WorldProvider API; retained here for direct facade tests as well. */
    public int getHeight() {
        return 256;
    }

    /** The synthetic facade has no Nether ceiling, so its actual height is the full overworld height. */
    public int getActualHeight() {
        return 256;
    }
}
