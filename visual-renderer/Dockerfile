FROM mcr.microsoft.com/playwright:v1.52.0-noble
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY server.mjs ./
COPY motion-template ./motion-template
ENV PORT=5020
EXPOSE 5020
CMD ["npm","start"]
