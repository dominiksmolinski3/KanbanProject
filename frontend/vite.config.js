import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    global: 'window',
  },
  build: {
    // Set the output directory to Spring Boot static resources folder
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      // The backend serves every REST route under /api (Spring adds the prefix centrally), so one
      // entry covers the whole API. Before that, each top-level route needed its own entry here and
      // a new one silently 404'd in dev until someone remembered to add it.
      '/api': {
        target: 'http://localhost:8080', // Spring Boot server port
        changeOrigin: true,
        secure: false,
      },
      // STOMP over SockJS. Not under /api: the destinations are resolved by the broker, not MVC.
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    }
  }
})