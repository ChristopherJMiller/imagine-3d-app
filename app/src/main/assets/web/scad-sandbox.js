// OpenSCAD in androidx.javascriptengine (a bare V8 isolate, no DOM, no fetch, no timers
// throttling when the app is in the background). Loaded before the emscripten glue.
// Kotlin: ScadSandbox.kt. The browser path (scad-worker.js) does the same job inside the WebView.
(function () {
  const g = globalThis;
  // Emscripten's /dev/random wants crypto.getRandomValues; OpenSCAD doesn't need real entropy.
  if (!g.crypto) g.crypto = { getRandomValues(a) { for (let i = 0; i < a.length; i++) a[i] = (Math.random() * 256) | 0; return a; } };
  if (!g.performance) g.performance = { now: () => Date.now() };

  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function base64(bytes) {
    let out = '';
    let i = 0;
    for (; i + 2 < bytes.length; i += 3) {
      const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
      out += B64[n >> 18] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + B64[n & 63];
    }
    if (i < bytes.length) {
      const n = (bytes[i] << 16) | ((i + 1 < bytes.length ? bytes[i + 1] : 0) << 8);
      out += B64[n >> 18] + B64[(n >> 12) & 63] + (i + 1 < bytes.length ? B64[(n >> 6) & 63] : '=') + '=';
    }
    return out;
  }

  let module = null;
  let files = {};

  g.ImagineSandbox = {
    async init(library) {
      files = { 'imagine.scad': library };
      if (!module) module = await WebAssembly.compile(await android.consumeNamedDataAsArrayBuffer('openscad.wasm'));
      return 'ready';
    },
    // Same contract as scad-worker.js: fresh emscripten instance per run (the runtime exits
    // after callMain), reusing the compiled module.
    async compile(code, defines) {
      const log = [];
      let exit = -1;
      let stl = null;
      try {
        const inst = await g.OpenSCAD({
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
        try { exit = inst.callMain(args); } catch (err) { log.push(`ERROR: OpenSCAD crashed: ${err}`); }
        try { stl = base64(inst.FS.readFile('/out.stl')); } catch (_) { /* no output */ }
      } catch (err) {
        log.push(`ERROR: ${err}`);
      }
      return JSON.stringify({ exit, log, stl });
    },
  };
})();
