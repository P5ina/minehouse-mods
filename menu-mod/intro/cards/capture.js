const { chromium } = require('playwright');
const fs = require('fs');
const [card, seconds, out, only] = [process.argv[2], +process.argv[3], process.argv[4], process.argv[5]];

// Virtual time: the page's clock and animation frames only move when we step them.
const virt = () => {
  let now = 0; let q = []; let id = 0;
  performance.now = () => now;
  const d0 = Date.now(); Date.now = () => d0 + now;
  window.requestAnimationFrame = (cb) => { q.push({ id: ++id, cb }); return id; };
  window.cancelAnimationFrame = (i) => { q = q.filter((e) => e.id !== i); };
  window.__step = (ms) => {
    now += ms;
    const run = q; q = [];
    for (const e of run) { try { e.cb(now); } catch (err) { console.error(err); } }
  };
};

(async () => {
  const b = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  p.on('pageerror', (e) => console.error('pageerror', e.message));
  await p.addInitScript(virt);
  const cdp = await p.context().newCDPSession(p);
  await p.goto(`http://localhost:3123/intro-cards?card=${card}`, { waitUntil: 'load' });
  await p.waitForTimeout(3000);
  await p.evaluate(() => document.fonts.ready);
  for (let i = 0; i < 10; i++) await p.evaluate(() => window.__step(100));
  await p.evaluate(() => window.__go());
  const fps = 24, n = Math.round(seconds * fps);
  const want = only ? new Set(only.split(',').map(Number)) : null;
  const t0 = Date.now();
  for (let i = 0; i < n; i++) {
    // two half-steps so React state set in one frame is rendered in the next
    await p.evaluate((ms) => window.__step(ms), 500 / fps);
    await p.evaluate((ms) => window.__step(ms), 500 / fps);
    if (!want || want.has(i)) {
      const r = await cdp.send('Page.captureScreenshot', { format: 'png' });
      fs.writeFileSync(`${out}/f${String(i).padStart(4, '0')}.png`, Buffer.from(r.data, 'base64'));
    }
    if (i % 24 === 0) console.log(`frame ${i}/${n} ${((Date.now() - t0) / 1000).toFixed(0)}s`);
  }
  await b.close();
})();
