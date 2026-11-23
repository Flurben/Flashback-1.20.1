package com.flurben.flashback120;

import java.io.File;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flurben.flashback120.client.CameraPathRenderer;
import com.flurben.flashback120.client.KeyBindings;
import com.flurben.flashback120.client.Thumbnails;
import com.flurben.flashback120.gui.ReplaySelectScreen;
import com.flurben.flashback120.recording.Recorder;
import com.flurben.flashback120.recording.RecordingManager;
import com.flurben.flashback120.replay.ReplayHandler;

/** Entry point of Flashback 1.20.1, an independent replay mod for Fabric 1.20.1 based on FlashbackNH. */
public final class Flashback120 implements ClientModInitializer {

    public static final String MOD_ID = "flashback120";
    public static final String NAME = "Flashback 1.20.1";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static String version() {
        return FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");
    }

    /** {@code <game dir>/flashback120}: replays, caches and the editor's own files. */
    public static File rootDir() {
        File dir = FabricLoader.getInstance().getGameDir().resolve("flashback120").toFile();
        dir.mkdirs();
        return dir;
    }

    @Override
    public void onInitializeClient() {
        LOG.info("{} {} starting", NAME, version());
        Config.load();
        FileUtils.deleteQuietly(new File(rootDir(), "cache"));
        KeyBindings.register();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            RecordingManager.INSTANCE.onClientTick();
            KeyBindings.tick(mc);
        });

        WorldRenderEvents.END.register(context -> Thumbnails.afterWorldRender());
        WorldRenderEvents.AFTER_TRANSLUCENT.register(CameraPathRenderer::render);

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen) {
                Screens.getButtons(screen).add(Button.builder(Component.literal("Replays"),
                    b -> client.setScreen(new ReplaySelectScreen(screen))).bounds(width / 2 + 104, height / 4 + 48, 70, 20).build());
            } else if (screen instanceof PauseScreen pause && pause.showPauseMenu && !ReplayHandler.isReplaying()) {
                addRecordingButtons(client, screen, width, height);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommandManager.literal("flashback");
            root.then(ClientCommandManager.literal("start").executes(ctx -> {
                Minecraft.getInstance().execute(RecordingManager.INSTANCE::start);
                return 1;
            }));
            root.then(ClientCommandManager.literal("finish").executes(ctx -> {
                Minecraft.getInstance().execute(RecordingManager.INSTANCE::finish);
                return 1;
            }));
            root.then(ClientCommandManager.literal("pause").executes(ctx -> {
                Minecraft.getInstance().execute(RecordingManager.INSTANCE::togglePause);
                return 1;
            }));
            root.then(ClientCommandManager.literal("cancel").executes(ctx -> {
                Minecraft.getInstance().execute(RecordingManager.INSTANCE::cancel);
                return 1;
            }));
            root.then(ClientCommandManager.literal("mark").executes(ctx -> {
                Minecraft.getInstance().execute(() -> RecordingManager.INSTANCE.addMarker(0));
                return 1;
            }));
            dispatcher.register(root);
        });

        if (Boolean.getBoolean("flashback120.dev")) new com.flurben.flashback120.dev.DevDriver().install();
    }

    /** Recording controls in the pause menu, on the left of the vanilla buttons. */
    private static void addRecordingButtons(Minecraft client, net.minecraft.client.gui.screens.Screen screen, int width,
        int height) {
        int x = 6, y = 6;
        Recorder r = RecordingManager.INSTANCE.getCurrent();
        var buttons = Screens.getButtons(screen);
        if (r == null) {
            buttons.add(Button.builder(Component.literal("Start Recording"), b -> {
                RecordingManager.INSTANCE.start();
                client.setScreen(null);
            }).bounds(x, y, 100, 20).build());
        } else {
            buttons.add(Button.builder(Component.literal(r.isPaused() ? "Unpause Recording" : "Pause Recording"), b -> {
                RecordingManager.INSTANCE.togglePause();
                client.setScreen(null);
            }).bounds(x, y, 100, 20).build());
            buttons.add(Button.builder(Component.literal("Finish Recording"), b -> {
                client.setScreen(null);
                RecordingManager.INSTANCE.finish();
            }).bounds(x, y + 24, 100, 20).build());
            buttons.add(Button.builder(Component.literal("Cancel Recording"), b -> {
                RecordingManager.INSTANCE.cancel();
                client.setScreen(null);
            }).bounds(x, y + 48, 100, 20).build());
        }
    }
}
