# Third-party components

| Component | Where | License | Source |
|---|---|---|---|
| OpenSCAD 2026.10.15 snapshot, WebAssembly build (`openscad.js`, `openscad.wasm`) | `app/src/main/assets/web/openscad/` | GPL-2.0-or-later | https://github.com/openscad/openscad. The binary is from https://files.openscad.org/snapshots/OpenSCAD-2026.10.15-WebAssembly-web.zip (sha256 `01fa89a1d67ac484508ecc8168e6fe16b9fbeeea1089caf753de4f0e5448d435`); see `tools/fetch-web-deps.sh`. |
| three.js 0.186.0 (`three.module.js`, `three.core.js`, `OrbitControls.js`, `STLLoader.js`) | `app/src/main/assets/web/three/` | MIT (`LICENSE` in that folder) | https://github.com/mrdoob/three.js |
| llama.cpp v0.7.0 (git submodule) | `third_party/llama.cpp` | MIT | https://github.com/ggml-org/llama.cpp |
| ARCore SDK, AndroidX, Jetpack Compose, Kotlin libraries | Gradle dependencies | Apache-2.0 / Google APIs Terms (ARCore) | Maven |

Because the APK bundles the OpenSCAD WebAssembly binary, a built APK is distributed under the
terms of the GPL for that component.

Model weights are not part of this repository. The app downloads them on demand from Hugging
Face. The catalog (`app/src/main/assets/models/catalog.json`) lists Qwen3 / Qwen3.5 (Apache-2.0)
and Gemma 4 (Apache-2.0).
