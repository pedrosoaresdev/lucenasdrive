# Cabeçalhos de segurança (incluído no servidor e em cada location que define add_header, pois o nginx
# não herda add_header quando a location declara o seu).
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options DENY always;
add_header Referrer-Policy strict-origin-when-cross-origin always;
add_header Permissions-Policy "geolocation=(self), camera=(), microphone=()" always;
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
# CSP: scripts só do próprio site (e Google Maps, se usado); conexões HTTPS/WSS (API, Firebase, OpenStreetMap);
# imagens de qualquer HTTPS (tiles de mapa, fotos); estilos inline exigidos por componentes do Angular/Leaflet.
add_header Content-Security-Policy "default-src 'self'; script-src 'self' https://maps.googleapis.com https://maps.gstatic.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com; img-src 'self' data: blob: https:; connect-src 'self' https: wss:; worker-src 'self' blob:; frame-src https://*.firebaseapp.com; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'" always;
