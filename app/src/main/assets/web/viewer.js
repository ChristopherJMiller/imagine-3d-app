// 3D preview + OpenSCAD bridge. Kotlin talks to window.Imagine and receives results on
// window.AndroidBridge (a @JavascriptInterface); in a desktop browser they collect in window.__results.
import * as THREE from 'three';
import { OrbitControls } from './three/OrbitControls.js';
import { STLLoader } from './three/STLLoader.js';

const bridge = window.AndroidBridge ?? {
  onReady: () => { window.__ready = true; },
  onResult: (json) => { (window.__results ??= []).push(JSON.parse(json)); },
};

const BED = 250;
const canvas = document.getElementById('c');
const dimsEl = document.getElementById('dims');
const busyEl = document.getElementById('busy');
const emptyEl = document.getElementById('empty');

const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(35, 1, 0.5, 5000);
camera.up.set(0, 0, 1); // OpenSCAD is Z-up
camera.position.set(160, -220, 160);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;

scene.add(new THREE.HemisphereLight(0xffffff, 0x445066, 1.6));
const key = new THREE.DirectionalLight(0xffffff, 1.6);
key.position.set(1, -1.5, 2);
camera.add(key);
scene.add(camera);

const bed = new THREE.Group();
let grid = null;
function makeGrid(major, minor) {
  if (grid) { bed.remove(grid); grid.geometry.dispose(); }
  grid = new THREE.GridHelper(BED, BED / 10, major, minor);
  grid.rotation.x = Math.PI / 2;
  bed.add(grid);
}
makeGrid(0x5a6270, 0x343a45);
const plateMaterial = new THREE.MeshBasicMaterial({ color: 0x20242b, transparent: true, opacity: 0.6, side: THREE.DoubleSide });
const plate = new THREE.Mesh(new THREE.PlaneGeometry(BED, BED), plateMaterial);
plate.position.z = -0.05;
bed.add(plate);
scene.add(bed);

const material = new THREE.MeshStandardMaterial({ color: 0x4f8fe6, roughness: 0.55, metalness: 0.05 });
let mesh = null;
const loader = new STLLoader();

function resize() {
  // Size from the window, not CSS percentages: a WebView that was laid out while detached
  // can keep a 0 px tall initial containing block even after it gets a real size.
  const w = window.innerWidth, h = window.innerHeight;
  if (canvas.style.width !== `${w}px`) canvas.style.width = `${w}px`;
  if (canvas.style.height !== `${h}px`) canvas.style.height = `${h}px`;
  if (canvas.width !== Math.floor(w * renderer.getPixelRatio()) || canvas.height !== Math.floor(h * renderer.getPixelRatio())) {
    renderer.setSize(w, h, false);
    camera.aspect = w / Math.max(1, h);
    camera.updateProjectionMatrix();
  }
}

function frame(box) {
  const size = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());
  const radius = Math.max(size.length() / 2, 10);
  const dist = radius / Math.sin(THREE.MathUtils.degToRad(camera.fov / 2)) * 1.05;
  const dir = new THREE.Vector3(0.55, -0.75, 0.55).normalize();
  camera.position.copy(center).addScaledVector(dir, dist);
  controls.target.copy(center);
  controls.update();
}

function show(buffer) {
  const geom = loader.parse(buffer);
  geom.computeBoundingBox();
  const bb = geom.boundingBox;
  // centre on the bed in X/Y, keep Z so the part sits where OpenSCAD put it
  geom.translate(-(bb.min.x + bb.max.x) / 2, -(bb.min.y + bb.max.y) / 2, 0);
  geom.computeBoundingBox();
  if (mesh) { scene.remove(mesh); mesh.geometry.dispose(); }
  mesh = new THREE.Mesh(geom, material);
  scene.add(mesh);
  const s = geom.boundingBox.getSize(new THREE.Vector3());
  dimsEl.textContent = `${s.x.toFixed(1)} × ${s.y.toFixed(1)} × ${s.z.toFixed(1)} mm`;
  dimsEl.hidden = false;
  emptyEl.hidden = true;
  frame(geom.boundingBox);
  return [s.x, s.y, s.z];
}

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

const pendingShow = new Map();
let latestShow = null;  // only the newest request that wants the preview may replace it
let libraryFiles = null;
let worker = null;

function onWorkerMessage(e) {
  const m = e.data;
  if (m.type === 'ready') {
    bridge.onReady();
  } else if (m.type === 'result') {
    busyEl.hidden = true;
    let size = null;
    const ok = m.exit === 0 && m.stl && m.stl.byteLength > 84;
    if (ok && pendingShow.get(m.id) && m.id === latestShow) {
      try { size = show(m.stl); } catch (err) { m.log.push(`ERROR: preview failed: ${err}`); }
    }
    pendingShow.delete(m.id);
    bridge.onResult(JSON.stringify({
      id: m.id, exit: m.exit, ms: m.ms, log: m.log, size,
      stl: ok ? toBase64(m.stl) : null,
    }));
  }
}

function startWorker() {
  worker = new Worker(new URL('./scad-worker.js', import.meta.url), { type: 'module' });
  worker.onmessage = onWorkerMessage;
  if (libraryFiles) worker.postMessage({ type: 'init', files: libraryFiles });
}

startWorker();
fetch('../scad/imagine.scad')
  .then((r) => r.text())
  .then((lib) => {
    libraryFiles = { 'imagine.scad': lib };
    worker.postMessage({ type: 'init', files: libraryFiles });
  });

window.Imagine = {
  /** req: {id, code, defines?: {name: valueString}, show?: boolean} */
  compile(req) {
    busyEl.hidden = false;
    pendingShow.set(req.id, req.show !== false);
    if (req.show !== false) latestShow = req.id;
    worker.postMessage({ type: 'compile', id: req.id, code: req.code, defines: req.defines || {} });
  },
  clear() {
    if (mesh) { scene.remove(mesh); mesh.geometry.dispose(); mesh = null; }
    dimsEl.hidden = true;
    emptyEl.hidden = false;
  },
  resetView() { if (mesh) frame(mesh.geometry.boundingBox); },
  /** Shows an STL compiled elsewhere (the Android JavaScriptSandbox), as base64. */
  showStl(b64) {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    latestShow = null;  // supersedes any in-page compile still running
    busyEl.hidden = true;
    show(bytes.buffer);
  },
  setBusy(on) { busyEl.hidden = !on; },
  /** Kills a worker stuck in a long OpenSCAD run and starts a fresh one. */
  resetWorker() {
    worker.terminate();
    pendingShow.clear();
    busyEl.hidden = true;
    startWorker();
  },
  setTheme({ bg, fg, part, empty }) {
    if (bg) {
      document.documentElement.style.setProperty('--bg', bg);
      const c = new THREE.Color(bg);
      const light = c.r * 0.3 + c.g * 0.59 + c.b * 0.11 > 0.5;
      document.documentElement.style.setProperty('--chip', light ? 'rgba(255, 255, 255, 0.7)' : 'rgba(0, 0, 0, 0.4)');
      plateMaterial.color.copy(c).offsetHSL(0, 0, light ? -0.06 : 0.04);
      plateMaterial.opacity = 1;
      makeGrid(light ? 0x9aa0ac : 0x5a6270, light ? 0xc4c8d0 : 0x343a45);
    }
    if (fg) document.documentElement.style.setProperty('--fg', fg);
    if (part) material.color.set(part);
    if (empty) emptyEl.textContent = empty;
  },
  snapshot() { renderer.render(scene, camera); return canvas.toDataURL('image/png'); },
};

renderer.setAnimationLoop(() => {
  resize();
  controls.update();
  renderer.setClearColor(getComputedStyle(document.documentElement).getPropertyValue('--bg').trim() || '#16181d');
  renderer.render(scene, camera);
});
