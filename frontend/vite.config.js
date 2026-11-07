import { defineConfig } from 'vite';

// One build for every target: Tauri loads dist/ from disk, the Go server serves
// it under /app/, so every URL is relative.
export default defineConfig({
  base: './',
  clearScreen: false,
  server: { port: 5173, strictPort: true, host: process.env.TAURI_DEV_HOST || false },
  envPrefix: ['VITE_', 'TAURI_ENV_'],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: ['es2022', 'chrome105', 'safari15'],
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
});
