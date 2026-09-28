import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const tunnel = process.env.CLOUDFLARE_MODE === 'true'
  ? new URL(process.env.CLOUDFLARE_PUBLIC_URL || 'https://ctfmon.shop')
  : undefined

export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0', port: 8085, strictPort: true,
    allowedHosts: tunnel ? [tunnel.hostname] : [],
    // The temporary Windows tunnel uses a full-page reload after changes.
    hmr: tunnel ? false : undefined,
    ws: tunnel ? false : undefined,
    https: process.env.LOCAL_HTTPS === 'true' ? {
      cert: readFileSync(fileURLToPath(new URL('../.tools/certs/server.pem', import.meta.url))),
      key: readFileSync(fileURLToPath(new URL('../.tools/certs/server-key.pem', import.meta.url))),
    } : undefined,
    proxy: { '/api': { target: 'http://127.0.0.1:8080', changeOrigin: false } },
  },
})
