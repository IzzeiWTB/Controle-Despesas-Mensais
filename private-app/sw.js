const CACHE_NAME = 'clareza-privado-v4';
const FILES = ['./', './index.html', './style.css', './app.js', './manifest.webmanifest', './icon.svg'];
self.addEventListener('install', event => { event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(FILES))); self.skipWaiting(); });
self.addEventListener('activate', event => event.waitUntil(
  caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('clareza-privado-') && key !== CACHE_NAME).map(key => caches.delete(key))))
    .then(() => self.clients.claim())
));
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);
  const allowed = FILES.map(file => new URL(file, self.registration.scope).pathname);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin || !allowed.includes(url.pathname)) return;
  event.respondWith(fetch(event.request).then(response => {
    if (response.ok) {
      const copy = response.clone();
      event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.put(event.request, copy)));
    }
    return response;
  }).catch(() => caches.open(CACHE_NAME).then(cache => cache.match(event.request)).then(cached => cached || Response.error())));
});
