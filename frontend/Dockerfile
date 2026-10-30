# Portais Lucena Drive (passageiro, motorista, admin) servidos por Nginx.
# BUILD_CONFIGURATION=development usa o Firebase Emulator (docker compose local).
FROM node:24-alpine AS build
ARG BUILD_CONFIGURATION=production
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npx ng build passenger --configuration ${BUILD_CONFIGURATION} \
 && npx ng build driver --configuration ${BUILD_CONFIGURATION} \
 && npx ng build admin --configuration ${BUILD_CONFIGURATION}

FROM nginx:1.27-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY docker/common.inc /etc/nginx/conf.d/common.inc
COPY docker/security-headers.inc /etc/nginx/conf.d/security-headers.inc
COPY --from=build /src/dist/passenger/browser /usr/share/nginx/passenger
COPY --from=build /src/dist/driver/browser /usr/share/nginx/driver
COPY --from=build /src/dist/admin/browser /usr/share/nginx/admin
EXPOSE 4200 4201 4202
