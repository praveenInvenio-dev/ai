FROM node:20-bookworm AS build
WORKDIR /build
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build -- --configuration production

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/ai-story-studio-frontend/browser /usr/share/nginx/html
EXPOSE 80
