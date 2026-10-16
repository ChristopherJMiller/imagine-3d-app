# Can a phone-sized model write printable OpenSCAD?

Short answer: **yes, for the household-part range this app targets.** It works if the model is
given a library of tested, print-friendly modules and a guide to which module fits which
request, instead of being asked to invent geometry from scratch.

## Method

`tools/eval/run_eval.py` runs the same loop as the app:

- **Same inputs.** It reads the app's own prompt assets (`app/src/main/assets/prompts`) and the
  helper library `imagine.scad`.
- **Same engine.** It drives llama.cpp's `llama-server`, the same engine the app runs through JNI.
- **Compile and check.** Each design is compiled with OpenSCAD (Manifold backend), then checked
  with the same rules as the app:
  - compile errors and fatal warnings from `diagnostics.json`;
  - an empty mesh;
  - a part that won't fit a 250 mm bed or is thinner than 1 mm;
  - a part made of several pieces that don't touch.
- **Repair.** Failures go back to the model for up to 2 repair rounds.

On top of that, every case records:

- whether the measurements appear in the code;
- whether the expected kind of part was used (`expect` in `tools/eval/cases.json`);
- case-specific checks, e.g. "no screws" must become `holes = 0`, and "6 compartments" must
  come out as 6.

Each run also renders a contact sheet of every result, and I reviewed them all by eye, because
"compiles" and "is the right object" are different things.

There are 17 cases (`tools/eval/cases.json`):

- **Library parts:** hooks over a cabinet door, a wall hook, clips for a desk edge and a shelf
  edge, a closet-rod hook, a spray-bottle holder, a toothbrush holder, cable clips, a drawer
  organizer, a shelf bracket, a table-leg cap, a door wedge, a remote holder and a plant saucer.
- **Freeform shapes with no matching module:** a hex pencil cup, a monitor spacer and an
  SD-card holder.

## Results

### Model comparison (round 2 prompt, 17 cases)

| Model (GGUF) | Size | Compiles | First try | Right part (auto) | Right part (by eye) | Avg tokens |
|---|---|---|---|---|---|---|
| **Qwen3-4B-Instruct-2507** Q4_K_M | 2.5 GB | 17/17 | 16/17 | 17/17 | 16/17 | 167 |
| Gemma 4 E4B Q4_0 (+vision) | 4.6 GB | 16/17 | 15/17 | 15/17 | ~14/17 | 253 |
| Qwen3.5-9B Q4_K_M (+vision) | 5.7 GB | 16/17 | 16/17 | 15/17 | ~13/17 | 201 |
| Qwen3.5-4B Q4_0 (+vision) | 2.6 GB | 17/17 | 17/17 | 16/17 | ~11/17 | 242 |
| Qwen3.5-2B Q4_0 | 1.2 GB | 1 of first 6 | – | – | – | – |

The bigger and multimodal models were no better at this. Qwen3-4B-Instruct (Q4_0 on the phone)
is the default.

### Final prompt, Qwen3-4B-Instruct-2507 Q4_0 (the exact on-device file)

| Set | Cases | Compiles | First try | Right part + checks (auto) | Right object (by eye) |
|---|---|---|---|---|---|
| Tuned set (17 requests + 3 refinements) | 20 | 20/20 | 20/20 | 19/20 | ~19/20 |
| Held-out (written after tuning, 6 requests) | 6 | 6/6 | 6/6 | 5/6 | 4/6 |

- **What the refinement cases cover:** each one starts from a finished design, then asks for a
  change ("add a second hook", "40 mm wider with a divider", "use adhesive"), in the same
  conversation shape the app uses.
- **What the held-out set measured:**
  - All 6 compiled on the first try.
  - By eye, 3 of 6 were right on the first held-out run. After two general guide fixes (round
    tubes get `round = true`; anything that holds a long handle upright is a `ring_holder`),
    4 of 6 were right.
  - Since those fixes came from it, the held-out set is now partly seen.
- **Expectation for unseen requests:** something like 2 in 3 come out as the right object on the
  first try, with almost all compiling. The rest need a refinement or a slider tweak.
- **Part names:** the model also writes a `// Name:` line that becomes the idea's title. In the
  last run every successful design got a 2–6-word name ("Kitchen Cabinet Dish Towel Hook",
  "Bedroom Door Stop Wedge", "Broom Handle Wall Holder"), with no measurable cost to quality
  (19/20 and 6/6 compiled).
- **How much runs vary:** at temperature 0.3, run-to-run variation is a case or two per run.
  Temperature 0.15 was no better.

Contact sheets are in `tools/eval/out/<model><tag>/sheet.png`; that folder is not committed, so
re-run `tools/eval/run_all.sh` to regenerate them.

### What moved the numbers

1. **Round 1** (Qwen3.5-4B, small library, a phone-stand example built from a side profile): all
   14 compiled, but only about 9 were the right object. The leg cap, wedge and saucer were all
   bent into extruded side profiles, which is few-shot bias. A toothbrush holder came out as a
   rod hook.
2. **Round 2** added:
   - modules for `wedge`, `tube_plug` and `cup`;
   - a "which module for which request" table;
   - a required one-line `// Plan:` comment before the code;
   - rules for honouring "no screws" and compartment counts;
   - a freeform CSG example (a battery holder built with `difference()` and loops).

   Qwen3-4B went to 17/17 right part.
3. **Round 3** added the "one connected solid" check, since loose floating pieces were the most
   common silent failure, and switched to Q4_0 (the format KleidiAI accelerates on arm64).
4. **Rounds 4–8:**
   - **Refinement cases.** The model shifted a second hook along the wrong axis, so the guide now
     says profile parts lie on their side with their width along Z.
   - **Repair message.** The loose-pieces message now says how to join them.
   - **`door_stop(gap)`.** The model kept turning the gap into the wedge's *length*; a module
     that does that maths was the reliable fix.
   - **Small doc fixes:** `div=[2,1]` adds a divider, `cup`'s `d` is the container's own size,
     and union adds while difference only removes.
   - **One wording mistake.** A sentence warning that "a pencil's size is not the cup's size"
     primed the model *towards* pencil-sized cups, so it was removed.

**Lesson:** a small model is reliable at picking a module and passing measurements into it, and
unreliable at inventing geometry or doing arithmetic about shapes. Each new household-part
module (with the maths inside) is worth more than any prompt wording.

### Remaining failure modes

- **Freeform shapes with no matching module.** The hex pencil cup sometimes comes out
  pencil-sized, and the soap dish once came out as a closed cylinder. They compile and pass every
  automatic check, so Refine, the sliders and the code editor are the fix.
- **Plausible but wrong module.** The broom holder became a hanging rail hook instead of a
  wall ring.
- **Values derived by formula aren't sliders.** Only plain `name = number;` lines become
  sliders.
- **Measurements are passed through, not checked.** If AR says 23 mm and the door is really
  19 mm, the part will be loose.

## Speed

- **Host (RX 6600 via Vulkan):** about 30 tok/s; a design takes 4 to 15 s.
- **Emulator (x86_64, 3 threads, memory-starved):**
  - the first prompt prefill (2,350 tokens) took about 230 to 270 s;
  - generation ran at 2 to 3 tok/s;
  - an end-to-end design took about 6 to 7 minutes;
  - this is the worst case, not a phone estimate.
- **Expected on the Pixel 9 Pro (Tensor G4, 4 threads, KleidiAI):** extrapolated from published
  Tensor G4 numbers, not yet measured.
  - Decode: about 8 to 14 tok/s, so a typical 170-token design takes 12 to 20 s.
  - Prefill: about 50 to 80 tok/s. The ~2,300-token design guide is processed once and saved
    to disk (about 330 MB per model). After that, each request only prefills about 50 to 300
    new tokens.
  - The app starts loading the model and the guide as soon as you open the Describe screen, so
    the cost overlaps with typing.
- **Vision (Qwen3.5-2B, image capped at 384 tokens):** about 2 s on the host GPU; expect around
  15 to 40 s on the phone CPU. It only runs once per idea.

**Measure on device:** the Details tab shows tokens, tokens/s, prefill time and OpenSCAD time
for every design.
