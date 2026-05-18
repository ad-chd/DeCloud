const sharp = require('sharp');
const pngToIco = require('png-to-ico').default;
const fs = require('fs');
const path = require('path');

const SRC = path.resolve(__dirname, 'assets', 'logo-source.png');
const ANDROID_RES = path.resolve(__dirname, '..', 'DeCloud-Android', 'app', 'src', 'main', 'res');
const ELECTRON_ASSETS = path.resolve(__dirname, 'assets');

const LEGACY_DENSITIES = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192,
};

const ADAPTIVE_DENSITIES = {
  'mipmap-mdpi': 108,
  'mipmap-hdpi': 162,
  'mipmap-xhdpi': 216,
  'mipmap-xxhdpi': 324,
  'mipmap-xxxhdpi': 432,
};

const ICO_SIZES = [16, 32, 48, 64, 128, 256];

(async () => {
  console.log('Loading source:', SRC);
  const srcMeta = await sharp(SRC).metadata();
  console.log(`Source: ${srcMeta.width}x${srcMeta.height}`);

  const { data: srcRaw, info: srcInfo } = await sharp(SRC).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const w = srcInfo.width, h = srcInfo.height;
  const px = Buffer.from(srcRaw);

  const isNearWhite = (i) => {
    const r = px[i], g = px[i+1], b = px[i+2];
    const maxCh = Math.max(r, g, b);
    const minCh = Math.min(r, g, b);
    if (maxCh < 110) return false;
    if (maxCh - minCh > 45) return false;
    return true;
  };

  const visited = new Uint8Array(w * h);
  const stack = [];
  const seed = (x, y) => { if (x >= 0 && x < w && y >= 0 && y < h) stack.push(x, y); };
  for (let x = 0; x < w; x++) { seed(x, 0); seed(x, h - 1); }
  for (let y = 0; y < h; y++) { seed(0, y); seed(w - 1, y); }

  let cleared = 0;
  while (stack.length) {
    const y = stack.pop(), x = stack.pop();
    const idx = y * w + x;
    if (visited[idx]) continue;
    visited[idx] = 1;
    const pi = idx * 4;
    if (!isNearWhite(pi)) continue;
    px[pi + 3] = 0;
    cleared++;
    if (x > 0) stack.push(x - 1, y);
    if (x < w - 1) stack.push(x + 1, y);
    if (y > 0) stack.push(x, y - 1);
    if (y < h - 1) stack.push(x, y + 1);
  }
  console.log(`Flood-filled ${cleared} near-white edge-connected pixels to transparent`);

  const trimmed = await sharp(px, { raw: { width: w, height: h, channels: 4 } })
    .trim({ threshold: 1 })
    .png()
    .toBuffer();
  const trimMeta = await sharp(trimmed).metadata();
  console.log(`After trim: ${trimMeta.width}x${trimMeta.height}`);

  const master = await sharp(trimmed)
    .resize(1024, 1024, {
      fit: 'contain',
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png()
    .toBuffer();
  console.log('Master 1024x1024 ready');

  for (const [dir, size] of Object.entries(LEGACY_DENSITIES)) {
    const outDir = path.join(ANDROID_RES, dir);
    fs.mkdirSync(outDir, { recursive: true });

    const xmlPath = path.join(outDir, 'ic_launcher.xml');
    if (fs.existsSync(xmlPath)) {
      fs.unlinkSync(xmlPath);
      console.log(`  removed ${dir}/ic_launcher.xml`);
    }

    await sharp(master).resize(size, size).png().toFile(path.join(outDir, 'ic_launcher.png'));
    await sharp(master).resize(size, size).png().toFile(path.join(outDir, 'ic_launcher_round.png'));
    console.log(`  ${dir}: ${size}x${size} (legacy + round)`);
  }

  console.log('\nAdaptive foregrounds (content at 66% of canvas)');
  for (const [dir, canvas] of Object.entries(ADAPTIVE_DENSITIES)) {
    const contentSize = Math.round(canvas * 0.66);
    const scaled = await sharp(trimmed)
      .resize(contentSize, contentSize, {
        fit: 'contain',
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      })
      .png()
      .toBuffer();

    await sharp({
      create: {
        width: canvas,
        height: canvas,
        channels: 4,
        background: { r: 0, g: 0, b: 0, alpha: 0 },
      },
    })
      .composite([{ input: scaled, gravity: 'center' }])
      .png()
      .toFile(path.join(ANDROID_RES, dir, 'ic_launcher_foreground.png'));
    console.log(`  ${dir}: ${canvas}x${canvas} (content ${contentSize}px centered)`);
  }

  console.log('\nElectron icons');
  fs.mkdirSync(ELECTRON_ASSETS, { recursive: true });

  const icoBuffers = [];
  for (const s of ICO_SIZES) {
    icoBuffers.push(await sharp(master).resize(s, s).png().toBuffer());
  }
  const icoBuf = await pngToIco(icoBuffers);
  fs.writeFileSync(path.join(ELECTRON_ASSETS, 'icon.ico'), icoBuf);
  console.log(`  icon.ico (${ICO_SIZES.join(', ')})`);

  await sharp(master).resize(512, 512).png().toFile(path.join(ELECTRON_ASSETS, 'icon.png'));
  console.log('  icon.png (512x512)');

  console.log('\nDone.');
})().catch((e) => {
  console.error('ERROR:', e);
  process.exit(1);
});
