package dev.cloudnine.client;

import dev.cloudnine.library.ClientLibrary;
import dev.cloudnine.library.LibraryEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** A deliberately self-contained screen: no untrusted jar is ever loaded by this UI. */
public final class CloudNineScreen extends Screen {
    private static final Identifier SKY = Identifier.fromNamespaceAndPath("cloudnine", "textures/gui/sky.png");
    private static final int WHITE = 0xFFF7F8FF;
    private static final int TEXT = 0xFFD6DEF5;
    private static final int MUTED = 0xFF91A0C4;
    private static final int LAVENDER = 0xFFB8B8FF;
    private static final int MINT = 0xFF96E9D4;
    private static final int AMBER = 0xFFFFCC95;
    private final Screen parent;
    private final ClientLibrary library;
    private final String startupError;
    private List<LibraryEntry> entries = List.of();
    private LibraryEntry selected;
    private EditBox search;
    private int offset;
    private String message = "";
    private boolean error;
    private boolean staging;

    private int x, y, panelWidth, panelHeight, leftX, leftWidth, rightX, rightWidth;
    private int listTop, listBottom, rowsTop, stageY, footerY;
    private static final int ROW_HEIGHT = 35;

    public CloudNineScreen(Screen parent, ClientLibrary library, String startupError) {
        super(Component.literal("Cloud Nine client library"));
        this.parent = parent;
        this.library = library;
        this.startupError = startupError;
    }

    @Override
    protected void init() {
        // Leave a generous frame of sky around the library on ordinary displays.
        panelWidth = Math.min(700, Math.max(240, width - (width >= 440 ? 80 : 20)));
        panelHeight = Math.min(410, Math.max(190, height - (height >= 280 ? 54 : 20)));
        x = (width - panelWidth) / 2;
        y = (height - panelHeight) / 2;
        int innerWidth = panelWidth - 28;
        leftX = x + 14;
        leftWidth = (innerWidth * 59) / 100;
        rightX = leftX + leftWidth + 10;
        rightWidth = innerWidth - leftWidth - 10;
        listTop = y + 77;
        listBottom = y + panelHeight - 43;
        rowsTop = listTop + 21;
        stageY = listBottom - 29;
        footerY = y + panelHeight - 30;
        String priorSearch = search == null ? "" : search.getValue();
        search = new EditBox(font, leftX + 28, y + 52, Math.max(30, leftWidth - 36), 14,
                Component.literal("Search clients"));
        search.setBordered(false);
        search.setMaxLength(80);
        search.setValue(priorSearch);
        search.setResponder(value -> offset = 0);
        addRenderableWidget(search);
        reload();
        if (startupError != null) { error = true; message = startupError; }
    }

    private void reload() {
        if (library == null) {
            error = true;
            message = startupError == null ? "The library could not be opened." : startupError;
            return;
        }
        try {
            entries = library.scan();
            if (selected != null) {
                String name = selected.file().getFileName().toString();
                selected = entries.stream().filter(entry -> entry.file().getFileName().toString().equals(name)).findFirst().orElse(null);
            }
            offset = Math.min(offset, Math.max(0, visibleEntries().size() - visibleRows()));
        } catch (IOException e) {
            error = true;
            message = "Could not read library: " + e.getMessage();
        }
    }

    private List<LibraryEntry> visibleEntries() {
        String query = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        return entries.stream().filter(entry -> entry.label().toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    private int visibleRows() { return Math.max(1, (listBottom - rowsTop - 4) / ROW_HEIGHT); }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // All layers are drawn together in render, avoiding vanilla's panorama/blur.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.blit(RenderPipelines.GUI_TEXTURED, SKY, 0, 0, 0.0F, 0.0F, width, height,
                1376, 768, 1376, 768, 0xFFFFFFFF);
        g.fill(0, 0, width, height, 0x66070B1B);
        rounded(g, x + 2, y + 5, panelWidth, panelHeight, 10, 0x77020612);
        rounded(g, x, y, panelWidth, panelHeight, 10, 0xB0202947);
        rounded(g, x + 1, y + 1, panelWidth - 2, panelHeight - 2, 9, 0xD60D1428);
        // Fine, luminous top edge and understated stars.
        g.fill(x + 15, y + 1, x + panelWidth - 15, y + 2, 0x887F91DD);
        star(g, x + panelWidth - 55, y + 15, 0xFF92A3E2);
        star(g, x + panelWidth - 78, y + 28, 0xFF5D6C9E);

        rounded(g, leftX, y + 14, 25, 25, 7, 0xFFB9BBFF);
        star(g, leftX + 12, y + 26, 0xFF1A2143);
        drawScaled(g, "CLOUD NINE", leftX + 35, y + 14, 1.20F, WHITE);
        draw(g, "YOUR CLIENTS, ABOVE THE CLOUDS", leftX + 35, y + 29, MUTED);
        button(g, x + panelWidth - 28, y + 14, 15, 15, "×", mouseX, mouseY, false);

        rounded(g, leftX, y + 48, leftWidth, 22, 6, 0xFF1A233C);
        g.fill(leftX + 9, y + 55, leftX + 15, y + 56, 0xFFAABCEB);
        g.fill(leftX + 8, y + 56, leftX + 9, y + 61, 0xFFAABCEB);
        g.fill(leftX + 15, y + 56, leftX + 16, y + 61, 0xFFAABCEB);
        g.fill(leftX + 9, y + 61, leftX + 15, y + 62, 0xFFAABCEB);
        g.fill(leftX + 15, y + 62, leftX + 18, y + 63, 0xFFAABCEB);
        if (search.getValue().isEmpty() && !search.isFocused()) {
            draw(g, clip("Search your library...", leftWidth - 36), leftX + 28, y + 52, MUTED);
        }
        rounded(g, leftX, listTop, leftWidth, listBottom - listTop, 7, 0xC9141D34);
        draw(g, "YOUR LIBRARY", leftX + 10, listTop + 8, 0xFFB8C7E8);
        String count = Integer.toString(visibleEntries().size());
        draw(g, count, leftX + leftWidth - 10 - font.width(count), listTop + 8, MUTED);

        List<LibraryEntry> visible = visibleEntries();
        if (visible.isEmpty()) {
            String line = entries.isEmpty() ? "A little empty up here." : "No clients match your search.";
            draw(g, clip(line, leftWidth - 24), leftX + 12, rowsTop + 12, TEXT);
            if (entries.isEmpty()) draw(g, clip("Add .jar files to your library.", leftWidth - 24), leftX + 12, rowsTop + 27, MUTED);
        } else {
            for (int i = 0; i < visibleRows() && i + offset < visible.size(); i++) {
                LibraryEntry entry = visible.get(i + offset);
                int rowY = rowsTop + i * ROW_HEIGHT;
                boolean activeRow = entry == selected;
                boolean hover = inside(mouseX, mouseY, leftX + 6, rowY, leftWidth - 12, 31);
                rounded(g, leftX + 6, rowY, leftWidth - 12, 31, 6,
                        activeRow ? 0xFF313C63 : (hover ? 0xFF26314E : 0xFF1D2741));
                rounded(g, leftX + 12, rowY + 5, 21, 21, 5, activeRow ? 0xFF8D9DF0 : 0xFF38496E);
                String initial = entry.label().isBlank() ? "?" : entry.label().substring(0, 1).toUpperCase(Locale.ROOT);
                draw(g, clip(initial, 8), leftX + 19, rowY + 12, WHITE);
                draw(g, clip(entry.label(), leftWidth - 57), leftX + 40, rowY + 6, WHITE);
                String sub = !entry.canStage() ? "NOT READY" : library != null && library.isPending(entry) ? "NEXT LAUNCH"
                        : library != null && library.isActive(entry) ? "ACTIVE THIS SESSION" : "FABRIC  ·  1.21.11";
                draw(g, clip(sub, leftWidth - 57), leftX + 40, rowY + 19, entry.canStage() ? MUTED : AMBER);
            }
            if (visible.size() > visibleRows()) draw(g, "SCROLL", leftX + leftWidth - 44, listBottom - 10, MUTED);
        }

        rounded(g, rightX, y + 48, rightWidth, listBottom - y - 48, 7, 0xDA182139);
        draw(g, "THE DETAILS", rightX + 11, y + 57, 0xFFB8C7E8);
        g.fill(rightX + 11, y + 70, rightX + rightWidth - 11, y + 71, 0xFF34415F);
        if (selected == null) {
            star(g, rightX + rightWidth / 2, y + 92, 0xFFB8C5FF);
            centered(g, "Choose a client", rightX + rightWidth / 2, y + 107, WHITE);
            centered(g, "to see the details", rightX + rightWidth / 2, y + 120, MUTED);
        } else {
            draw(g, clip(selected.label(), rightWidth - 22), rightX + 11, y + 80, WHITE);
            String status = selected.canStage() ? "READY TO STAGE" : "NOT AVAILABLE";
            draw(g, clip(status, rightWidth - 22), rightX + 11, y + 95, selected.canStage() ? MINT : AMBER);
            String detail = selected.canStage() ? "ID: " + selected.modId() : selected.problem();
            draw(g, clip(detail, rightWidth - 22), rightX + 11, y + 110, MUTED);
            if (selected.canStage()) {
                draw(g, clip("Dependencies are not installed for you.", rightWidth - 22), rightX + 11, y + 123, MUTED);
                if (stageY > y + 205) {
                    rounded(g, rightX + 10, y + 143, rightWidth - 20, 61, 6, 0xA3263250);
                    draw(g, clip("ONE SESSION, YOUR WAY", rightWidth - 36), rightX + 18, y + 153, LAVENDER);
                    draw(g, clip("1  Select a trusted client", rightWidth - 36), rightX + 18, y + 170, TEXT);
                    draw(g, clip("2  Restart Minecraft to use it", rightWidth - 36), rightX + 18, y + 184, TEXT);
                }
            }
        }
        boolean canStage = selected != null && selected.canStage() && library != null && !staging;
        button(g, rightX + 10, stageY, rightWidth - 20, 21, staging ? "WORKING..." : "USE FOR NEXT LAUNCH", mouseX, mouseY, canStage);

        g.fill(x + 14, footerY - 6, x + panelWidth - 14, footerY - 5, 0xFF2A3653);
        boolean hasPending = library != null && library.hasPending();
        String status = error ? message : staging ? "Updating your selection..."
                : hasPending ? "Restart to use " + library.pendingLabel() + " once."
                : library != null && !library.activeLabels().isEmpty() ? "Active this session; clears after exit."
                : !message.isEmpty() ? message : "One selection. One session. Existing mods stay untouched.";
        int reserved = hasPending ? 211 : 155;
        draw(g, clip(status, Math.max(15, panelWidth - reserved - 36)), leftX, footerY + 3, error ? AMBER : MUTED);
        if (hasPending) button(g, x + panelWidth - 207, footerY, 50, 16, "UNDO", mouseX, mouseY, false);
        button(g, x + panelWidth - 151, footerY, 55, 16, "REFRESH", mouseX, mouseY, false);
        button(g, x + panelWidth - 90, footerY, 76, 16, "OPEN FOLDER", mouseX, mouseY, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            double mx = click.x(), my = click.y();
            if (inside(mx, my, x + panelWidth - 28, y + 14, 15, 15)) { onClose(); return true; }
            if (inside(mx, my, x + panelWidth - 207, footerY, 50, 16) && library != null && library.hasPending() && !staging) {
                cancelPending();
                return true;
            }
            if (inside(mx, my, x + panelWidth - 151, footerY, 55, 16)) {
                reload(); message = "Library refreshed."; error = false; return true;
            }
            if (inside(mx, my, x + panelWidth - 90, footerY, 76, 16) && library != null) {
                Util.getPlatform().openFile(library.clientsDir().toFile());
                message = "Add compiled Fabric JARs, then refresh."; error = false; return true;
            }
            if (inside(mx, my, rightX + 10, stageY, rightWidth - 20, 21) && selected != null && selected.canStage() && library != null && !staging) {
                stageSelected();
                return true;
            }
            List<LibraryEntry> visible = visibleEntries();
            if (inside(mx, my, leftX + 6, rowsTop, leftWidth - 12, listBottom - rowsTop)) {
                int row = (int) ((my - rowsTop) / ROW_HEIGHT);
                if (row >= 0 && row < visibleRows() && row + offset < visible.size()) {
                    selected = visible.get(row + offset); return true;
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (inside(mouseX, mouseY, leftX, listTop, leftWidth, listBottom - listTop) && vertical != 0) {
            offset = Math.max(0, Math.min(offset + (vertical < 0 ? 1 : -1), Math.max(0, visibleEntries().size() - visibleRows())));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private void cancelPending() {
        staging = true;
        error = false;
        CompletableFuture.runAsync(() -> {
            try { library.cancelPending(); }
            catch (IOException e) { throw new CompletionException(e); }
        }).whenComplete((ignored, failure) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                staging = false;
                if (failure == null) { message = "Selection cancelled."; error = false; }
                else {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    message = "Could not cancel: " + cause.getMessage(); error = true;
                }
            });
        });
    }

    private void stageSelected() {
        LibraryEntry choice = selected;
        staging = true;
        error = false;
        CompletableFuture.runAsync(() -> {
            try { library.stage(choice); }
            catch (IOException e) { throw new CompletionException(e); }
        }).whenComplete((ignored, failure) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                staging = false;
                if (failure == null) { message = "Restart Minecraft to use your selection once."; error = false; }
                else {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    message = "Could not stage: " + cause.getMessage(); error = true;
                }
            });
        });
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (CloudNineClient.matchesOpen(event)) { onClose(); return true; }
        if (!search.isFocused() && (event.key() == GLFW.GLFW_KEY_DOWN || event.key() == GLFW.GLFW_KEY_UP)) {
            List<LibraryEntry> visible = visibleEntries();
            if (!visible.isEmpty()) {
                int current = selected == null ? -1 : visible.indexOf(selected);
                int next = Math.max(0, Math.min(visible.size() - 1, current + (event.key() == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
                selected = visible.get(next);
                offset = Math.max(0, Math.min(offset, next));
                if (next >= offset + visibleRows()) offset = next - visibleRows() + 1;
            }
            return true;
        }
        if (!search.isFocused() && event.key() == GLFW.GLFW_KEY_ENTER && selected != null && selected.canStage() && library != null && !staging) {
            stageSelected(); return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() { if (minecraft != null) minecraft.setScreen(parent); }
    @Override
    public boolean isPauseScreen() { return false; }

    // Public geometry also lets a test harness drive the real screen without bypassing input.
    public int firstRowCenterX() { return leftX + leftWidth / 2; }
    public int firstRowCenterY() { return rowsTop + 15; }
    public int stageButtonCenterX() { return rightX + rightWidth / 2; }
    public int stageButtonCenterY() { return stageY + 10; }

    private void draw(GuiGraphics g, String text, int xx, int yy, int color) { g.drawString(font, text, xx, yy, color, false); }
    private void centered(GuiGraphics g, String text, int xx, int yy, int color) { draw(g, text, xx - font.width(text) / 2, yy, color); }
    private void drawScaled(GuiGraphics g, String text, int xx, int yy, float scale, int color) {
        g.pose().pushMatrix(); g.pose().translate(xx, yy); g.pose().scale(scale, scale);
        g.drawString(font, text, 0, 0, color, false); g.pose().popMatrix();
    }
    private String clip(String text, int pixels) {
        if (pixels <= 0) return "";
        String safe = text == null ? "" : text.replace('§', ' ').replaceAll("[\\p{Cntrl}]", " ");
        if (font.width(safe) <= pixels) return safe;
        String ellipsis = "...";
        return font.plainSubstrByWidth(safe, Math.max(0, pixels - font.width(ellipsis))) + ellipsis;
    }
    private void button(GuiGraphics g, int bx, int by, int w, int h, String label, int mx, int my, boolean primary) {
        boolean hover = inside(mx, my, bx, by, w, h);
        int color = primary ? (hover ? 0xFFCBD0FF : 0xFFAAB7F6) : (hover ? 0xFF34415F : 0xFF25314A);
        rounded(g, bx, by, w, h, 5, color);
        centered(g, clip(label, w - 8), bx + w / 2, by + (h - 8) / 2, primary ? 0xFF141C38 : TEXT);
    }
    private static boolean inside(double mx, double my, int bx, int by, int w, int h) {
        return mx >= bx && mx < bx + w && my >= by && my < by + h;
    }
    private static void star(GuiGraphics g, int sx, int sy, int color) {
        g.fill(sx - 1, sy - 3, sx + 1, sy + 4, color); g.fill(sx - 3, sy - 1, sx + 4, sy + 1, color);
    }
    private static void rounded(GuiGraphics g, int rx, int ry, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w / 2, h / 2));
        g.fill(rx + r, ry, rx + w - r, ry + h, color);
        g.fill(rx, ry + r, rx + r, ry + h - r, color);
        g.fill(rx + w - r, ry + r, rx + w, ry + h - r, color);
        for (int dy = 0; dy < r; dy++) {
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - (r - dy - 0.5) * (r - dy - 0.5)));
            g.fill(rx + inset, ry + dy, rx + r, ry + dy + 1, color);
            g.fill(rx + w - r, ry + dy, rx + w - inset, ry + dy + 1, color);
            g.fill(rx + inset, ry + h - dy - 1, rx + r, ry + h - dy, color);
            g.fill(rx + w - r, ry + h - dy - 1, rx + w - inset, ry + h - dy, color);
        }
    }
}
