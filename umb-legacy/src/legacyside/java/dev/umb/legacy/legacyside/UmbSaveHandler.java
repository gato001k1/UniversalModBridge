package dev.umb.legacy.legacyside;

import java.io.File;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.WorldInfo;

/**
 * E step 4: {@code World.perWorldStorage} (a {@code MapStorage}, seeded in {@link UmbWorld#create}) needs a non-null {@code ISaveHandler} - not to persist anything ({@code dev.umb.bridge.api.HostWorld} has no file-persistence concept and this facade does not...
 */
final class UmbSaveHandler extends SaveHandler {

    /**
     * A number of 1.7.10 mods obtain the handler through ISaveFormat and then
     * (unfortunately, but compatibly with vanilla) cast it to SaveHandler.
     * Keep the universal no-disk facade usable by those callers as well.  The
     * superclass constructor only establishes its private path fields; every
     * persistence method is overridden below with the no-disk behavior.
     */
    UmbSaveHandler() {
        super(new File(System.getProperty("java.io.tmpdir"), "umb-legacy-save"),
                "umb-legacy", false);
    }

    @Override
    public WorldInfo func_75757_d() {
        return null;
    }

    @Override
    public void func_75762_c() {
    }

    @Override
    public IChunkLoader func_75763_a(WorldProvider provider) {
        return null;
    }

    @Override
    public void func_75755_a(WorldInfo info, NBTTagCompound tag) {
    }

    @Override
    public void func_75761_a(WorldInfo info) {
    }

    @Override
    public IPlayerFileData func_75756_e() {
        return null;
    }

    @Override
    public void func_75759_a() {
    }

    @Override
    public File func_75765_b() {
        return null;
    }

    /**
 * func_75758_b - getMapFileFromName.
 * Null: "no save file exists for this name", which routes MapStorage.func_75742_a's body straight to "return null" (no disk I/O attempted), the honest answer given HostWorld has no persistence primitive
 */
    @Override
    public File func_75758_b(String name) {
        return null;
    }

    @Override
    public String func_75760_g() {
        return "umb-legacy";
    }
}
