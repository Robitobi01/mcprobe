// mcprobe live viewer: listens to ./stream (server-sent events) and draws an icicle flame graph.
"use strict";
const $ = (id) => document.getElementById(id);
const instance = location.pathname.split("/").filter(Boolean).pop();
$("title").textContent = instance;
document.title = instance + " · mcprobe";

const ROW = 18, TICK_MS = 50;
let data = null, focusPath = [], paused = false, regex = null, hits = [];

// --- data ---------------------------------------------------------------------------------

// Idle: the main loop sleeping between ticks (MinecraftServer.run -> Thread.sleep).
function withoutIdle(node) {
  const kids = (node.c || [])
    .filter((c) => !(node.n === "MinecraftServer.run" && /^Thread\.sleep/.test(c.n)))
    .map(withoutIdle);
  const v = (node.s || 0) + kids.reduce((a, c) => a + c.v, 0);
  return { n: node.n, v, s: node.s || 0, c: kids.filter((c) => c.v > 0) };
}

function view() {
  if (!data || !data.tree) return null;
  return $("idle").checked ? withoutIdle(data.tree) : data.tree;
}

function find(root, path) {
  let node = root;
  for (const name of path) {
    const next = (node.c || []).find((c) => c.n === name);
    if (!next) break;
    node = next;
  }
  return node;
}

// samples -> milliseconds per tick (wall clock samples of `interval` ms over `covered` s)
function msPerTick(samples) {
  if (!data || !data.covered) return 0;
  return (samples * data.interval) / (data.covered * (1000 / TICK_MS));
}

// One server-sent event per second with the profile and the tick times. While the stream is
// open the server keeps profiling; closing the page stops it.
let source = null;
function connect() {
  if (source) source.close();
  source = new EventSource("stream?window=" + $("window").value);
  source.onmessage = (e) => {
    if (paused) return;
    const msg = JSON.parse(e.data);
    data = msg.profile;
    $("status").textContent = data.status === "running"
      ? `profiling · ${Math.round(data.covered)} s of samples`
      : "starting profiler…";
    if (msg.ticks) drawTicks(msg.ticks.tickNanos || []); else $("mspt").textContent = "–";
    render();
  };
  source.onerror = () => ($("status").textContent = "reconnecting…");
}

// --- drawing ------------------------------------------------------------------------------

function drawTicks(ns) {
  const c = $("ticks"), g = c.getContext("2d"), css = getComputedStyle(document.documentElement);
  const ms = ns.map((v) => v / 1e6);
  const avg = ms.length ? ms.reduce((a, b) => a + b, 0) / ms.length : 0;
  $("mspt").textContent = ms.length ? avg.toFixed(1) : "–";
  g.clearRect(0, 0, c.width, c.height);
  const max = Math.max(TICK_MS * 1.2, ...ms), w = c.width / Math.max(ms.length, 1);
  ms.forEach((v, i) => {
    g.fillStyle = v > TICK_MS ? css.getPropertyValue("--bad") : css.getPropertyValue("--ok");
    const h = (v / max) * c.height;
    g.fillRect(i * w, c.height - h, Math.max(w - 1, 1), h);
  });
  g.fillStyle = css.getPropertyValue("--muted");
  g.fillRect(0, c.height - (TICK_MS / max) * c.height, c.width, 1);
}

function color(name) {
  if (/^(java|javax|sun|jdk|com\/sun)\//.test(name) || /^(Thread|Object|String)\./.test(name)) return [70, 140, 210];
  if (name.indexOf(".") < 0 || name.indexOf("::") >= 0 || /^\[|^__|^os::/.test(name)) return [200, 150, 60];
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return [215 + (h & 31), 90 + ((h >> 5) & 63), 40 + ((h >> 11) & 31)];
}

function render() {
  const root = view(), canvas = $("flame");
  if (!root) return;
  const focus = find(root, focusPath);
  if (focus === root) focusPath = [];
  const boxes = [];
  let depth = 0;
  (function layout(node, x, w, d) {
    if (w < 0.5) return;
    boxes.push({ node, x, w, d });
    depth = Math.max(depth, d);
    let cx = x;
    for (const c of node.c || []) {
      const cw = (c.v / node.v) * w;
      layout(c, cx, cw, d + 1);
      cx += cw;
    }
  })(focus, 0, canvas.clientWidth, 0);

  const dpr = window.devicePixelRatio || 1;
  canvas.height = (depth + 1) * ROW * dpr;
  canvas.style.height = (depth + 1) * ROW + "px";
  canvas.width = canvas.clientWidth * dpr;
  const g = canvas.getContext("2d");
  g.scale(dpr, dpr);
  g.font = "12px system-ui, sans-serif";
  g.textBaseline = "middle";
  hits = boxes;
  let matched = 0;
  for (const b of boxes) {
    const hit = regex && regex.test(b.node.n);
    if (hit) matched += b.node.s;
    const [r, gg, bb] = color(b.node.n);
    g.fillStyle = hit ? getComputedStyle(document.documentElement).getPropertyValue("--hl") : `rgb(${r},${gg},${bb})`;
    g.fillRect(b.x, b.d * ROW, Math.max(b.w - 1, 0.5), ROW - 1);
    if (b.w > 30) {
      g.fillStyle = "#000";
      g.save();
      g.beginPath();
      g.rect(b.x, b.d * ROW, b.w - 2, ROW);
      g.clip();
      g.fillText(b.node.n, b.x + 3, b.d * ROW + ROW / 2);
      g.restore();
    }
  }
  $("crumbs").innerHTML = "";
  ["all", ...focusPath].forEach((name, i) => {
    const a = document.createElement("a");
    a.textContent = name;
    a.onclick = () => { focusPath = focusPath.slice(0, i); render(); };
    if (i) $("crumbs").append(" › ");
    $("crumbs").append(a);
  });
  if (regex) $("crumbs").append(` · search: ${msPerTick(matched).toFixed(2)} ms/tick`);
  selfTable(root);
}

function selfTable(root) {
  const self = new Map();
  (function walk(n) {
    if (n.s) self.set(n.n, (self.get(n.n) || 0) + n.s);
    (n.c || []).forEach(walk);
  })(root);
  const rows = [...self].sort((a, b) => b[1] - a[1]).slice(0, 30);
  $("self").tBodies[0].innerHTML = rows.map(([name, s]) =>
    `<tr><td>${name.replace(/&/g, "&amp;").replace(/</g, "&lt;")}</td>` +
    `<td>${msPerTick(s).toFixed(2)}</td><td>${((100 * s) / root.v).toFixed(1)}</td></tr>`).join("");
}

// --- interaction --------------------------------------------------------------------------

function boxAt(e) {
  const r = $("flame").getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
  return hits.find((b) => x >= b.x && x < b.x + b.w && Math.floor(y / ROW) === b.d);
}

$("flame").addEventListener("click", (e) => {
  const b = boxAt(e);
  if (!b) return;
  const root = view();
  // path from the root to the clicked node
  const path = [];
  (function search(n, trail) {
    if (n === b.node) { path.push(...trail); return true; }
    return (n.c || []).some((c) => search(c, [...trail, c.n]));
  })(root, []);
  focusPath = b.d === 0 ? focusPath.slice(0, -1) : path;
  render();
});

$("flame").addEventListener("mousemove", (e) => {
  const b = boxAt(e), tip = $("tip");
  if (!b) { tip.style.display = "none"; return; }
  const total = view().v;
  tip.innerHTML = `<b>${b.node.n.replace(/</g, "&lt;")}</b><br>` +
    `${msPerTick(b.node.v).toFixed(2)} ms/tick · ${((100 * b.node.v) / total).toFixed(1)} % · ${b.node.v} samples` +
    (b.node.s ? `<br>self: ${msPerTick(b.node.s).toFixed(2)} ms/tick` : "");
  const m = $("flame").parentElement.getBoundingClientRect();
  tip.style.left = Math.min(e.clientX - m.left + 12, m.width - 300) + "px";
  tip.style.top = e.clientY - m.top + 16 + "px";
  tip.style.display = "block";
});
$("flame").addEventListener("mouseleave", () => ($("tip").style.display = "none"));

$("pause").onclick = () => {
  paused = !paused;
  $("pause").textContent = paused ? "resume" : "pause";
};
$("idle").onchange = render;
$("window").onchange = () => { paused = false; $("pause").textContent = "pause"; connect(); };
$("search").oninput = (e) => {
  try { regex = e.target.value ? new RegExp(e.target.value, "i") : null; } catch { regex = null; }
  render();
};
window.addEventListener("resize", render);
connect();
