index index.html;
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options DENY always;
add_header Referrer-Policy strict-origin-when-cross-origin always;
add_header Permissions-Policy "geolocation=(self), camera=(), microphone=()" always;
add_header Cache-Control $cache_policy;
location = /ngsw-worker.js { add_header Cache-Control "no-cache"; }
location = /firebase-messaging-sw.js { add_header Cache-Control "no-cache"; }
location / { try_files $uri $uri/ /index.html; }
