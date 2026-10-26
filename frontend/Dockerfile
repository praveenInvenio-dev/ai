FROM node:20-bookworm AS build
WORKDIR /build
# Lockfile + npm ci: same dependency versions every build, and the layer caches
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build -- --configuration production

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/ai-story-studio-frontend/browser /usr/share/nginx/html
EXPOSE 80
