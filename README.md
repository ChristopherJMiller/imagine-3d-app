# Imagine3D

Point your phone at a spot that needs a hook, clip or organizer, measure it, say what you want,
and get a 3D-printable STL. Everything runs **on the phone**:

- an open-weight LLM (llama.cpp) writes parametric OpenSCAD;
- OpenSCAD itself, compiled to WebAssembly, turns that into an STL. It runs in Android's
  headless JavaScriptSandbox, so it keeps working while the app is in the background.

```
ARCore photo + measurements → annotate / describe → vision model notes (optional)
  → Qwen3-4B writes OpenSCAD (helper library + few-shot) → OpenSCAD-wasm (Manifold) → STL
  → checks (compile errors, bed size, loose pieces) → automatic repair → 3D preview, sliders, refine, export
```

The goal and exit criteria are in [docs/GOAL.md](docs/GOAL.md). The model study is in
[docs/eval-results.md](docs/eval-results.md).

## Install on a phone (Pixel 9 Pro)

```sh
git clone --recursive https://github.com/ChristopherJMiller/imagine-3d-app   # llama.cpp is a submodule
cd imagine-3d-app
nix develop                                        # JDK 21, Android SDK 37 / NDK r29 / CMake, OpenSCAD, llama.cpp, ...
./gradlew assembleRelease -Pabis=arm64-v8a         # phone-only APK, ~67 MB (first build compiles llama.cpp, ~4 min)
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it sideloads. `./gradlew assembleDebug`
builds for arm64 and x86_64, so it also runs on the emulator (`nix develop .#emu`).

### Get a model

Either:

- **In the app:** open **Models** (chip icon, top right) and download **Qwen3 4B Instruct**
  (2.4 GB). Optionally also get the **Qwen3.5 2B photo reader** (1.9 GB).
- **From a computer**, which is faster if you already have the files:

```sh
adb shell mkdir -p /sdcard/Android/data/app.imagine3d/files/models
adb push Qwen3-4B-Instruct-2507-Q4_0.gguf /sdcard/Android/data/app.imagine3d/files/models/
# optional photo reader:
adb push Qwen3.5-2B-Q4_0.gguf /sdcard/Android/data/app.imagine3d/files/models/
adb push Qwen3.5-2B-mmproj-F16.gguf /sdcard/Android/data/app.imagine3d/files/models/   # rename of mmproj-F16.gguf
```

File names must match `app/src/main/assets/models/catalog.json`. `tools/eval/run_all.sh`'s
download list uses the same names.

### First run

The first design processes the ~2,300-token design guide once. Expect roughly 30 to 60 s on a
Tensor G4. The result is cached on disk (`files/prefix-cache`, about 330 MB), and the app starts
doing this as soon as you open the Describe screen. After that, a design is about 15 to 30 s of
writing plus well under a second of OpenSCAD.

## Using it

1. Tap **New idea**, then pick one:
   - **Measure with AR:** aim the centre dot at one end of something and tap **+**, then the
     other end and **+** again. Repeat for each size, then tap the camera button.
   - **Take a photo / Choose a photo:** you type the measurements yourself.
   - **Just describe it:** no photo.
2. **Describe:** the photo fills the screen, so scribble on it where the part goes. Type or
   dictate what you want on the card at the bottom; it rides up with the keyboard. Swipe the
   card up to name each measurement ("door thickness"). AR values are editable; check tight
   fits with a ruler.
3. **Design it:** the model names the part ("Bike Helmet Wall Hook") and writes OpenSCAD. The
   app compiles it and checks it, and failures go back to the model for up to two repairs.
   You can leave the app: a foreground service keeps the model running, shows progress in a
   notification (with Stop), and posts "… is ready" when done. Tap it to open the design.
4. **Review the result:**
   - **Adjust:** sliders for every top-level number, rebuilt live.
   - **Refine:** "make it 10 mm wider".
   - **Code:** edit the OpenSCAD and run it.
   - **Details:** timings, token counts, and what the vision model saw.
5. **Share** (to a slicer or Files) or **Save** the STL.

## Layout

| Path | What |
|---|---|
| `flake.nix` | Dev shells. `nix develop` builds; `nix develop .#emu` adds the emulator and a Play Store image (AVDs stay in `.avd/`). |
| `app/src/main/assets/scad/imagine.scad` | Print-friendly part library the model builds with (hooks, clips, holders, trays, cups, plugs, door stops, wedges, brackets…). Adding a module here, with the maths inside, is the best way to teach the app a new kind of part. |
| `app/src/main/assets/prompts/` | System prompt, user/repair/refine templates, fatal-diagnostic patterns. Shared with the eval harness. |
| `app/src/main/assets/web/` | three.js viewer and OpenSCAD 2026.10.15 WebAssembly (`tools/fetch-web-deps.sh` re-fetches with pinned hashes). OpenSCAD runs in androidx.javascriptengine via `scad-sandbox.js` / `ScadSandbox.kt`, falling back to a WebView worker (`scad-worker.js`). |
| `app/src/main/java/app/imagine3d/DesignService.kt` | Foreground service plus progress and done notifications while a design runs. |
| `app/src/main/java/app/imagine3d/ai/` | `DesignPipeline` (vision → generate → compile → check → repair), prompt and code helpers, STL checks. |
| `app/src/main/java/app/imagine3d/ar/` | ARCore measuring: GL camera background, centre-reticle hit tests, Compose overlay, frame capture. |
| `llama/` | Android library: llama.cpp (submodule `third_party/llama.cpp`, v0.7.0) + JNI with a persisted system-prompt cache and libmtmd vision. |
| `tools/eval/` | Host eval: `run_all.sh models/*.gguf` → `tools/eval/out/<model>/{results.json,sheet.png}`. |
| `tools/scadtest/` | Renders every library part. |
| `tools/webtest/` | Headless-Chromium test of the viewer + wasm (`cdp.py`) and a CDP evaluator for the app's WebView (`cdp_eval.py`). |
| `tools/emu/` | `shot.sh` (screenshots) and `ui.py` (tap and type by on-screen text) for driving the emulator. |

## Spike findings

- **On-device generation works.** With the helper library and a "which module for which
  request" guide, Qwen3-4B-Instruct (Q4_0, 2.4 GB) compiled on the first try for all 20 tuned
  eval requests, about 19 of which were visibly the right object. On 6 held-out requests, all
  compiled and 4 were right. Requests that match a library part (hooks, clips, holders, trays,
  plugs, door stops) are reliable. Freeform shapes are the weak spot, and Refine or the sliders
  fix those.
- **The library matters more than model size.** Qwen3.5-9B and Gemma 4 E4B did no better than
  the 4B. The big gains came from:
  - tested modules;
  - the selection guide;
  - a `// Plan:` line;
  - mesh checks (bed size, loose pieces) that feed the repair loop.
- **OpenSCAD on the phone is the real thing.** It's the official WebAssembly build with
  Manifold, and compiles take 20 to 200 ms. There's no CGAL or Qt port, and the LLM writes the
  OpenSCAD dialect it already knows.
- **Don't compile in a WebView page if work must continue in the background.** Chromium
  throttles hidden pages until their workers starve, and background compiles timed out.
  Android's JavaScriptSandbox runs the same wasm unthrottled, with two small shims
  (`crypto.getRandomValues`, `performance.now`).
- **AR measuring uses ARCore** (planes, then depth, then feature points), like Google's Measure
  app. Distances are good to a few millimetres at arm's length. That's fine for a hook's reach,
  but too coarse for a press fit, so the values stay editable.
- **The vision model is a helper, not the source of geometry.** A small VLM (Qwen3.5-2B) turns
  the annotated photo into a sentence like "attach at the top edge of the door near the red
  mark". The numbers come from measurements.

## Known limits / next steps

- **AR and phone speed are unverified.** AR measuring hasn't been tested on a real camera yet:
  on the emulator, ARCore's tracker aborts on the synthetic camera. Phone speed is also
  unmeasured; the Details tab reports it.
- **No GPU.** Inference is CPU-only; Mali Vulkan in llama.cpp is still unreliable.
- **Depth "profile scan".** Sample the depth map along a line to capture an edge profile (a
  shelf lip, a rail) as a polygon for snug-fitting clips.
- **Print orientation.** Rotate the preview to show "as installed" against "as printed".
- **Slicer hand-off.** Export 3MF with print settings.
- **Licences.** See [License](#license). Models are Apache-2.0 and are downloaded, not bundled.

## License

Copyright (C) 2026 Christopher Miller

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

**Additional permission under GNU GPL version 3 section 7.** If you modify this program, or any
covered work, by linking or combining it with the ARCore SDK or Google Play services libraries
(or a modified version of those libraries), containing parts covered by the terms of their
respective licenses, the licensors of this program grant you additional permission to convey
the resulting work.

Bundled third-party components and their licenses are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). OpenSCAD is GPL-2.0-or-later and is used here
under GPL-3.0; three.js and llama.cpp are MIT.
