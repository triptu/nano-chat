import { existsSync } from "node:fs"
import { defineConfig, type Plugin } from "vite"

const has = (file: string) => existsSync(new URL(`public/media/${file}`, import.meta.url))

const video = `<video class="trailer-video" src="/media/trailer-v3.mp4" poster="/media/poster-v3.jpg" controls playsinline preload="metadata"></video>`

/** Shows the trailer once `public/media/trailer-v3.mp4` exists, and the screenshots in its place until then */
const trailer = (): Plugin => ({
  name: "trailer",
  transformIndexHtml: (html) =>
    has("trailer-v3.mp4") ? html.replace(/<!-- trailer -->[\s\S]*?<!-- \/trailer -->/, video) : html,
})

export default defineConfig({ plugins: [trailer()] })
