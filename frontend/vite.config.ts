import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8081'

  return {
    plugins: [react(), tailwindcss()],
    resolve: { alias: { '@': path.resolve(__dirname, './src') } },
    server: {
      host: true,
      port: 5175,
      allowedHosts: true,
      proxy: { '/api': { target: apiProxyTarget, changeOrigin: true } },
    },
    build: {
      rollupOptions: {
        output: {
          // VYB-0958: the libraries every screen shares are their own long-lived chunks, so a change to application code does not make
          // a returning visitor download React again; each screen is a chunk of its own (see app/App.tsx).
          manualChunks(id) {
            if (!id.includes('node_modules')) return undefined
            if (/node_modules\/(react|react-dom|react-router|react-router-dom|scheduler|@remix-run)\//.test(id)) return 'react'
            if (id.includes('@tanstack')) return 'query'
            if (id.includes('lucide-react')) return 'icons'
            return undefined
          },
        },
      },
    },
    preview: {
      host: true,
      allowedHosts: true,
    },
  }
})