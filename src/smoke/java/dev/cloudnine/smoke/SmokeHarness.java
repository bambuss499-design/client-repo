package dev.cloudnine.smoke;

import dev.cloudnine.client.CloudNineScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFWNativeX11;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only mod. It observes real GUI events; xdotool performs the inputs. */
public final class SmokeHarness implements ClientModInitializer {
    private final String phase = System.getenv().getOrDefault("CLOUDNINE_SMOKE_PHASE", "");
    private int exitCountdown = -1;
    private boolean wroteLayout;
    private boolean wroteTitle;

    @Override public void onInitializeClient() {
        if (phase.isBlank()) return;
        Path game = FabricLoader.getInstance().getGameDir();
        Path output = game.resolve("cloudnine/smoke");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                Files.createDirectories(output);
                if (client.screen instanceof TitleScreen && !wroteTitle) {
                    // X11's native window ID is more reliable than guessing the development window title.
                    long window = GLFWNativeX11.glfwGetX11Window(client.getWindow().handle());
                    Files.writeString(output.resolve("title-ready"), Long.toUnsignedString(window));
                    wroteTitle = true;
                }
                if (phase.equals("stage") && client.screen instanceof CloudNineScreen screen && !wroteLayout) {
                    double scale = client.getWindow().getGuiScale();
                    String coords = "ROW_X=" + Math.round(screen.firstRowCenterX() * scale)
                            + "\nROW_Y=" + Math.round(screen.firstRowCenterY() * scale)
                            + "\nSTAGE_X=" + Math.round(screen.stageButtonCenterX() * scale)
                            + "\nSTAGE_Y=" + Math.round(screen.stageButtonCenterY() * scale) + "\n";
                    Files.writeString(output.resolve("layout.env"), coords);
                    wroteLayout = true;
                }
                if (phase.equals("stage") && wroteLayout && exitCountdown < 0) {
                    try (var files = Files.list(game.resolve("mods"))) {
                        if (files.anyMatch(path -> path.getFileName().toString().startsWith("cloudnine-once-")
                                && path.toString().endsWith(".jar"))) {
                            Files.writeString(output.resolve("staged"), "selected via the real screen\n");
                            exitCountdown = 40;
                        }
                    }
                }
                if (client.screen instanceof TitleScreen && exitCountdown < 0 && phase.equals("loaded")) {
                    if (!FabricLoader.getInstance().isModLoaded("cloudninefixture") || !Files.exists(game.resolve("cloudnine/fixture-loaded.txt"))) {
                        throw new IllegalStateException("Selected fixture did not initialize");
                    }
                    Files.writeString(output.resolve("loaded"), "Fabric loaded the selected fixture\n");
                    exitCountdown = 40;
                }
                if (client.screen instanceof TitleScreen && exitCountdown < 0 && phase.equals("empty")) {
                    if (FabricLoader.getInstance().isModLoaded("cloudninefixture")) throw new IllegalStateException("Fixture was not one-shot");
                    Files.writeString(output.resolve("empty"), "Fixture absent on subsequent launch\n");
                    exitCountdown = 40;
                }
                if (exitCountdown >= 0 && --exitCountdown == 0) client.stop();
            } catch (Exception e) {
                try { Files.writeString(output.resolve("FAILED"), e.toString()); } catch (Exception ignored) { }
                client.stop();
            }
        });
    }
}
