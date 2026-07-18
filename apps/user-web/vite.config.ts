import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy: {
      '/api/v1/conversations': 'http://127.0.0.1:4174',
      '/api/v1/generations': 'http://127.0.0.1:4174',
      '/api/v1/model-registry': 'http://127.0.0.1:8083'
    }
  }
});
