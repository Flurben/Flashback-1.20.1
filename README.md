# Flashback 1.20.1

Record your gameplay, play it back and make cinematic shots in **Minecraft 1.20.1 (Fabric)**.

Flashback 1.20.1 is an **unofficial, independent reimplementation** of the feature set of
[Flashback](https://github.com/Moulberry/Flashback), a replay mod for newer Fabric versions. It is a Fabric 1.20.1 port of
[FlashbackNH](https://github.com/Flurben/FlashbackNH), which was written from scratch. This mod contains **no code from
Flashback**. Flashback's source code is "all rights reserved", so it can't be ported or redistributed. This project is
not affiliated with or endorsed by Flashback or its author. Please don't report issues with this mod to the Flashback
project.

The mod is client-side only. It works in singleplayer and on servers, and the server doesn't need it.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.16 or newer for Minecraft 1.20.1, and
   [Fabric API](https://modrinth.com/mod/fabric-api) for 1.20.1.
2. Put `flashback-1.20.1-<version>.jar` from the [releases](https://github.com/Flurben/Flashback-1.20.1/releases) in
   your `mods` folder. You need Java 17 or newer.
3. Video export needs [ffmpeg](https://ffmpeg.org/download.html). Either put it on your `PATH` or set its path in the
   export screen's *Advanced* tab. PNG sequence export works without ffmpeg.

[Mod Menu](https://modrinth.com/mod/modmenu) is optional and opens the settings screen.

## Features

### Recording
- Start, pause, unpause, finish or cancel a recording from the pause menu, with keybinds or with `/flashback`.
- Optionally start recording automatically when you join a world or server, and finish when you leave.
- Works in singleplayer and multiplayer. On servers the exact network stream is recorded.
- Starting mid-session records a snapshot of the current world, including chunks, block entities, entities, the player
  list, tab header and footer, boss bars, scoreboard, world border, weather and time.
- Your own player is rebuilt from the client: movement, head rotation, arm swings, equipment, poses and block breaking
  progress.
- Your HUD is recorded and shown while you spectate yourself in the replay: hotbar, health, food, armor, absorption
  and XP.
- Markers come from a keybind or `/flashback mark`. Dimension changes are marked automatically.
- Crash safe: recordings are written continuously and can be recovered after a crash, from the *Recover* button on the
  replay list.

### Replay viewer and editor
- Open replays from the **Replays** button on the title screen. The list shows thumbnails and can be sorted, renamed
  and deleted.
- Free camera: hold the right mouse button over the world and use WASD, Space and Shift. Scroll to change speed and
  hold Ctrl to go faster. Momentum is optional.
- Play, pause, slow motion and fast forward, skip forwards and backwards, and seek to any point on the timeline.
- The timeline has markers, in and out points, zoom (Ctrl+scroll) and scroll.
- Keyframe tracks use Smooth, Linear, Ease In/Out, Ease In, Ease Out or Hold interpolation. Smooth movement never
  changes speed suddenly. Each keyframe's **Ease in** and **Ease out** set how long the camera takes to slow into it
  and speed away from it. The inspector shows a graph of the speed around the keyframe. The tracks are:
  - **Camera** (position, rotation and roll)
  - **FOV**
  - **Time of Day**
  - **Speed** (playback and export speed, for speed ramps and timelapses)
  - **Camera Shake**
  - **Weather** (rain and thunder strength, from 0 to 1)
- Edit keyframes: select, multi-select, drag, copy and paste, delete, set interpolation and easing per keyframe, type
  exact values, and use "set to current camera". The camera path shows in the world as a preview.
- **Undo and redo** work with Ctrl+Z, Ctrl+Y or Ctrl+Shift+Z, and from the Edit menu. They cover keyframes, easing,
  in and out points, markers, visuals and camera presets. Dragging a keyframe or a slider counts as one step.
- **Camera presets** follow a moving player or mob without keyframes:
  - **Orbit** circles the target.
  - **Follow** moves with the target.
  - **Look At** stays in one place and keeps the target in view.
- Player list: spectate any player in first person, hide or show players, or teleport the camera to them.
- Visuals: toggle chat, scoreboard, player list, boss bar, entities, players, particles, sky, clouds and nametags.
  You can also override FOV, time of day and weather, and add night vision, camera roll, camera shake and composition
  guides.
- The editor state (keyframes, in and out points, visuals, export settings) is saved inside the replay file.

### Export
- Renders the replay frame by frame at any resolution and frame rate, independent of your real FPS.
- The export screen (Ctrl+E) is laid out like the deliver page of a video editor:
  - **Render Settings** has preset tiles, then the Video, File and Advanced tabs.
  - **Output** shows the estimated file size and export time, length, frame count, encoder and bitrate, and the
    Render button.
  - **Timeline** sets the export range.
- **Presets**:
  - Built in: YouTube 1080p, YouTube 4K, Shorts / TikTok (vertical), Quick Preview, Editing (ProRes), PNG Frames and
    GIF.
  - *Save as* stores the current settings as your own preset. Right-click a tile to use, update, rename, duplicate or
    delete it.
  - User presets are kept in `.minecraft/flashback120/export-presets.json`.
- **Estimates** start from measured exports and learn the real speed and size per frame for each encoder after every
  finished export. *Reset estimates* in the Advanced tab forgets what was learned.
- **Formats**:
  - MP4 and MKV (H.264), WebM (VP9), MOV (ProRes 422 HQ) and GIF, all through ffmpeg.
  - PNG image sequences, built in.
- **Encoder** (MP4 and MKV):
  - *Auto* uses the graphics card's video encoder when ffmpeg can use one: NVENC, AMF, QSV or VideoToolbox. Each one
    is checked once by encoding a test frame. If none works, Auto uses x264 on the CPU.
  - You can also pick an encoder yourself. Quality follows the CRF setting.
- **MP4 index**:
  - *Standard* puts the index at the end of the file.
  - *Fragmented* leaves a playable file even if the game crashes mid-export.
  - *Fast start (web)* puts the index at the start, for playback in a browser before the file has downloaded.
- Before each frame is saved, the export waits for chunks to finish building, with both the vanilla renderer and Sodium.
- Speed keyframes, an option to hide nametags, a progress display, and Esc to cancel.
- Vsync and the frame limit are turned off while exporting and restored afterwards. Frames are read back from the GPU
  asynchronously.

## Building

```
./gradlew build
```

Gradle runs on JDK 25, which is set by `org.gradle.java.home` in `gradle.properties`, so change that path for your
machine. The mod itself compiles for Java 17. The jar goes to `build/libs/`.

`./gradlew test` runs the keyframe and camera preset unit tests.

### Test harness

If you start the game with `-Dflashback120.dev=true`, it reads commands from `<game dir>/fb120-dev/commands.txt` and
writes the results and screenshots next to that file. See `dev/DevDriver.java` for the commands. The harness was used
to test the mod in a Prism Launcher instance without anyone at the keyboard.

## What has been tested

These were run in the real game through Prism Launcher with Fabric Loader and Fabric API:
- Recording mid-session in singleplayer, from the pause menu and with commands.
- Saving and reopening replays.
- Playback speed, seeking forwards and backwards, and spectating the recorded player, with skin, nametag and HUD.
- Camera keyframes driving the camera, and undo and redo.
- The ImGui editor and the export screen, including presets, the Auto encoder choosing NVENC and the estimates.
- MP4 export through ffmpeg: frame counts were correct and frames were the right way up with correct lighting.
- PNG export.

Not tested yet: recording on a remote server (the raw network capture path), auto-start on join, crash recovery, and
Iris shader packs.

## License

MIT, see [LICENSE](LICENSE). imgui-java (MIT) is bundled.
