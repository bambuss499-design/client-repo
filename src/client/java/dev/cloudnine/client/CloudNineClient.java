package dev.cloudnine.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cloudnine.library.ClientLibrary;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class CloudNineClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Cloud Nine");
    private static KeyMapping openKey;
    private static ClientLibrary library;
    private static String startupError;

    @Override
    public void onInitializeClient() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                // A new translation key avoids retaining the prototype's saved Right Alt
                // default in options.txt when an existing installation upgrades.
                "key.cloudnine.open_left", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("cloudnine", "main"))));
        try {
            var origin = FabricLoader.getInstance().getModContainer("cloudnine").orElseThrow().getOrigin();
            if (origin.getKind() != ModOrigin.Kind.PATH || origin.getPaths().size() != 1) {
                throw new IOException("Cloud Nine must be installed as a JAR in the mods folder.");
            }
            library = new ClientLibrary(Minecraft.getInstance().gameDirectory.toPath(), origin.getPaths().get(0));
        } catch (IOException | RuntimeException e) {
            startupError = "Library unavailable: " + e.getMessage();
            LOGGER.error("Could not initialize the client library", e);
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.screen == null) client.setScreen(new CloudNineScreen(null, library, startupError));
            }
        });
        // Vanilla key mappings are not polled while a Screen is open, including the title screen.
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
            if (screen instanceof CloudNineScreen) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, event) -> {
                if (matchesOpen(event)) {
                    client.setScreen(new CloudNineScreen(current, library, startupError));
                    return false;
                }
                return true;
            });
        });
    }

    static boolean matchesOpen(KeyEvent event) { return openKey != null && openKey.matches(event); }
}
