import { createRequire } from 'node:module';
import { defineConfig } from 'vite';
// the Motion Canvas plugin is CommonJS: resolve its default export explicitly
const require = createRequire(import.meta.url);
const mod = require('@motion-canvas/vite-plugin');
const motionCanvas = mod.default || mod;
export default defineConfig({ plugins: [motionCanvas({ project: './src/project.ts' })], build: { minify: false } });
