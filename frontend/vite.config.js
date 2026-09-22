import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    global: 'window',
  },
  build: {
    // nginx serves this directory from disk now (see CLAUDE.md, "Two containers, one origin") -
    // Spring stopped serving static resources when the tier split landed.
    outDir: 'dist',
    emptyOutDir: true,
    // Read by scripts/check-bundle-size.mjs, which walks the entry's static import graph to know
    // what actually downloads before the sign-in form paints - a dynamic import() (a lazy route)
    // does not appear in that graph, which is the distinction the budget is built on.
    manifest: true,
    rollupOptions: {
      output: {
        // React itself barely ever changes version between deploys, so it earns its own chunk
        // under the immutable-asset caching the split gave /assets/ - a react-only chunk stays
        // cached across a deploy that only touched application code. Route-specific weight
        // (SockJS, the STOMP client, react-xarrows) is deliberately left alone here: it already
        // lands in its own chunk from the dynamic `import()` boundary in App.jsx, and folding it
        // into a single catch-all "vendor" bucket would force it to load before the sign-in form
        // paints, undoing the point of that split.
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