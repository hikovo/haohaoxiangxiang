import {defineConfig} from 'vite';
export default defineConfig({clearScreen:false, cacheDir:'.vite-cache', server:{port:1420,strictPort:true}, build:{target:'chrome63',cssTarget:'chrome63',sourcemap:false}});
