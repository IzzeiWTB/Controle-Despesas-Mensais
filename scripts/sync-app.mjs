// Generated copies keep Render's existing backend-only Docker context working.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const source = path.join(root, 'private-app');
const targets = [path.join(root, 'backend/src/main/resources/static'), path.join(root, 'mobile/www')];
const files = ['index.html', 'app.js', 'style.css', 'sw.js', 'icon.svg', 'manifest.webmanifest'];
const check = process.argv.includes('--check');
for (const target of targets) {
  if (!check) await mkdir(target, { recursive: true });
  for (const file of files) {
    let content = await readFile(path.join(source, file));
    if (target.includes(`${path.sep}backend${path.sep}`) && file === 'index.html') {
      content = Buffer.from(content.toString().replace('<head>', '<head>\n  <meta name="clareza-api" content="/api">'));
    }
    if (check) {
      const actual = await readFile(path.join(target, file));
      if (!content.equals(actual)) throw new Error(`Cópia desatualizada: ${target}/${file}. Execute node scripts/sync-app.mjs.`);
    } else await writeFile(path.join(target, file), content);
  }
}
console.log(check ? 'Cópias da PWA verificadas.' : 'PWA sincronizada para Render e Android.');
