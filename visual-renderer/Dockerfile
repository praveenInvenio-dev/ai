FROM mcr.microsoft.com/playwright:v1.64.1-noble
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund --omit=dev
# Motion Canvas: the player project is built ONCE here; at run time scenes are driven by plain variables (nothing is generated or compiled)
COPY motion/package.json ./motion/
RUN cd motion && npm install --no-audit --no-fund
COPY motion ./motion
RUN cd motion && (npm run build || echo 'MOTION CANVAS BUILD FAILED: the renderer will report it unavailable') && rm -rf node_modules
COPY server.mjs three-scene.js ./
ENV PORT=5020
EXPOSE 5020
CMD ["npm","start"]
