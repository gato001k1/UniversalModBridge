package net.umb.fixtures.hello;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-side entrypoint twin of {@link FabricHelloMod}; registered under the "client" key. */
public class FabricHelloClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricHelloMod.MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[umb-fixture] hello from modern fabric fixture (client)");
    }
}
