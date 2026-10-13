import { defineConfig, defaultExclude } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  // 内联空 PostCSS 配置：同 vite.config.ts，阻止向上搜索到工作区根目录的 postcss.config.mjs
  css: {
    postcss: {},
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    // 排除 e2e 测试（Playwright），避免 vitest 误收集
    exclude: [...defaultExclude, 'e2e/**'],
  },
})