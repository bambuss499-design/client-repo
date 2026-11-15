package dev.cloudnine.fixture;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;

public final class FixtureClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        try {
            var dir = FabricLoader.getInstance().getGameDir().resolve("cloudnine");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("fixture-loaded.txt"), "fixture initialized by Fabric\n");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
