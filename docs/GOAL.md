# Spike goal: photo + idea → printable 3D model, fully on-device

**Question the spike answers:** Can a Pixel 9 Pro go from "I'm standing in my apartment wishing
there was a hook here" to an STL I can send to a slicer, with all of the intelligence running
on-device on open-weight models?

## Pipeline

```
 ┌────────────┐   ┌──────────────┐   ┌───────────────┐   ┌───────────────┐   ┌──────────┐
 │ Capture    │──▶│ Annotate     │──▶│ Generate      │──▶│ Compile       │──▶│ Preview  │
 │ ARCore cam │   │ draw on photo│   │ llama.cpp     │   │ OpenSCAD WASM │   │ three.js │
 │ + measure  │   │ text / voice │   │ (open weights)│◀──│ (Manifold)    │   │ sliders  │
 │ (or photo) │   │ edit dims    │   │ → .scad code  │err│ → STL         │   │ export   │
 └────────────┘   └──────────────┘   └───────────────┘   └───────────────┘   └──────────┘
```

- **Capture:** ARCore session; reticle/tap to drop points (plane → depth → feature-point hits),
  live distance readout, shutter grabs the camera frame with measurement overlays. Fallback:
  system camera / gallery photo + manually typed measurements.
- **Annotate:** freehand marks on the photo, named measurements (editable values), description
  (typed or dictated).
- **Generate:** small open-weight LLM via llama.cpp (JNI, CPU, arm64 i8mm/dotprod) writes
  parametric OpenSCAD using a bundled helper library of print-friendly modules + few-shot
  examples. Compile errors are fed back for automatic repair.
- **Compile:** OpenSCAD compiled to WebAssembly running in a WebView worker (Manifold backend).
- **Preview/Export:** three.js viewer with print-bed grid and bounding-box dimensions;
  top-level variables become sliders (re-render without the LLM); "refine" asks the LLM for
  edits; STL shared via the Android share sheet.

## Exit criteria (the loop runs until these hold)

1. [x] `nix develop` + `./gradlew assembleDebug` produces an installable APK (arm64-v8a + x86_64);
       `./gradlew assembleRelease -Pabis=arm64-v8a` gives a 67 MB phone-only APK.
2. [x] Host eval: with the default model + prompt, ≥ 80% of the eval prompts produce OpenSCAD that
       compiles (with repair) and passes sanity checks. See `docs/eval-results.md`.
3. [x] Emulator, end to end:
       - typed idea → on-device LLM → WASM compile → 3D preview → sliders → save version →
         refine → STL share sheet;
       - photo idea → Qwen3.5-2B vision notes → design;
       - Stop button, prompt-cache reuse and the pieces-check repair loop.
4. [~] AR measuring on ARCore, with a graceful fallback when unavailable.
       - On the emulator the session starts and the overlay renders.
       - ARCore's own tracker then aborts on the emulator's synthetic camera, so the real-camera
         test has to happen on the Pixel.
       - The code was reviewed against Google's hello_ar sample.
5. [x] In-app model manager: Hugging Face download with progress (tested on the emulator),
       delete, choosing the active model, `.gguf` import, and `adb push` detection.
6. [x] README: build, install, first-run model download, spike findings, known limits, next steps.

## Non-goals for the spike

- Polished visuals, accounts, cloud sync, Play Store packaging.
- Photogrammetry / full scene reconstruction (measurements are the geometric input; a depth
  "profile scan" is a stretch goal).
- Perfect accuracy: ARCore distances are ±1 cm-ish; values stay user-editable.
