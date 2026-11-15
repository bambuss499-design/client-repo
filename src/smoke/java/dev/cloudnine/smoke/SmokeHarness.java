package dev.cloudnine.smoke;

import dev.cloudnine.client.CloudNineScreen;
import dev.cloudnine.smoke.mixin.SmokeMouseInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only mod: drives Minecraft's registered GLFW callbacks, not selector methods. */
public final class SmokeHarness implements ClientModInitializer {
    private final String phase = System.getenv().getOrDefault("CLOUDNINE_SMOKE_PHASE", "");
    private int exitCountdown = -1;
    private int titleTicks;
    private int screenTicks;
    private boolean sentKey;
    private boolean wroteTitle;

    @Override public void onInitializeClient() {
        if (phase.isBlank()) return;
        Path game = FabricLoader.getInstance().getGameDir();
        Path output = game.resolve("cloudnine/smoke");
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
            if (screen instanceof CloudNineScreen) {
                ScreenMouseEvents.beforeMouseClick(screen).register((ignored, click) ->
                    System.out.println("CLOUDNINE_SMOKE: mouse event x=" + click.x() + " y=" + click.y() + " button=" + click.button()));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                Files.createDirectories(output);
                if (client.screen instanceof TitleScreen) {
                    if (!wroteTitle) { Files.writeString(output.resolve("title-ready"), "ready\n"); wroteTitle = true; }
                    if (phase.equals("stage") && !sentKey && ++titleTicks >= 25) {
                        pressRightAlt(client.getWindow().handle());
                        sentKey = true;
                        System.out.println("CLOUDNINE_SMOKE: sent Right Alt through Minecraft's GLFW callback");
                    }
                    if (exitCountdown < 0 && phase.equals("loaded")) {
                        if (!FabricLoader.getInstance().isModLoaded("cloudninefixture") || !Files.exists(game.resolve("cloudnine/fixture-loaded.txt"))) {
                            throw new IllegalStateException("Selected fixture did not initialize");
                        }
                        Files.writeString(output.resolve("loaded"), "Fabric loaded the selected fixture\n");
                        exitCountdown = 40;
                    }
                    if (exitCountdown < 0 && phase.equals("empty")) {
                        if (FabricLoader.getInstance().isModLoaded("cloudninefixture")) throw new IllegalStateException("Fixture was not one-shot");
                        Files.writeString(output.resolve("empty"), "Fixture absent on subsequent launch\n");
                        exitCountdown = 40;
                    }
                }
                if (phase.equals("stage") && client.screen instanceof CloudNineScreen screen) {
                    screenTicks++;
                    double scale = client.getWindow().getGuiScale();
                    if (screenTicks == 10) {
                        click(client, screen.firstRowCenterX() * scale, screen.firstRowCenterY() * scale);
                        System.out.println("CLOUDNINE_SMOKE: requested row click at " + screen.firstRowCenterX() + "," + screen.firstRowCenterY() + " scale=" + scale);
                    }
                    if (screenTicks == 15) {
                        Screenshot.grab(client.gameDirectory, "cloudnine-smoke.png", client.getMainRenderTarget(), 1,
                                result -> System.out.println("CLOUDNINE_SMOKE screenshot: " + result.getString()));
                    }
                    if (screenTicks == 20) {
                        Object selected = field(screen, "selected");
                        System.out.println("CLOUDNINE_SMOKE: selection=" + selected);
                        if (selected == null) throw new IllegalStateException("Fixture row click did not select an entry");
                    }
                    if (screenTicks == 45) {
                        click(client, screen.stageButtonCenterX() * scale, screen.stageButtonCenterY() * scale);
                        System.out.println("CLOUDNINE_SMOKE: requested stage click at " + screen.stageButtonCenterX() + "," + screen.stageButtonCenterY());
                    }
                    if (screenTicks == 75) {
                        System.out.println("CLOUDNINE_SMOKE: status=" + field(screen, "message") + " staging=" + field(screen, "staging"));
                    }
                    if (exitCountdown < 0) {
                        try (var files = Files.list(game.resolve("mods"))) {
                            if (files.anyMatch(path -> path.getFileName().toString().startsWith("cloudnine-once-")
                                    && path.toString().endsWith(".jar"))) {
                                Files.writeString(output.resolve("stage"), "Right Alt and UI click staged the fixture\n");
                                exitCountdown = 60;
                            }
                        }
                    }
                }
                if (exitCountdown >= 0 && --exitCountdown == 0) client.stop();
            } catch (Exception e) {
                try { Files.writeString(output.resolve("FAILED"), e.toString()); } catch (Exception ignored) { }
                client.stop();
            }
        });
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void pressRightAlt(long window) {
        GLFWKeyCallback callback = GLFW.glfwSetKeyCallback(window, null);
        if (callback == null) throw new IllegalStateException("Minecraft did not register a key callback");
        GLFW.glfwSetKeyCallback(window, callback);
        callback.invoke(window, GLFW.GLFW_KEY_RIGHT_ALT, 0, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_ALT);
        callback.invoke(window, GLFW.GLFW_KEY_RIGHT_ALT, 0, GLFW.GLFW_RELEASE, 0);
    }

    private static void click(Minecraft client, double x, double y) {
        long window = client.getWindow().handle();
        SmokeMouseInvoker input = (SmokeMouseInvoker) (Object) client.mouseHandler;
        input.cloudnineMove(window, x, y);
        MouseButtonInfo left = new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        input.cloudnineButton(window, left, GLFW.GLFW_PRESS);
        input.cloudnineButton(window, left, GLFW.GLFW_RELEASE);
    }
}
