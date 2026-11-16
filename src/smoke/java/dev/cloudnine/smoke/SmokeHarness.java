package dev.cloudnine.smoke;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only mod: drives Minecraft's registered GLFW callbacks, not selector methods. */
public final class SmokeHarness implements ClientModInitializer {
    private final String phase = System.getenv().getOrDefault("CLOUDNINE_SMOKE_PHASE", "");
    private final boolean dgclient = System.getenv().getOrDefault("CLOUDNINE_SMOKE_TARGET", "fixture").equals("dgclient");
    private int exitCountdown = -1;
    private int titleTicks;
    private int screenTicks;
    private int diagnosticTicks;
    private boolean sentKey;
    private boolean wroteTitle;
    private boolean dismissedWelcome;
    private boolean fixturePrepared;

    @Override public void onInitializeClient() {
        if (phase.isBlank()) return;
        Path game = FabricLoader.getInstance().getGameDir();
        Path output = game.resolve("cloudnine/smoke");
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
            if (isSelector(screen)) {
                ScreenMouseEvents.beforeMouseClick(screen).register((ignored, click) ->
                        System.out.println("CLOUDNINE_SMOKE: mouse event x=" + click.x() + " y=" + click.y() + " button=" + click.button()));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                Files.createDirectories(output);
                if (++diagnosticTicks % 100 == 0) {
                    System.out.println("CLOUDNINE_SMOKE: current screen=" + (client.screen == null ? "none" : client.screen.getClass().getName())
                            + " overlay=" + (client.getOverlay() == null ? "none" : client.getOverlay().getClass().getName()));
                }
                // A brand-new isolated production profile can start on the accessibility welcome.
                // Continue past it through its normal close handler before testing our own UI.
                if (!dismissedWelcome && client.getOverlay() == null
                        && client.screen instanceof AccessibilityOnboardingScreen welcome) {
                    dismissedWelcome = true;
                    welcome.onClose();
                    System.out.println("CLOUDNINE_SMOKE: continued past first-run accessibility welcome");
                }
                // The title screen exists underneath the Mojang splash: Minecraft intentionally
                // blocks mouse events until that loading overlay is actually gone.
                if (client.screen instanceof TitleScreen && client.getOverlay() == null) {
                    if (!wroteTitle) { Files.writeString(output.resolve("title-ready"), "ready\n"); wroteTitle = true; }
                    if (phase.equals("stage") && !sentKey && ++titleTicks >= 25) {
                        pressLeftAlt(client.getWindow().handle());
                        sentKey = true;
                        System.out.println("CLOUDNINE_SMOKE: sent Left Alt through Minecraft's GLFW callback");
                    }
                    if (exitCountdown < 0 && phase.equals("loaded")) {
                        if (dgclient ? !FabricLoader.getInstance().isModLoaded("aetherclient")
                                : !FabricLoader.getInstance().isModLoaded("cloudninefixture") || !Files.exists(game.resolve("cloudnine/fixture-loaded.txt"))) {
                            throw new IllegalStateException("Selected " + (dgclient ? "Dgclient" : "fixture") + " did not initialize");
                        }
                        if (FabricLoader.getInstance().isModLoaded("cloudnine") || !FabricLoader.getInstance().isModLoaded("cloudnine_return")) {
                            throw new IllegalStateException("Selector must be unloaded and return helper present in selected session");
                        }
                        Files.writeString(output.resolve("loaded"), "Fabric loaded the selected fixture\n");
                        exitCountdown = 40;
                    }
                    if (exitCountdown < 0 && phase.equals("empty")) {
                        if (FabricLoader.getInstance().isModLoaded("cloudninefixture") || FabricLoader.getInstance().isModLoaded("aetherclient")
                                || FabricLoader.getInstance().isModLoaded("cloudnine_return")
                                || !FabricLoader.getInstance().isModLoaded("cloudnine")) {
                            throw new IllegalStateException("Selector was not restored without the fixture and return helper");
                        }
                        Files.writeString(output.resolve("empty"), "Fixture absent on subsequent launch\n");
                        exitCountdown = 40;
                    }
                }
                if (phase.equals("stage") && client.getOverlay() == null && isSelector(client.screen)) {
                    var screen = client.screen;
                    screenTicks++;
                    double scale = client.getWindow().getGuiScale();
                    if (screenTicks == 5) {
                        var entries = (java.util.List<?>) field(screen, "entries");
                        var labels = new java.util.ArrayList<String>();
                        for (Object entry : entries) labels.add((String) call(entry, "label"));
                        var expected = java.util.List.of("67Client.zip", "Anubis.zip", "CorzClient.zip", "Dgclient.zip", "Gooba.zip", "Krispy.zip", "Waterv7.zip");
                        if (!labels.containsAll(expected)) throw new IllegalStateException("Drive catalog missing entries: " + labels);
                        // Filter for the synthetic fixture; the row and buttons are still driven by GLFW.
                        ((EditBox) field(screen, "search")).setValue(dgclient ? "Dgclient" : "Moonlit");
                    }
                    if (screenTicks == 10) {
                        click(client.getWindow().handle(), coordinate(screen, "firstRowCenterX") * scale, coordinate(screen, "firstRowCenterY") * scale);
                        System.out.println("CLOUDNINE_SMOKE: requested row click at " + coordinate(screen, "firstRowCenterX") + "," + coordinate(screen, "firstRowCenterY") + " scale=" + scale);
                    }
                    if (screenTicks == 20) {
                        Object selected = field(screen, "selected");
                        System.out.println("CLOUDNINE_SMOKE: source selection=" + selected);
                        String expected = dgclient ? "Dgclient.zip" : "Moonlit Fixture.zip";
                        if (selected == null || !call(selected, "label").equals(expected) || (boolean) call(selected, "canStage")) {
                            throw new IllegalStateException("The source-only catalog entry was not selected");
                        }
                        if (dgclient) {
                            Object library = field(screen, "library");
                            boolean canBuild = (boolean) library.getClass().getMethod("canBuild", selected.getClass()).invoke(library, selected);
                            if (!canBuild) throw new IllegalStateException("Dgclient source build is not offered");
                            click(client.getWindow().handle(), coordinate(screen, "stageButtonCenterX") * scale, coordinate(screen, "stageButtonCenterY") * scale);
                            if (field(screen, "confirmingBuild") == null || Boolean.TRUE.equals(field(screen, "staging"))) {
                                throw new IllegalStateException("Source build did not request confirmation first");
                            }
                        }
                    }
                    if (screenTicks == 25) {
                        String builtJar = dgclient ? "Dgclient.jar" : "Moonlit Fixture.jar";
                        Files.copy(game.resolve("cloudnine/smoke").resolve(builtJar),
                                game.resolve("cloudnine/clients").resolve(builtJar));
                        click(client.getWindow().handle(), coordinate(screen, "refreshButtonCenterX") * scale, coordinate(screen, "refreshButtonCenterY") * scale);
                        System.out.println("CLOUDNINE_SMOKE: added the prepared JAR and requested refresh");
                        fixturePrepared = true;
                    }
                    if (screenTicks == 35) {
                        Object selected = field(screen, "selected");
                        System.out.println("CLOUDNINE_SMOKE: paired selection=" + selected);
                        String expected = dgclient ? "Dgclient.zip" : "Moonlit Fixture.zip";
                        if (!fixturePrepared || selected == null || !call(selected, "label").equals(expected)
                                || !(boolean) call(selected, "canStage")) {
                            throw new IllegalStateException("Selected ZIP name or built-JAR pairing was lost");
                        }
                    }
                    if (screenTicks == 45) {
                        Screenshot.grab(client.gameDirectory, "cloudnine-smoke.png", client.getMainRenderTarget(), 1,
                                result -> System.out.println("CLOUDNINE_SMOKE paired screenshot: " + result.getString()));
                    }
                    if (screenTicks == 55) {
                        click(client.getWindow().handle(), coordinate(screen, "stageButtonCenterX") * scale, coordinate(screen, "stageButtonCenterY") * scale);
                        System.out.println("CLOUDNINE_SMOKE: requested stage click at " + coordinate(screen, "stageButtonCenterX") + "," + coordinate(screen, "stageButtonCenterY"));
                    }
                    if (screenTicks == 85) {
                        System.out.println("CLOUDNINE_SMOKE: status=" + field(screen, "message") + " staging=" + field(screen, "staging"));
                    }
                    if (screenTicks > 200 && Boolean.TRUE.equals(field(screen, "error")) && !Boolean.TRUE.equals(field(screen, "staging"))) {
                        throw new IllegalStateException("Selection failed: " + field(screen, "message"));
                    }
                    if (exitCountdown < 0 && Files.exists(game.resolve("cloudnine/handoff/next.properties"))) {
                        Files.writeString(output.resolve("stage"), "Left Alt and UI click prepared the isolated fixture\n");
                        exitCountdown = 60;
                    }
                }
                if (exitCountdown >= 0 && --exitCountdown == 0) client.stop();
            } catch (Exception e) {
                try { Files.writeString(output.resolve("FAILED"), e.toString()); } catch (Exception ignored) { }
                client.stop();
            }
        });
    }

    private static boolean isSelector(Object screen) {
        return screen != null && screen.getClass().getName().equals("dev.cloudnine.client.CloudNineScreen");
    }

    private static Object call(Object object, String name) throws ReflectiveOperationException {
        return object.getClass().getMethod(name).invoke(object);
    }

    private static int coordinate(Object screen, String name) throws ReflectiveOperationException {
        return (int) call(screen, name);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void pressLeftAlt(long window) {
        GLFWKeyCallback callback = GLFW.glfwSetKeyCallback(window, null);
        if (callback == null) throw new IllegalStateException("Minecraft did not register a key callback");
        GLFW.glfwSetKeyCallback(window, callback);
        callback.invoke(window, GLFW.GLFW_KEY_LEFT_ALT, 0, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_ALT);
        callback.invoke(window, GLFW.GLFW_KEY_LEFT_ALT, 0, GLFW.GLFW_RELEASE, 0);
    }

    private static void click(long window, double x, double y) {
        GLFWCursorPosCallback motion = GLFW.glfwSetCursorPosCallback(window, null);
        if (motion == null) throw new IllegalStateException("Minecraft did not register a cursor callback");
        GLFW.glfwSetCursorPosCallback(window, motion);
        motion.invoke(window, x, y);
        GLFWMouseButtonCallback button = GLFW.glfwSetMouseButtonCallback(window, null);
        if (button == null) throw new IllegalStateException("Minecraft did not register a mouse callback");
        GLFW.glfwSetMouseButtonCallback(window, button);
        button.invoke(window, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        button.invoke(window, GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
    }
}
