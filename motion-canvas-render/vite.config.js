import { createRequire } from 'node:module';
import { defineConfig } from 'vite';

// The Motion Canvas Vite plugin is CommonJS. Resolve its default export
// explicitly so Node/Vite ESM interop does not turn it into a non-callable object.
const require = createRequire(import.meta.url);
const motionCanvasModule = require('@motion-canvas/vite-plugin');
const motionCanvas = motionCanvasModule.default || motionCanvasModule;

export default defineConfig({
  plugins: [motionCanvas({ project: './src/project.ts' })],
  server: { host: '0.0.0.0', port: 5173 },
});
