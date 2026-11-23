package com.flurben.flashback120.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.format.ReplayArchive;
import com.flurben.flashback120.format.ReplayFormat;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.recording.RecordingManager;
import com.flurben.flashback120.replay.ReplayHandler;
import com.mojang.blaze3d.platform.NativeImage;

/** The list of saved replays. */
public class ReplaySelectScreen extends Screen {

    private static final String[] SORTS = { "Date", "Name", "Length" };
    private static int sortMode;
    private static boolean sortDescending = true;

    private final Screen parent;
    private ReplayList list;
    private Button openButton, renameButton, deleteButton;
    private EditBox renameBox;
    private final Map<File, ResourceLocation> thumbnails = new HashMap<>();
    private final List<DynamicTexture> textures = new ArrayList<>();
    private String status;

    public ReplaySelectScreen(Screen parent) {
        super(Component.literal("Replays"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = new ReplayList(minecraft, width, height, 32, height - 64, 40);
        addWidget(list);
        reload();
        int cx = width / 2, y = height - 52;
        openButton = addRenderableWidget(Button.builder(Component.literal("Open Replay"), b -> openSelected())
            .bounds(cx - 154, y, 100, 20).build());
        renameButton = addRenderableWidget(Button.builder(Component.literal("Rename"), b -> startRename())
            .bounds(cx - 50, y, 100, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelected())
            .bounds(cx + 54, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Sort: " + SORTS[sortMode] + (sortDescending ? " v" : " ^")), b -> {
            if (sortDescending) sortDescending = false;
            else {
                sortDescending = true;
                sortMode = (sortMode + 1) % SORTS.length;
            }
            rebuildWidgets();
        }).bounds(cx - 154, y + 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Open Folder"), b -> Util.getPlatform().openFile(RecordingManager.replaysDir()))
            .bounds(cx - 50, y + 24, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx + 54, y + 24, 100, 20).build());
        int recoverable = RecordingManager.INSTANCE.findRecoverable().size();
        if (recoverable > 0) {
            addRenderableWidget(Button.builder(Component.literal("Recover (" + recoverable + ")"),
                b -> minecraft.setScreen(new RecoveryScreen(this))).bounds(width - 130, 6, 124, 20).build());
        }
        updateButtons();
    }

    private void reload() {
        List<ReplayEntry> entries = new ArrayList<>();
        File[] files = RecordingManager.replaysDir().listFiles((d, n) -> n.toLowerCase().endsWith(".zip"));
        if (files != null) {
            for (File f : files) {
                try {
                    entries.add(new ReplayEntry(f, ReplayArchive.readMeta(f)));
                } catch (IOException e) {
                    Flashback120.LOG.debug("Not a replay: {}", f, e);
                }
            }
        }
        Comparator<ReplayEntry> c = switch (sortMode) {
            case 1 -> Comparator.comparing(e -> e.meta.name.toLowerCase());
            case 2 -> Comparator.comparingInt(e -> e.meta.durationTicks);
            default -> Comparator.comparingLong(e -> e.meta.createdAt);
        };
        if (sortDescending) c = c.reversed();
        entries.sort(c);
        list.replace(entries);
    }

    private ReplayEntry selected() {
        return list.getSelected();
    }

    private void updateButtons() {
        boolean any = selected() != null;
        openButton.active = any;
        renameButton.active = any;
        deleteButton.active = any;
    }

    private void openSelected() {
        ReplayEntry e = selected();
        if (e == null) return;
        try {
            if (minecraft.level != null) return;
            ReplayHandler.open(e.file);
        } catch (Throwable t) {
            Flashback120.LOG.error("Unable to open replay {}", e.file, t);
            status = "Unable to open replay: " + t.getMessage();
            minecraft.setScreen(this);
        }
    }

    private void startRename() {
        ReplayEntry e = selected();
        if (e == null) return;
        renameBox = new EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, Component.literal("Name"));
        renameBox.setMaxLength(100);
        renameBox.setValue(e.meta.name);
        addRenderableWidget(renameBox);
        setFocused(renameBox);
    }

    private void finishRename() {
        ReplayEntry e = selected();
        String name = renameBox.getValue().trim();
        removeWidget(renameBox);
        renameBox = null;
        if (e == null || name.isEmpty()) return;
        try {
            e.meta.name = name;
            Map<String, byte[]> map = new HashMap<>();
            map.put(ReplayFormat.ENTRY_META, e.meta.toJson().getBytes(StandardCharsets.UTF_8));
            ReplayArchive.update(e.file, map);
            File target = RecordingManager.uniqueFile(e.file.getParentFile(), RecordingManager.sanitize(name), ".zip");
            if (!e.file.renameTo(target)) Flashback120.LOG.warn("Unable to rename {} to {}", e.file, target);
        } catch (IOException ex) {
            status = "Unable to rename: " + ex.getMessage();
        }
        rebuildWidgets();
    }

    private void deleteSelected() {
        ReplayEntry e = selected();
        if (e == null) return;
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes && !e.file.delete()) Flashback120.LOG.warn("Unable to delete {}", e.file);
            minecraft.setScreen(this);
        }, Component.literal("Delete this replay?"), Component.literal("'" + e.meta.name + "' will be lost forever.")));
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (renameBox != null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                finishRename();
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                removeWidget(renameBox);
                renameBox = null;
                return true;
            }
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        renderBackground(g);
        list.render(g, mouseX, mouseY, partialTicks);
        g.drawCenteredString(font, "Replays", width / 2, 12, 0xFFFFFF);
        if (list.children().isEmpty()) {
            g.drawCenteredString(font, "No replays yet. Record one from the pause menu in a world.", width / 2, height / 2 - 20, 0xA0A0A0);
        }
        if (status != null) g.drawCenteredString(font, status, width / 2, height - 64, 0xFF6060);
        if (renameBox != null) g.fill(width / 2 - 104, height / 2 - 24, width / 2 + 104, height / 2 + 14, 0xE0101014);
        super.render(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        for (DynamicTexture t : textures) t.close();
        textures.clear();
        thumbnails.clear();
    }

    private ResourceLocation thumbnail(File file) {
        if (thumbnails.containsKey(file)) return thumbnails.get(file);
        ResourceLocation id = null;
        try {
            byte[] png = ReplayArchive.readEntry(file, ReplayFormat.ENTRY_THUMBNAIL);
            if (png != null) {
                DynamicTexture texture = new DynamicTexture(NativeImage.read(png));
                textures.add(texture);
                id = minecraft.getTextureManager().register("flashback120_thumb", texture);
            }
        } catch (Throwable t) {
            Flashback120.LOG.debug("No thumbnail for {}", file, t);
        }
        thumbnails.put(file, id);
        return id;
    }

    final class ReplayEntry extends ObjectSelectionList.Entry<ReplayEntry> {

        final File file;
        final ReplayMeta meta;
        private long lastClick;

        ReplayEntry(File file, ReplayMeta meta) {
            this.file = file;
            this.meta = meta;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY,
            boolean hovered, float partialTicks) {
            ResourceLocation thumb = thumbnail(file);
            int tw = 64, th = 36;
            if (thumb != null) g.blit(thumb, left + 2, top + 1, 0, 0, tw, th, tw, th);
            else g.fill(left + 2, top + 1, left + 2 + tw, top + 1 + th, 0xFF202028);
            Minecraft mc = Minecraft.getInstance();
            int x = left + tw + 8;
            g.drawString(mc.font, meta.name, x, top + 2, 0xFFFFFF);
            String date = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(meta.createdAt));
            g.drawString(mc.font, date + "   " + ReplayMeta.formatTicks(meta.durationTicks)
                + (meta.serverAddress != null ? "   " + meta.serverAddress : "   singleplayer"), x, top + 14, 0xA0A0A0);
            g.drawString(mc.font, meta.mcVersion + "   recorded by " + meta.recordedBy + "   " + file.getName(), x, top + 25, 0x707070);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            list.setSelected(this);
            updateButtons();
            long now = Util.getMillis();
            if (now - lastClick < 250) openSelected();
            lastClick = now;
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(meta.name);
        }
    }

    final class ReplayList extends ObjectSelectionList<ReplayEntry> {

        ReplayList(Minecraft mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        void replace(List<ReplayEntry> entries) {
            replaceEntries(entries);
        }

        @Override
        public int getRowWidth() {
            return Math.min(520, width - 40);
        }

        @Override
        protected int getScrollbarPosition() {
            return width / 2 + getRowWidth() / 2 + 6;
        }
    }
}
