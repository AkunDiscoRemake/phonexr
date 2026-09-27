// PhoneXR for iPhone: a Cardboard VR home as a web app (PWA).
// Two eyes with Cardboard lens warp, head rotation from the gyroscope, the camera around you,
// a floating panel of apps, and hands (MediaPipe) — a cursor and a pinch to click, like PhoneXR on Android.
'use strict';

const APPS = [
  { title: 'Moon Rider', detail: 'Ритм‑игра', url: 'https://moonrider.xyz/', color: '#e0409f', glyph: '🌙' },
  { title: 'A‑Blast', detail: 'Тир', url: 'https://aframe.io/a-blast/', color: '#ff9f0a', glyph: '🎯' },
  { title: 'Hello WebXR', detail: 'Мини‑игры', url: 'https://mixedreality.mozilla.org/hello-webxr/', color: '#5e5ce6', glyph: '👋' },
  { title: 'XR Dinosaurs', detail: 'Динозавры', url: 'https://xrdinosaurs.com/', color: '#30d158', glyph: '🦖' },
  { title: 'A‑Painter', detail: 'Рисование', url: 'https://aframe.io/a-painter/', color: '#0a84ff', glyph: '🎨' },
  { title: 'YouTube', detail: 'Видео', url: 'https://m.youtube.com/', color: '#ff0033', glyph: '▶' },
  { title: 'Sketchfab', detail: '3D‑модели', url: 'https://sketchfab.com/', color: '#1caad9', glyph: '🧊' },
  { title: 'Выйти', detail: 'Из VR', url: null, color: '#636366', glyph: '✕' },
];

// ---------------------------------------------------------------- home screen

const $ = (id) => document.getElementById(id);
const settings = JSON.parse(localStorage.getItem('phonexr') || '{}');
$('hands').checked = settings.hands !== false;
$('passthrough').checked = settings.passthrough !== false;
$('ipd').value = settings.ipd || 64;
$('ipdText').textContent = $('ipd').value + ' мм';
function save() {
  localStorage.setItem('phonexr', JSON.stringify({ hands: $('hands').checked, passthrough: $('passthrough').checked, ipd: +$('ipd').value }));
}
['hands', 'passthrough'].forEach((id) => $(id).addEventListener('change', save));
$('ipd').addEventListener('input', () => { $('ipdText').textContent = $('ipd').value + ' мм'; save(); });
$('games').innerHTML = APPS.filter((a) => a.url).map((a) =>
  `<a class="row" href="${a.url}"><div class="icon" style="background:${a.color}">${a.glyph}</div>` +
  `<div class="text">${a.title}<div class="detail">${a.detail}</div></div><div class="chev">›</div></a>`).join('');
if (navigator.standalone || matchMedia('(display-mode: standalone)').matches) $('hint').textContent = 'PhoneXR открыт как приложение.';
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});

// ---------------------------------------------------------------- quaternions

const quat = {
  mul(a, b) {
    return [a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
            a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
            a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
            a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]];
  },
  axis(x, y, z, angle) { const s = Math.sin(angle / 2); return [x * s, y * s, z * s, Math.cos(angle / 2)]; },
  rotate(q, v) {
    const [x, y, z, w] = q;
    const tx = 2 * (y * v[2] - z * v[1]), ty = 2 * (z * v[0] - x * v[2]), tz = 2 * (x * v[1] - y * v[0]);
    return [v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx)];
  },
  conj(q) { return [-q[0], -q[1], -q[2], q[3]]; },
  // Column-major rotation matrix of the inverse (world → head).
  viewMatrix(q) {
    const [x, y, z, w] = quat.conj(q);
    return [1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
            2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
            2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0, 0, 0, 0, 1];
  },
};

// ---------------------------------------------------------------- head rotation

let orientation = null;
let head = [0, 0, 0, 1];
let zeroYaw = null;
window.addEventListener('deviceorientation', (e) => { if (e.alpha !== null) orientation = e; });

function headFromOrientation() {
  if (!orientation) return head;
  // The same conversion as three.js DeviceOrientationControls: device angles → a camera looking out of the screen.
  const alpha = orientation.alpha * Math.PI / 180, beta = orientation.beta * Math.PI / 180, gamma = orientation.gamma * Math.PI / 180;
  const screenAngle = ((screen.orientation && screen.orientation.angle) || window.orientation || 0) * Math.PI / 180;
  // Euler YXZ (beta about X, alpha about Y, -gamma about Z).
  const c1 = Math.cos(beta / 2), c2 = Math.cos(alpha / 2), c3 = Math.cos(-gamma / 2);
  const s1 = Math.sin(beta / 2), s2 = Math.sin(alpha / 2), s3 = Math.sin(-gamma / 2);
  let q = [s1 * c2 * c3 + c1 * s2 * s3, c1 * s2 * c3 - s1 * c2 * s3, c1 * c2 * s3 - s1 * s2 * c3, c1 * c2 * c3 + s1 * s2 * s3];
  q = quat.mul(q, [-Math.sqrt(0.5), 0, 0, Math.sqrt(0.5)]);
  q = quat.mul(q, quat.axis(0, 0, 1, -screenAngle));
  // "Straight ahead" is where you looked when VR started (and after a recentre).
  if (zeroYaw === null) {
    const f = quat.rotate(q, [0, 0, -1]);
    zeroYaw = Math.atan2(-f[0], -f[2]);
  }
  return quat.mul(quat.axis(0, 1, 0, -zeroYaw), q);
}

// ---------------------------------------------------------------- WebGL

const canvas = $('canvas');
const video = $('video');
let gl, programs = {}, textures = {}, eyeTarget = null, panelCanvas, panelDirty = true, hovered = -1;

function shader(type, source) {
  const s = gl.createShader(type); gl.shaderSource(s, source); gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
  return s;
}
function program(vs, fs) {
  const p = gl.createProgram();
  gl.attachShader(p, shader(gl.VERTEX_SHADER, vs)); gl.attachShader(p, shader(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(p);
  return p;
}
const TEX_VS = 'attribute vec3 aPos; attribute vec2 aUv; uniform mat4 uMvp; varying vec2 vUv; void main(){ vUv=aUv; gl_Position=uMvp*vec4(aPos,1.0);}';
const TEX_FS = 'precision mediump float; uniform sampler2D uTex; uniform float uAlpha; varying vec2 vUv; void main(){ vec4 c=texture2D(uTex,vUv); gl_FragColor=vec4(c.rgb,c.a*uAlpha);}';
const COLOR_FS = 'precision mediump float; uniform vec4 uColor; varying vec2 vUv; void main(){ gl_FragColor=uColor; }';
// Cardboard lenses: the same gentle barrel warp as PhoneXR on Android, each eye a rectangle.
const WARP_FS = `precision highp float; uniform sampler2D uTex; varying vec2 vUv;
  vec2 bend(vec2 e, float k){ vec2 p=(e-0.5)*2.0; float r2=dot(p,p); p*=1.0+k*r2+0.06*r2*r2; return p*0.5+0.5; }
  void main(){ float right=step(0.5,vUv.x); vec2 e=vec2(vUv.x*2.0-right,vUv.y);
    vec2 r=bend(e,0.205), g=bend(e,0.215), b=bend(e,0.225);
    if (min(min(g.x,g.y),min(1.0-g.x,1.0-g.y))<0.0){ gl_FragColor=vec4(0,0,0,1); return; }
    float base=right*0.5;
    gl_FragColor=vec4(texture2D(uTex,vec2(base+clamp(r.x,0.,1.)*0.5,r.y)).r, texture2D(uTex,vec2(base+g.x*0.5,g.y)).g,
                      texture2D(uTex,vec2(base+clamp(b.x,0.,1.)*0.5,b.y)).b, 1.0); }`;

function setupGl() {
  gl = canvas.getContext('webgl', { antialias: false, alpha: false });
  programs.tex = program(TEX_VS, TEX_FS);
  programs.color = program(TEX_VS, COLOR_FS);
  programs.warp = program(TEX_VS, WARP_FS);
  for (const name of ['video', 'panel', 'eyes']) {
    textures[name] = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, textures[name]);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  }
  panelCanvas = document.createElement('canvas');
  panelCanvas.width = 1400; panelCanvas.height = 900;
}

function resize() {
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.round(innerWidth * dpr); canvas.height = Math.round(innerHeight * dpr);
  gl.bindTexture(gl.TEXTURE_2D, textures.eyes);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, canvas.width, canvas.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
  eyeTarget = gl.createFramebuffer();
  gl.bindFramebuffer(gl.FRAMEBUFFER, eyeTarget);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, textures.eyes, 0);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  $('rotate').style.display = innerHeight > innerWidth ? 'flex' : 'none';
}

const buffer = () => gl.buffer || (gl.buffer = gl.createBuffer());
function draw(prog, data, mode, uniforms) {
  gl.useProgram(prog);
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer());
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data), gl.DYNAMIC_DRAW);
  const pos = gl.getAttribLocation(prog, 'aPos'), uv = gl.getAttribLocation(prog, 'aUv');
  gl.enableVertexAttribArray(pos); gl.vertexAttribPointer(pos, 3, gl.FLOAT, false, 20, 0);
  if (uv >= 0) { gl.enableVertexAttribArray(uv); gl.vertexAttribPointer(uv, 2, gl.FLOAT, false, 20, 12); }
  gl.uniformMatrix4fv(gl.getUniformLocation(prog, 'uMvp'), false, uniforms.mvp);
  if (uniforms.color) gl.uniform4fv(gl.getUniformLocation(prog, 'uColor'), uniforms.color);
  if (uniforms.texture) {
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, uniforms.texture);
    gl.uniform1i(gl.getUniformLocation(prog, 'uTex'), 0);
    gl.uniform1f(gl.getUniformLocation(prog, 'uAlpha'), uniforms.alpha ?? 1);
  }
  gl.drawArrays(mode, 0, data.length / 5);
}
const quad = (x0, y0, x1, y1, z, u0 = 0, v0 = 0, u1 = 1, v1 = 1) =>
  [x0, y0, z, u0, v1, x1, y0, z, u1, v1, x0, y1, z, u0, v0, x1, y1, z, u1, v0];

function perspective(fovY, aspect, near, far, shiftX) {
  const f = 1 / Math.tan(fovY / 2);
  return [f / aspect, 0, 0, 0, 0, f, 0, 0, shiftX, 0, (far + near) / (near - far), -1, 0, 0, 2 * far * near / (near - far), 0];
}
function multiply(a, b) {
  const out = new Array(16).fill(0);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) for (let k = 0; k < 4; k++) out[c * 4 + r] += a[k * 4 + r] * b[c * 4 + k];
  return out;
}
function translate(m, x, y, z) { return multiply(m, [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, x, y, z, 1]); }

// ---------------------------------------------------------------- the panel of apps

const PANEL = { distance: 1.6, width: 1.5, height: 1.5 * 900 / 1400, y: -0.05 };
function tileRect(i) {
  const col = i % 4, row = Math.floor(i / 4);
  return { x: 110 + col * 310, y: 170 + row * 340, w: 250, h: 300 };
}
function drawPanel() {
  const c = panelCanvas.getContext('2d');
  c.clearRect(0, 0, 1400, 900);
  c.fillStyle = 'rgba(28,28,30,0.82)';
  c.beginPath(); c.roundRect(0, 0, 1400, 900, 70); c.fill();
  c.fillStyle = '#fff'; c.font = '600 64px -apple-system, sans-serif'; c.textAlign = 'left';
  c.fillText('PhoneXR', 90, 110);
  c.fillStyle = '#8e8e93'; c.font = '34px -apple-system, sans-serif';
  c.fillText(handsReady ? 'Рука — курсор, щипок — открыть' : 'Смотрите на плитку и коснитесь экрана', 400, 108);
  APPS.forEach((app, i) => {
    const r = tileRect(i), hover = i === hovered;
    c.save();
    if (hover) { c.fillStyle = 'rgba(255,255,255,0.14)'; c.beginPath(); c.roundRect(r.x - 14, r.y - 14, r.w + 28, r.h + 28, 40); c.fill(); }
    c.fillStyle = app.color; c.beginPath(); c.roundRect(r.x + 25, r.y, 200, 200, 48); c.fill();
    c.font = '100px -apple-system, sans-serif'; c.textAlign = 'center'; c.fillStyle = '#fff';
    c.fillText(app.glyph, r.x + 125, r.y + 138);
    c.font = '600 36px -apple-system, sans-serif'; c.fillText(app.title, r.x + 125, r.y + 255);
    c.fillStyle = '#8e8e93'; c.font = '28px -apple-system, sans-serif'; c.fillText(app.detail, r.x + 125, r.y + 292);
    c.restore();
  });
  gl.bindTexture(gl.TEXTURE_2D, textures.panel);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, panelCanvas);
  panelDirty = false;
}
/** Where a ray from the eyes (world direction) meets the panel, as panel pixels, or null. */
function panelHit(dir) {
  if (dir[2] >= -1e-3) return null;
  const t = -PANEL.distance / dir[2];
  const x = dir[0] * t, y = dir[1] * t - PANEL.y;
  if (Math.abs(x) > PANEL.width / 2 || Math.abs(y) > PANEL.height / 2) return null;
  return { px: (x / PANEL.width + 0.5) * 1400, py: (0.5 - y / PANEL.height) * 900 };
}
function tileAt(hit) {
  if (!hit) return -1;
  return APPS.findIndex((_, i) => { const r = tileRect(i); return hit.px >= r.x - 14 && hit.px <= r.x + r.w + 14 && hit.py >= r.y - 14 && hit.py <= r.y + r.h + 14; });
}
function open(index) {
  const app = APPS[index];
  if (!app) return;
  if (!app.url) { exitVr(); return; }
  exitVr();
  location.href = app.url;
}

// ---------------------------------------------------------------- camera and hands

// The phone camera's view, in tangents: wide camera ≈ 68° across in landscape.
const CAMERA_TAN_X = Math.tan(34 * Math.PI / 180);
let cameraTanY = CAMERA_TAN_X * 9 / 16;
let handsReady = false, landmarker = null, lastVideoTime = -1;
let cursor = null, pinching = false, handLines = [];

async function startCamera() {
  const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false });
  video.srcObject = stream;
  await video.play();
  cameraTanY = CAMERA_TAN_X * video.videoHeight / video.videoWidth;
}

async function startHands() {
  const version = '0.10.14';
  const vision = await import(`https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${version}/vision_bundle.mjs`);
  const files = await vision.FilesetResolver.forVisionTasks(`https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@${version}/wasm`);
  landmarker = await vision.HandLandmarker.createFromOptions(files, {
    baseOptions: { modelAssetPath: 'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task', delegate: 'GPU' },
    runningMode: 'VIDEO', numHands: 2,
  });
  handsReady = true; panelDirty = true;
}

function trackHands(now) {
  if (!landmarker || video.readyState < 2 || video.currentTime === lastVideoTime) return;
  lastVideoTime = video.currentTime;
  const result = landmarker.detectForVideo(video, now);
  handLines = result.landmarks || [];
  const hand = handLines[0];
  if (!hand) { cursor = null; if (pinching) { pinching = false; } return; }
  const d = (a, b) => Math.hypot(hand[a].x - hand[b].x, hand[a].y - hand[b].y);
  const gap = d(4, 8) / Math.max(d(5, 17), 0.02);
  // Aim between the thumb and index knuckles, so a pinch does not move the cursor.
  const ax = hand[2].x * 0.3 + hand[5].x * 0.45 + (hand[4].x + hand[8].x) / 2 * 0.25;
  const ay = hand[2].y * 0.3 + hand[5].y * 0.45 + (hand[4].y + hand[8].y) / 2 * 0.25;
  cursor = cursor ? { x: cursor.x + (ax - cursor.x) * 0.5, y: cursor.y + (ay - cursor.y) * 0.5 } : { x: ax, y: ay };
  const was = pinching;
  pinching = was ? gap < 0.48 : gap < 0.30;
  if (pinching && !was) click();
}
/** The cursor as a direction in head space. */
function cursorTangent() { return cursor ? [(cursor.x - 0.5) * 2 * CAMERA_TAN_X, (0.5 - cursor.y) * 2 * cameraTanY, -1] : null; }

function pointerDirection() {
  const local = cursorTangent() || [0, 0, -1]; // no hand: gaze
  return quat.rotate(head, local);
}
function click() { const tile = tileAt(panelHit(pointerDirection())); if (tile >= 0) open(tile); }

// ---------------------------------------------------------------- the frame

let running = false;
function frame(now) {
  if (!running) return;
  requestAnimationFrame(frame);
  head = headFromOrientation();
  if ($('hands').checked) trackHands(now);
  const tile = tileAt(panelHit(pointerDirection()));
  if (tile !== hovered) { hovered = tile; panelDirty = true; }
  if (panelDirty) drawPanel();
  const passthrough = $('passthrough').checked && video.readyState >= 2;
  if (passthrough) { gl.bindTexture(gl.TEXTURE_2D, textures.video); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, video); }

  gl.bindFramebuffer(gl.FRAMEBUFFER, eyeTarget);
  gl.clearColor(0.06, 0.06, 0.08, 1); gl.clear(gl.COLOR_BUFFER_BIT);
  gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
  const eyeW = canvas.width / 2, aspect = eyeW / canvas.height;
  const halfIpd = (+$('ipd').value) / 2000;
  const view = quat.viewMatrix(head);
  for (let eye = 0; eye < 2; eye++) {
    gl.viewport(eye * eyeW, 0, eyeW, canvas.height);
    const projection = perspective(80 * Math.PI / 180, aspect, 0.05, 50, 0);
    // The camera, fixed to the head, as wide as it really sees.
    if (passthrough) draw(programs.tex, quad(-CAMERA_TAN_X, -cameraTanY, CAMERA_TAN_X, cameraTanY, -1), gl.TRIANGLE_STRIP, { mvp: projection, texture: textures.video });
    const eyeView = translate(multiply(projection, [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, eye === 0 ? halfIpd : -halfIpd, 0, 0, 1]), 0, 0, 0);
    const mvp = multiply(eyeView, view);
    draw(programs.tex, quad(-PANEL.width / 2, PANEL.y - PANEL.height / 2, PANEL.width / 2, PANEL.y + PANEL.height / 2, -PANEL.distance), gl.TRIANGLE_STRIP, { mvp, texture: textures.panel });
    // Hands as a skeleton, and the cursor, over the real hands (head space).
    const toView = (p) => [(p.x - 0.5) * 2 * CAMERA_TAN_X, (0.5 - p.y) * 2 * cameraTanY, -1, 0, 0];
    for (const hand of handLines) {
      const bones = [0,1,1,2,2,3,3,4,0,5,5,6,6,7,7,8,5,9,9,10,10,11,11,12,9,13,13,14,14,15,15,16,13,17,0,17,17,18,18,19,19,20];
      const data = []; for (const i of bones) data.push(...toView(hand[i]));
      draw(programs.color, data, gl.LINES, { mvp: projection, color: pinching ? [1, 0.48, 0.1, 0.95] : [1, 1, 1, 0.8] });
    }
    const aim = cursorTangent() || [0, 0, -1];
    const r = pinching ? 0.012 : 0.018, disc = [];
    for (let k = 0; k <= 16; k++) { const a = k / 16 * Math.PI * 2; disc.push(aim[0], aim[1], -1, 0, 0, aim[0] + Math.cos(a) * r, aim[1] + Math.sin(a) * r, -1, 0, 0); }
    draw(programs.color, disc, gl.TRIANGLE_STRIP, { mvp: projection, color: [1, 1, 1, 1] });
  }
  gl.disable(gl.BLEND);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  gl.viewport(0, 0, canvas.width, canvas.height);
  draw(programs.warp, quad(-1, -1, 1, 1, 0, 0, 1, 1, 0), gl.TRIANGLE_STRIP, { mvp: [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1], texture: textures.eyes });
}

// ---------------------------------------------------------------- entering and leaving VR

let wakeLock = null;
async function enterVr() {
  // iOS asks for the motion sensors once, from a tap.
  if (typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function') {
    // Without it VR still opens, only it does not turn with the head.
    await DeviceOrientationEvent.requestPermission().catch(() => 'denied');
  }
  $('home').style.display = 'none'; $('vr').style.display = 'block'; $('exit').style.display = 'block';
  if (!gl) setupGl();
  resize();
  zeroYaw = null; panelDirty = true; running = true;
  requestAnimationFrame(frame);
  try { wakeLock = await navigator.wakeLock?.request('screen'); } catch (e) {}
  try { await document.documentElement.requestFullscreen?.(); } catch (e) {}
  if ($('passthrough').checked || $('hands').checked) {
    try { await startCamera(); } catch (e) { $('passthrough').checked = false; }
  }
  if ($('hands').checked && !landmarker) startHands().catch(() => { handsReady = false; });
}
function exitVr() {
  running = false;
  $('home').style.display = 'block'; $('vr').style.display = 'none'; $('exit').style.display = 'none'; $('rotate').style.display = 'none';
  video.srcObject?.getTracks().forEach((t) => t.stop()); video.srcObject = null;
  wakeLock?.release?.(); wakeLock = null;
}
$('enter').addEventListener('click', enterVr);
$('exit').addEventListener('click', exitVr);
window.addEventListener('resize', () => { if (gl) resize(); });
// The Cardboard button (a touch on the screen) clicks where you look; a double tap re-centres.
let lastTap = 0;
canvas.addEventListener('touchstart', (e) => {
  e.preventDefault();
  const now = Date.now();
  if (now - lastTap < 300) { zeroYaw = null; lastTap = 0; return; }
  lastTap = now;
  const tile = tileAt(panelHit(quat.rotate(head, [0, 0, -1])));
  if (tile >= 0) open(tile);
}, { passive: false });
