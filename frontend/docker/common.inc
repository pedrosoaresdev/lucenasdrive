index index.html;
include /etc/nginx/conf.d/security-headers.inc;
add_header Cache-Control $cache_policy;
location = /ngsw-worker.js { include /etc/nginx/conf.d/security-headers.inc; add_header Cache-Control "no-cache"; }
location = /firebase-messaging-sw.js { include /etc/nginx/conf.d/security-headers.inc; add_header Cache-Control "no-cache"; }
location / { try_files $uri $uri/ /index.html; }
