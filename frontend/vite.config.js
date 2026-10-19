import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({ mode }) => {
  if (mode === 'mobile' && !loadEnv(mode, '.').VITE_API_ORIGIN) {
    throw new Error('Set VITE_API_ORIGIN in frontend/.env.mobile.local: the app has no server of its own to call.');
  }

  return {
    plugins: [react()],
    define: {
      global: 'window',
    },
    build: {
      outDir: 'dist',
      emptyOutDir: true,
      manifest: true,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (/[\\/]node_modules[\\/](react|react-dom|react-router-dom|scheduler)[\\/]/.test(id)) {
              return 'vendor-react';
            }
          },
        },
      },
    },
    server: {
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          secure: false,
        },
        '/ws': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          ws: true,
        },
      }
    }
  }
})
