// Flächenlese – Offline-Unterstützung
// Bei jeder neuen Version die Nummer erhöhen.
const CACHE = 'flaechenlese-v7';
const FILES = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png'];

self.addEventListener('install', e => {
  // cache:'reload' = immer frisch vom Server holen, nie aus dem Browser-Zwischenspeicher
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
async function freshPage() {
  const res = await Promise.race([
    fetch(new Request('./index.html', { cache: 'no-cache', credentials: 'same-origin' })),
    new Promise((_, rej) => setTimeout(() => rej('timeout'), 5000))
  ]);
  if (res.ok) { const c = await caches.open(CACHE); await c.put('./index.html', res.clone()); }
  return res;
}
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  const path = new URL(req.url).pathname;
  if (req.mode === 'navigate' || path.endsWith('/index.html') || path.endsWith('/')) {
    // Seite: online immer die neueste Version, offline aus dem Speicher
    e.respondWith(freshPage().catch(async () => (await caches.match('./index.html')) || Response.error()));
    return;
  }
  // Icons usw.: zuerst aus dem Speicher
  e.respondWith(caches.match(req).then(hit => hit || fetch(req).then(res => {
    if (res.ok) { const copy = res.clone(); caches.open(CACHE).then(c => c.put(req, copy)); }
    return res;
  })));
});
