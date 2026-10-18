import { defineConfig } from 'vite';
import motionCanvas from '@motion-canvas/vite-plugin';
export default defineConfig({ plugins: [motionCanvas({ project: './src/project.ts' })], server: { host: '0.0.0.0', port: 5173 } });
