# Porting status (work in progress)

Flashback 1.20.1 is an independent Fabric 1.20.1 port of **FlashbackNH** (MIT, by Flurben), which reimplements
Flashback's features from scratch. It contains **no code from Moulberry's Flashback**, whose source is
"all rights reserved / do not redistribute". An early attempt that copied Flashback's source was deleted, along with
its git history, before anything was published.

## Build setup
- Gradle 9.8.2, Loom 1.19-SNAPSHOT. Gradle runs on JDK 25 (`org.gradle.java.home` in `gradle.properties`). The mod
  compiles for Java 17.
- Mojang mappings, Fabric API 0.92.6+1.20.1, imgui-java 1.89.0 (MIT), bundled with jar-in-jar.

## Done (written, not compiled yet)
- Copied from FlashbackNH unchanged in logic: editor model (`editor/*`: keyframe tracks, per-keyframe ease in/out,
  weather track, camera presets, undo/redo `EditHistory`), export model (`export/*`: presets, estimator, stats,
  Auto encoder detection `VideoEncoders`, MP4 layout, ffmpeg/PNG encoders), replay format (`format/*`), tests.
- Rewritten for 1.20.1: `Config` (JSON), `net/PacketCodec`, `net/PacketIds` (looked up at runtime),
  `net/PacketBuilder`, `format/PacketChunks`, the `ReplayStream` indexer, `recording/*` (capture handler after
  decompression, recorder, world snapshot, local player, HUD state, manager), `replay/*` (connection,
  packet filter, camera entity, camera controller, replay handler, visuals applier), `util/Notifications`.

## Left to do
1. Access widener entries (fields used: `ConnectionProtocol.flows`, `PacketSet.getId`, `ClientPacketListener.registryAccess/
   playerInfoMap/listedPlayers`, `ClientChunkCache.storage` + `Storage.chunks`, `TagNetworkSerialization.serializeToNetwork`,
   `PlayerTabOverlay.header/footer`, `BossHealthOverlay.events`, `MultiPlayerGameMode.destroyBlockPos/destroyProgress/isDestroying`,
   `SynchedEntityData.itemsById`, `LivingEntity.lerp*`, `LevelRenderer.destroyingBlocks/destructionProgress`,
   `LightTexture.updateLightTexture`, `Timer.partialTick` is public). Then compile and fix errors.
2. Mixins: Timer.advanceTime (replay clock), Minecraft.tick head/return (pause gating), MultiPlayerGameMode.createPlayer
   (CameraEntity), GameRenderer.getFov + renderLevel (FOV, roll, shake), EntityRenderDispatcher.shouldRender
   (render filter), ParticleEngine.add, Gui (chat/scoreboard/tab/boss toggles), disconnect ignore, connection hook for
   auto-start, LevelChunk block change hook (`ReplayHandler.onChunkChanged`), sky toggle, block interaction off.
3. GUI: ImGui renderer + input bridge, `ReplayEditorScreen` (timeline, keyframe inspector with speed graph, Edit menu
   undo/redo, visuals, player list, camera presets), `ReplaySelectScreen`, `SaveReplayScreen`, recovery screen,
   export screen (preset tiles, estimates), pause-menu and title-screen buttons, keybinds, recording indicator, HUD
   renderer for the spectated recorder, camera path renderer.
4. Export pipeline: `Exporter`, frame readback, chunk wait (port from FlashbackNH).
5. Test in Prism (an instance with Fabric 1.20.1 exists: "Archipelago Fabric 1.20.1"; make a copy for testing).
6. README with the same independence disclaimer as FlashbackNH, then the GitHub repo and release.
