package dev.umb.legacy.legacyside;

import java.util.Collections;
import java.util.List;

import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;

/**
 * In-memory save-format facade for the isolated legacy universe.
 *
 * <p>The legacy lifecycle has a real {@code MinecraftServer} facade but it does
 * not own a disk-backed world.  Mods still use {@code getActiveAnvilConverter}
 * as a service locator for an {@link ISaveHandler}, especially while creating
 * tile entities.  Returning {@code null} from that server field turns an
 * otherwise valid tile into an unrelated NPE.  This implementation keeps the
 * no-disk contract while providing the honest empty-world answers.</p>
 */
final class UmbSaveFormat implements ISaveFormat {

    @Override
    public String func_154333_a() {
        return "umb-legacy";
    }

    @Override
    public ISaveHandler func_75804_a(String name, boolean storePlayerdata) {
        return new UmbSaveHandler();
    }

    @Override
    public List func_75799_b() {
        return Collections.emptyList();
    }

    @Override
    public void func_75800_d() {
    }

    @Override
    public WorldInfo func_75803_c(String name) {
        return null;
    }

    @Override
    public boolean func_154335_d(String name) {
        return false;
    }

    @Override
    public boolean func_75802_e(String name) {
        return false;
    }

    @Override
    public void func_75806_a(String name, String renamed) {
    }

    @Override
    public boolean func_154334_a(String name) {
        return false;
    }

    @Override
    public boolean func_75801_b(String name) {
        return false;
    }

    @Override
    public boolean func_75805_a(String name, IProgressUpdate progress) {
        return false;
    }

    @Override
    public boolean func_90033_f(String name) {
        return false;
    }
}
