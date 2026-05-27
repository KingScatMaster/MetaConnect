/**
 * MetaConnect Service Worker
 * Keeps the app alive and cacheable for offline launch
 */

const CACHE_NAME = 'metaconnect-v1';
const CACHE_URLS = [
    '/',
    '/static/style.css',
    '/static/app.js',
    '/static/manifest.json'
];

// Install — cache core files
self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE_NAME).then((cache) => cache.addAll(CACHE_URLS))
    );
    self.skipWaiting();
});

// Activate — clean old caches
self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((keys) =>
            Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k)))
        )
    );
    self.clients.claim();
});

// Fetch — serve from cache, fall back to network
self.addEventListener('fetch', (event) => {
    // Don't cache API calls or WebSocket
    if (event.request.url.includes('/api/') || event.request.url.includes('/ws')) {
        return;
    }

    event.respondWith(
        caches.match(event.request).then((cached) => {
            return cached || fetch(event.request);
        })
    );
});
