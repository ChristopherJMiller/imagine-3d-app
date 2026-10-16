// Runs OpenSCAD (WebAssembly, Manifold backend) off the UI thread.
// The emscripten runtime exits after one callMain(), so every compile gets a fresh
// instance; the compiled WebAssembly.Module is reused so that stays cheap.
import OpenSCAD from './openscad/openscad.js';

const wasmUrl = new URL('./openscad/openscad.wasm', import.meta.url);
const wasmModule = WebAssembly.compileStreaming(fetch(wasmUrl));
let files = {};

async function compile({ id, code, defines }) {
  const log = [];
  const t0 = performance.now();
  const module = await wasmModule;
  let exit = -1;
  let stl = null;
  try {
    const inst = await OpenSCAD({
      noInitialRun: true,
      print: (t) => log.push(t),
      printErr: (t) => log.push(t),
      instantiateWasm: (imports, done) => {
        WebAssembly.instantiate(module, imports).then((i) => done(i, module));
        return {};
      },
    });
    for (const [name, content] of Object.entries(files)) inst.FS.writeFile('/' + name, content);
    inst.FS.writeFile('/input.scad', code);
    const args = ['/input.scad', '-o', '/out.stl', '--backend=manifold', '--export-format=binstl'];
    for (const [k, v] of Object.entries(defines || {})) args.push(`-D${k}=${v}`);
    try {
      exit = inst.callMain(args);
    } catch (err) {
      log.push(`ERROR: OpenSCAD crashed: ${err}`);
    }
    try {
      stl = inst.FS.readFile('/out.stl').slice().buffer;
    } catch (_) { /* no output */ }
  } catch (err) {
    log.push(`ERROR: ${err}`);
  }
  const ms = Math.round(performance.now() - t0);
  postMessage({ type: 'result', id, exit, log, ms, stl }, stl ? [stl] : []);
}

onmessage = async (e) => {
  const m = e.data;
  if (m.type === 'init') {
    files = m.files || {};
    await wasmModule;
    postMessage({ type: 'ready' });
  } else if (m.type === 'compile') {
    await compile(m);
  }
};
