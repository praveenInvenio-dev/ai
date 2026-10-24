FROM mcr.microsoft.com/playwright:v1.64.1-noble
WORKDIR /app
# ffmpeg encodes the 3D clips (the Playwright image does not put one on the PATH)
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY package.json ./
RUN npm install --no-audit --no-fund --omit=dev
# Motion Canvas: the player project is built ONCE here; at run time scenes are driven by plain variables (nothing is generated or compiled)
COPY motion/package.json ./motion/
RUN cd motion && npm install --no-audit --no-fund
COPY motion ./motion
RUN cd motion && (npm run build || echo 'MOTION CANVAS BUILD FAILED: the renderer will report it unavailable') && rm -rf node_modules
# Revideo (MIT): animated step-flow clips timed to the narration. Dependencies + Puppeteer's own Chrome are installed here (Revideo is built for it);
# the project is rendered on demand. If the Chrome download fails the renderer reports animated clips unavailable and lessons keep using still frames.
COPY revideo/package.json ./revideo/
RUN cd revideo && npm install --no-audit --no-fund
RUN cd revideo && (npx puppeteer browsers install chrome || echo 'REVIDEO: Chrome download failed; animated clips will be reported unavailable')
COPY revideo ./revideo
COPY server.mjs three-scene.js ./
ENV PORT=5020
EXPOSE 5020
CMD ["npm","start"]
