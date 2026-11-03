// Desktop harness runner: serves shaders + data, runs tests in headless Chromium (WebGL2 via
// SwiftShader = a conformant GLSL ES 3.00 implementation), stores outputs for Python checks.
// usage: node run.js <dataDir> <outDir> test1 [test2 ...]   (or "all")
const http = require('http'), fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const ROOT = path.resolve(__dirname, '../..');
const SHADERS = path.join(ROOT, 'app/src/main/assets/shaders');
const EXTRA = path.join(__dirname, 'glsl');   // test-only shaders (v0 baseline port)
const [dataDir, outDir, ...tests] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (req.method === 'POST' && u.pathname === '/save') {
    const chunks = []; req.on('data', c => chunks.push(c));
    req.on('end', () => { const f = path.join(outDir, path.basename(u.searchParams.get('name'))); (u.searchParams.get('append') ? fs.appendFileSync : fs.writeFileSync)(f, Buffer.concat(chunks)); res.end('ok'); });
    return;
  }
  if (u.pathname === '/shaders') { res.setHeader('content-type', 'application/json'); res.end(JSON.stringify(fs.readdirSync(SHADERS).concat(fs.existsSync(EXTRA) ? fs.readdirSync(EXTRA) : []))); return; }
  let f;
  if (u.pathname.startsWith('/shader/')) { f = path.join(SHADERS, path.basename(u.pathname)); if (!fs.existsSync(f)) f = path.join(EXTRA, path.basename(u.pathname)); }
  else if (u.pathname.startsWith('/data/')) f = path.join(dataDir, u.pathname.slice(6));
  else if (u.pathname.startsWith('/asset/')) f = path.join(ROOT, 'app/src/main/assets', u.pathname.slice(7));
  else f = path.join(__dirname, u.pathname === '/' ? 'index.html' : u.pathname);
  if (!fs.existsSync(f)) { res.statusCode = 404; res.end(); return; }
  res.end(fs.readFileSync(f));
});
server.listen(0, async () => {
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--js-flags=--max-old-space-size=4096'] });
  const page = await browser.newPage();
  page.on('console', m => console.log('[page]', m.text()));
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  await page.goto(`http://127.0.0.1:${port}/`);
  await page.waitForFunction(() => window.READY === true, null, { timeout: 60000 });
  const list = tests[0] === 'all' ? await page.evaluate(() => Object.keys(window.TESTS)) : tests;
  let failed = 0;
  for (const t of list) {
    const t0 = Date.now();
    try {
      const r = await page.evaluate(n => window.TESTS[n](), t);
      console.log(JSON.stringify({ test: t, ms: Date.now() - t0, result: r }));
      fs.writeFileSync(path.join(outDir, t + '.json'), JSON.stringify(r, null, 1));
    } catch (e) { failed++; console.log(JSON.stringify({ test: t, error: String(e.message || e) })); }
  }
  await browser.close(); server.close(); process.exit(failed ? 1 : 0);
});
