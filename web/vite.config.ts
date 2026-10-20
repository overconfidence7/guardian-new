/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

const brand = '#1f5f57';
const paper = '#f7f4ee';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      // One service worker does both jobs: precaching the app shell and receiving web
      // push from FCM. It is named firebase-messaging-sw.js (the name FCM looks for) and
      // registered by src/lib/pwa.ts, which also hands it to getToken().
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'firebase-messaging-sw.ts',
      injectRegister: false,
      manifestFilename: 'manifest.webmanifest',
      includeAssets: ['icons/*.svg', 'icons/*.png'],
      injectManifest: {
        globPatterns: ['**/*.{js,css,html,svg,png,webmanifest}'],
        rollupFormat: 'iife',
      },
      manifest: {
        id: '/',
        name: 'Guardian Mode',
        short_name: 'Guardian',
        description: "Set up and help with your parent's Android phone from wherever you are.",
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: paper,
        theme_color: brand,
        categories: ['lifestyle', 'utilities'],
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
          { src: '/icons/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
        ],
      },
      devOptions: { enabled: false },
    }),
  ],
  server: { port: 5173, strictPort: true, fs: { allow: ['..'] } },
  preview: { port: 4173, strictPort: true },
  build: {
    sourcemap: true,
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (id.includes('node_modules/firebase') || id.includes('node_modules/@firebase')) return 'firebase';
          if (id.includes('node_modules/react') || id.includes('node_modules/scheduler')) return 'react';
          return undefined;
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    css: false,
  },
});
