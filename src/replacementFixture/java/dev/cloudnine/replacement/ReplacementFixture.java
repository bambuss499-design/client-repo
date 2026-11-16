package dev.cloudnine.replacement;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;

/** Synthetic second client for the optional restart smoke test. */
public final class ReplacementFixture implements ClientModInitializer {
    @Override public void onInitializeClient() {
        try {
            var dir = FabricLoader.getInstance().getGameDir().resolve("cloudnine");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("replacement-loaded.txt"), "replacement initialized by Fabric\n");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
