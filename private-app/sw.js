const CACHE_NAME = 'clareza-privado-v1';
const FILES = ['./', './index.html', './style.css', './app.js', './manifest.webmanifest', './icon.svg'];
self.addEventListener('install', event => { event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(FILES))); self.skipWaiting(); });
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('fetch', event => { if (event.request.method === 'GET') event.respondWith(caches.match(event.request).then(cached => cached || fetch(event.request))); });
