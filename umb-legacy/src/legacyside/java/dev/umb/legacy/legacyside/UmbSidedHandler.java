package dev.umb.legacy.legacyside;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.IFMLSidedHandler;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.StartupQuery;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.registry.LanguageRegistry;
import cpw.mods.fml.relauncher.Side;

import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

/**
 * The SERVER-flavoured {@code IFMLSidedHandler} for a headless legacy universe.
 *
 * <p>Modelled on {@code cpw.mods.fml.server.FMLServerHandler}. The server is bound after the
 * synthetic world facade is available, before lifecycle events are fired. Standalone probes
 * retain SERVER; the integrated host passes {@code umb.legacy.side=CLIENT} so client-only
 * coremods see the same side as the real 26.2 client.</p>
 */
public final class UmbSidedHandler implements IFMLSidedHandler {

    private final File gameDir;
    private final Side side;
    private MinecraftServer server;

    public UmbSidedHandler(File gameDir) {
        this.gameDir = gameDir;
        this.side = "CLIENT".equalsIgnoreCase(System.getProperty("umb.legacy.side", "SERVER"))
                ? Side.CLIENT : Side.SERVER;
    }

    @Override
    public List<String> getAdditionalBrandingInformation() {
        return Collections.singletonList("umb-legacy headless");
    }

    @Override
    public Side getSide() {
        return side;
    }

    @Override
    public void haltGame(String message, Throwable exception) {
        throw new RuntimeException(message, exception);
    }

    @Override
    public void showGuiScreen(Object clientGuiElement) {
        // server side: nothing to show
    }

    @Override
    public void queryUser(StartupQuery query) throws InterruptedException {
        // no console, no commands: log it and take the default so loading never blocks
        FMLLog.warning("[umb-legacy] startup query auto-answered: %s", query.getText());
        query.finish();
    }

    @Override
    public void beginServerLoading(MinecraftServer server) {
        // the lifecycle is driven explicitly by LegacyDriver
    }

    @Override
    public void finishServerLoading() {
        // the lifecycle is driven explicitly by LegacyDriver
    }

    @Override
    public File getSavesDirectory() {
        return new File(gameDir, "saves");
    }

    @Override
    public MinecraftServer getServer() {
        return server;
    }

    /** Binds the synthetic server before FMLServerStartingEvent is dispatched. */
    void bindServer(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("server");
        }
        if (this.server != null && this.server != server) {
            throw new IllegalStateException("legacy sided handler already has a different server");
        }
        this.server = server;
    }

    @Override
    public boolean shouldServerShouldBeKilledQuietly() {
        return false;
    }

    @Override
    public void addModAsResource(ModContainer container) {
        LanguageRegistry.instance().loadLanguagesFor(container, side);
    }

    @Override
    public String getCurrentLanguage() {
        return "en_US";
    }

    @Override
    public void serverStopped() {
        // no server
    }

    @Override
    public NetworkManager getClientToServerNetworkManager() {
        throw new RuntimeException("umb-legacy headless universe has no client<->server channel");
    }

    @Override
    public INetHandler getClientPlayHandler() {
        return null;
    }

    @Override
    public void waitForPlayClient() {
        // no client
    }

    @Override
    public void fireNetRegistrationEvent(EventBus bus, NetworkManager manager, Set<String> channelSet,
                                         String channel, Side side) {
        // no network stack is started in
    }

    @Override
    public boolean shouldAllowPlayerLogins() {
        return false;
    }

    @Override
    public void allowLogins() {
        // no logins
    }

    @Override
    public void processWindowMessages() {
        // no window, ever
    }

    @Override
    public String stripSpecialChars(String message) {
        return message;
    }
}
