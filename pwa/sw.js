// PhoneXR PWA: the app itself works offline; MediaPipe (hands) is cached after the first run.
const CACHE = 'phonexr-pwa-1';
const APP = ['./', 'index.html', 'app.js', 'manifest.webmanifest', 'icon-180.png', 'icon-192.png', 'icon-512.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(APP)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  const cacheable = url.origin === location.origin || url.hostname === 'cdn.jsdelivr.net' || url.hostname === 'storage.googleapis.com';
  if (event.request.method !== 'GET' || !cacheable) return;
  event.respondWith(caches.match(event.request).then((hit) => hit || fetch(event.request).then((answer) => {
    if (answer.ok) { const copy = answer.clone(); caches.open(CACHE).then((cache) => cache.put(event.request, copy)); }
    return answer;
  })));
});
