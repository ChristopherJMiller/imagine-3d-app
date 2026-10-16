#!/usr/bin/env python3
"""Host-side eval: can a small open-weight model write printable OpenSCAD for real requests?

Uses the exact prompt assets the Android app ships (app/src/main/assets/prompts) and the same
helper library, runs the model in llama-server (same llama.cpp engine as on device), compiles
with OpenSCAD (Manifold) and feeds failures back for repair, like the app does.

  python3 tools/eval/run_eval.py --model models/Qwen3.5-4B-Q4_0.gguf [--cases id1,id2] [--repairs 2]
"""
import argparse, json, os, pathlib, re, signal, subprocess, sys, tempfile, time
import requests

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets"
PROMPTS = ASSETS / "prompts"
LIB = ASSETS / "scad"
BED = 250.0


def fmt_mm(v):
    return f"{v:g}"


def build_user_message(case):
    tmpl = (PROMPTS / "user.md").read_text()
    meas = ""
    if case.get("measurements"):
        meas = "Measurements:\n" + "".join(f"- {m['label']}: {fmt_mm(m['mm'])} mm\n" for m in case["measurements"])
    notes = f"Notes: {case['notes']}\n" if case.get("notes") else ""
    return tmpl.replace("{{request}}", case["request"]).replace("{{measurements}}", meas).replace("{{notes}}", notes).strip() + "\n"


def extract_code(text):
    blocks = re.findall(r"```(?:openscad|scad)?\s*\n(.*?)```", text, re.S)
    if blocks:
        return max(blocks, key=len)
    m = re.search(r"```(?:openscad|scad)?\s*\n(.*)", text, re.S)  # unterminated block
    return m.group(1) if m else text


PARTS = ["wall_hook", "over_door_hook", "shelf_clip_hook", "rail_hook", "ring_holder", "cable_clip", "tray", "cup",
         "l_bracket", "wedge", "door_stop", "tube_plug", "mount_plate"]
HELPERS = ["stroke", "profile_part", "rounded_cube", "rounded_square", "tube", "screw_hole"]

FATAL = [re.compile(p) for p in json.loads((PROMPTS / "diagnostics.json").read_text())["fatal"]]


def compile_scad(code, workdir, name):
    scad = workdir / f"{name}.scad"
    stl = workdir / f"{name}.stl"
    scad.write_text(code)
    if stl.exists():
        stl.unlink()
    env = dict(os.environ, OPENSCADPATH=str(LIB))
    t0 = time.time()
    p = subprocess.run(["openscad", "--backend=manifold", "--export-format=binstl", "-o", str(stl), str(scad)],
                       capture_output=True, text=True, env=env, timeout=180)
    dt = time.time() - t0
    lines = [l for l in (p.stdout + p.stderr).splitlines() if l.strip()]
    fatal = [l for l in lines if any(r.search(l) for r in FATAL)]
    ok = p.returncode == 0 and not fatal and stl.exists() and stl.stat().st_size > 84
    return ok, fatal or ([l for l in lines if "ERROR" in l or "WARNING" in l][-5:] if not ok else []), stl, dt


def mesh_stats(stl):
    import trimesh
    m = trimesh.load(stl)
    ext = [float(x) for x in m.extents]
    pieces = len(m.split(only_watertight=False))
    return {"extents": [round(x, 1) for x in ext], "watertight": bool(m.is_watertight), "volume_cm3": round(float(m.volume) / 1000, 1),
            "pieces": pieces}


def sanity_problems(stats):
    probs = []
    ext = sorted(stats["extents"])
    if ext[-1] > BED:
        probs.append(f"The part is {' x '.join(fmt_mm(round(e)) for e in stats['extents'])} mm, which does not fit a {fmt_mm(BED)} mm print bed. Make it smaller or split it so it fits.")
    if ext[0] < 1.0:
        probs.append("The part is thinner than 1 mm in one direction, so it cannot be printed. Check the dimensions.")
    if stats.get("pieces", 1) > 1:
        probs.append(f"The model is {stats['pieces']} separate pieces that do not touch. Make them overlap by at least 1 mm (or add a bar between them) so it prints as one solid part.")
    return probs


def render_png(scad, png):
    env = dict(os.environ, OPENSCADPATH=str(LIB))
    subprocess.run(["openscad", "--backend=manifold", "--render", "--imgsize=480,400", "--viewall", "--autocenter",
                    "--colorscheme=Tomorrow", "-o", str(png), str(scad)], capture_output=True, env=env, timeout=180)


class Server:
    def __init__(self, model, port, ctx):
        self.port = port
        log = open(f"/tmp/llama-server-{port}.log", "w")
        self.p = subprocess.Popen(["llama-server", "-m", model, "--port", str(port), "-c", str(ctx), "-ngl", "99",
                                   "--jinja", "-np", "1", "--no-webui"], stdout=log, stderr=subprocess.STDOUT)
        for _ in range(600):
            try:
                if requests.get(f"http://127.0.0.1:{port}/health", timeout=1).status_code == 200:
                    return
            except requests.RequestException:
                pass
            if self.p.poll() is not None:
                raise RuntimeError(f"llama-server exited, see /tmp/llama-server-{port}.log")
            time.sleep(0.5)
        raise RuntimeError("llama-server did not become healthy")

    def chat(self, messages, temperature, max_tokens):
        r = requests.post(f"http://127.0.0.1:{self.port}/v1/chat/completions", json={
            "messages": messages, "temperature": temperature, "top_p": 0.9, "top_k": 20, "max_tokens": max_tokens,
            "chat_template_kwargs": {"enable_thinking": False}, "cache_prompt": True}, timeout=900)
        r.raise_for_status()
        d = r.json()
        return d["choices"][0]["message"]["content"], d.get("timings", {})

    def close(self):
        self.p.send_signal(signal.SIGINT)
        try:
            self.p.wait(10)
        except subprocess.TimeoutExpired:
            self.p.kill()


def run_case(server, case, outdir, repairs, temperature, max_tokens):
    system = (PROMPTS / "system.md").read_text()
    messages = [{"role": "system", "content": system}, {"role": "user", "content": build_user_message(case)}]
    rec = design_loop(server, case, messages, case["id"], outdir, repairs, temperature, max_tokens)
    if case.get("refine") and rec["ok"]:
        # Same conversation shape as the app: the kept design, then the change request.
        code = (outdir / f"{rec['final']}.scad").read_text()
        messages = messages[:2] + [{"role": "assistant", "content": f"```openscad\n{code.rstrip()}\n```"},
                                   {"role": "user", "content": (PROMPTS / "refine.md").read_text().replace("{{change}}", case["refine"])}]
        refined = design_loop(server, {**case, "expect": case.get("refine_expect", case.get("expect"))}, messages,
                              case["id"] + "-refined", outdir, repairs, temperature, max_tokens)
        before, after = rec.get("mesh", {}).get("extents"), refined.get("mesh", {}).get("extents")
        refined["first_extents"] = before
        rec = {**refined, "id": case["id"], "refine": case["refine"]}
        rec["refine_changed_size"] = before != after
    return rec


def design_loop(server, case, messages, name, outdir, repairs, temperature, max_tokens):
    rec = {"id": name, "attempts": [], "ok": False}
    for attempt in range(repairs + 1):
        reply, timings = server.chat(messages, temperature, max_tokens)
        code = extract_code(reply)
        ok, errs, stl, dt = compile_scad(code, outdir, f"{name}_a{attempt}")
        a = {"ok": ok, "errors": errs, "compile_s": round(dt, 2), "prompt_n": timings.get("prompt_n"),
             "predicted_n": timings.get("predicted_n"), "predicted_per_second": timings.get("predicted_per_second")}
        problems = list(errs)
        if ok:
            stats = mesh_stats(stl)
            a["mesh"] = stats
            sp = sanity_problems(stats)
            a["sanity"] = sp
            problems = sp
            if not sp:
                rec.update(ok=True, final=f"{name}_a{attempt}", mesh=stats)
                code_vals = re.findall(r"(?<![\w.])(\d+(?:\.\d+)?)(?![\w.])", code)
                used = [m["label"] for m in case.get("measurements", []) if any(float(v) == float(m["mm"]) for v in code_vals)]
                rec["measurements_used"] = f"{len(used)}/{len(case.get('measurements', []))}"
                rec["library"] = sorted(set(re.findall(r"\b(" + "|".join(PARTS + HELPERS) + r")\s*\(", code)))
                parts_used = [m for m in rec["library"] if m in PARTS] or ["freeform"]
                rec["right_part"] = any(m in case.get("expect", parts_used) for m in parts_used)
                rec["checks_ok"] = all(re.search(c, code) for c in case.get("checks", []))
                m = re.search(r"^[ \t]*//[ \t]*Name:[ \t]*(.*?)[ \t]*$", code, re.M)
                rec["name"] = m.group(1).strip().strip('"\'`.:') if m else None
                a["ok"] = True
                rec["attempts"].append(a)
                render_png(outdir / f"{name}_a{attempt}.scad", outdir / f"{name}.png")
                break
        a["ok"] = False
        rec["attempts"].append(a)
        messages += [{"role": "assistant", "content": f"```openscad\n{code}```"},
                     {"role": "user", "content": (PROMPTS / "repair.md").read_text().replace("{{errors}}", "\n".join(problems[:6]))}]
    rec["n_attempts"] = len(rec["attempts"])
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--cases", default="")
    ap.add_argument("--cases-file", default=str(ROOT / "tools/eval/cases.json"))
    ap.add_argument("--tag", default="")
    ap.add_argument("--repairs", type=int, default=2)
    ap.add_argument("--temperature", type=float, default=0.3)
    ap.add_argument("--max-tokens", type=int, default=1200)
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--out", default=str(ROOT / "tools/eval/out"))
    args = ap.parse_args()

    cases = json.loads(pathlib.Path(args.cases_file).read_text())
    if args.cases:
        keep = set(args.cases.split(","))
        cases = [c for c in cases if c["id"] in keep]
    tag = pathlib.Path(args.model).stem + args.tag
    outdir = pathlib.Path(args.out) / tag
    outdir.mkdir(parents=True, exist_ok=True)

    server = Server(args.model, args.port, 8192)
    results = []
    try:
        for c in cases:
            t0 = time.time()
            r = run_case(server, c, outdir, args.repairs, args.temperature, args.max_tokens)
            r["wall_s"] = round(time.time() - t0, 1)
            results.append(r)
            print(f"{'PASS' if r['ok'] else 'FAIL'} {c['id']:18s} attempts={r['n_attempts']} "
                  f"name={r.get('name')!r} meas={r.get('measurements_used', '-')} part={'ok' if r.get('right_part') else 'WRONG'} checks={'ok' if r.get('checks_ok', True) else 'FAIL'} lib={','.join(r.get('library', []))} "
                  f"ext={r.get('mesh', {}).get('extents')} {r['wall_s']}s", flush=True)
    finally:
        server.close()

    n_ok = sum(r["ok"] for r in results)
    first_try = sum(r["ok"] and r["n_attempts"] == 1 for r in results)
    gen = [a["predicted_n"] for r in results for a in r["attempts"] if a.get("predicted_n")]
    good = sum(r["ok"] and r.get("right_part") and r.get("checks_ok", True) for r in results)
    named = sum(bool(r.get("name")) and 2 <= len(r["name"].split()) <= 6 for r in results)
    summary = {"model": tag, "cases": len(results), "passed": n_ok, "first_try": first_try, "right_part_and_checks": good, "named_2_to_6_words": named,
               "avg_generated_tokens": round(sum(gen) / max(1, len(gen)))}
    (outdir / "results.json").write_text(json.dumps({"summary": summary, "results": results}, indent=2))
    print(json.dumps(summary))
    pngs = [str(outdir / f"{n}.png") for r in results if r["ok"] for n in (r["id"], r["id"] + "-refined") if (outdir / f"{n}.png").exists()]
    if pngs:
        subprocess.run([sys.executable, str(ROOT / "tools/scadtest/sheet.py"), str(outdir / "sheet.png"), *pngs])


if __name__ == "__main__":
    main()
