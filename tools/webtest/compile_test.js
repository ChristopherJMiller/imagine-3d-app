// Compiles a few snippets through the worker and summarises what the bridge received.
const wait = (pred, ms = 90000) => new Promise((res, rej) => {
  const t0 = Date.now();
  const tick = () => pred() ? res() : (Date.now() - t0 > ms ? rej(new Error('timeout')) : setTimeout(tick, 100));
  tick();
});
await wait(() => window.__ready);
const t0 = performance.now();
Imagine.compile({ id: 'hook', code: 'include <imagine.scad>\nwall_hook(width=25);\n' });
Imagine.compile({ id: 'define', code: 'include <imagine.scad>\nw = 20;\ncube([w, 10, 5]);\n', defines: { w: '40' }, show: false });
Imagine.compile({ id: 'unknown', code: 'include <imagine.scad>\nnot_a_module(3);\n', show: false });
Imagine.compile({ id: 'syntax', code: 'cube(10', show: false });
Imagine.compile({ id: 'hook2', code: 'include <imagine.scad>\nover_door_hook(door_t=19, width=25);\n' });
await wait(() => (window.__results || []).length >= 5);
return {
  totalMs: Math.round(performance.now() - t0),
  results: window.__results.map(r => ({ id: r.id, exit: r.exit, ms: r.ms, size: r.size, stlBytes: r.stl ? r.stl.length : 0,
    log: r.log.filter(l => /ERROR|WARNING|Volumes|Total rendering/.test(l)).slice(0, 4) })),
};
