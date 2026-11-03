// Builds the static GenKeyboard website into website/dist.
// The privacy page is generated from PRIVACY.md so it always matches the policy in the repo,
// and images come straight from store/ so they are not duplicated.
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const root = join(import.meta.dirname, '..');
const out = join(import.meta.dirname, 'dist');

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'assets'), { recursive: true });
for (const f of ['index.html', 'style.css']) cpSync(join(import.meta.dirname, f), join(out, f));
for (const f of ['graphics/icon-512.png', 'graphics/feature-1024x500.png',
  'screenshots/01-suggestions.png', 'screenshots/02-ai-panel.png', 'screenshots/03-ai-result.png']) {
  cpSync(join(root, 'store', f), join(out, 'assets', f.split('/').pop()));
}

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const inline = (s) => esc(s)
  .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  .replace(/`([^`]+)`/g, '<code>$1</code>')
  .replace(/https:\/\/[^\s),]+/g, (u) => `<a href="${u}">${u}</a>`)
  .replace(/\b[\w.+-]+@[\w-]+\.[\w.]+\b/g, (m) => `<a href="mailto:${m}">${m}</a>`);

// Minimal Markdown: #/## headings, "- " lists, paragraphs. Enough for PRIVACY.md.
const html = [];
let list = false;
let para = [];
const flush = () => { if (para.length) { html.push(`<p>${inline(para.join(' '))}</p>`); para = []; } };
for (const line of readFileSync(join(root, 'PRIVACY.md'), 'utf8').split(/\r?\n/)) {
  const h = line.match(/^(#{1,3})\s+(.*)$/);
  const li = line.match(/^\s*-\s+(.*)$/);
  if (h || li || !line.trim()) flush();
  if (!li && list) { html.push('</ul>'); list = false; }
  if (h) html.push(`<h${h[1].length}>${inline(h[2])}</h${h[1].length}>`);
  else if (li) { if (!list) { html.push('<ul>'); list = true; } html.push(`<li>${inline(li[1])}</li>`); }
  else if (line.trim()) para.push(line.trim());
}
flush();
if (list) html.push('</ul>');

const page = readFileSync(join(import.meta.dirname, 'index.html'), 'utf8');
const head = page.slice(0, page.indexOf('<main>')).replace(/<title>.*?<\/title>/, '<title>Privacy Policy - GenKeyboard</title>');
const foot = page.slice(page.indexOf('</main>') + '</main>'.length);
mkdirSync(join(out, 'privacy'), { recursive: true });
writeFileSync(join(out, 'privacy', 'index.html'), `${head}<main class="wrap doc">\n${html.join('\n')}\n</main>${foot}`);
console.log(`website built: ${out}`);
