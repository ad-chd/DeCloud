const sharp = require('sharp');
const path = require('path');

const screenshotsDir = path.resolve(__dirname, '..', 'docs', 'screenshots');
const frames = ['front.jpg', 'backup.jpg', 'summary.jpg'];
const targetWidth = 400;          // pixels — keeps the GIF small for fast README load
const frameDelayMs = 1800;        // each phone screen visible for 1.8s

(async () => {
  // Step 1: resize each frame to target width, record natural height
  const widthLocked = [];
  for (const file of frames) {
    const meta = await sharp(path.join(screenshotsDir, file))
      .resize({ width: targetWidth })
      .toBuffer({ resolveWithObject: true });
    widthLocked.push({ file, buffer: meta.data, height: meta.info.height });
  }

  // Step 2: find the tallest frame — every other frame gets padded to match
  const canvasHeight = Math.max(...widthLocked.map(f => f.height));
  console.log(`Canvas: ${targetWidth}x${canvasHeight} (tallest frame wins, others letterboxed)`);

  // Step 3: pad each frame with black so all frames share identical dimensions
  const padded = [];
  for (const f of widthLocked) {
    const pad = canvasHeight - f.height;
    const top = Math.floor(pad / 2);
    const bottom = pad - top;
    const { data, info } = await sharp(f.buffer)
      .extend({
        top, bottom, left: 0, right: 0,
        background: { r: 0, g: 0, b: 0, alpha: 1 },
      })
      .removeAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true });
    if (info.width !== targetWidth || info.height !== canvasHeight) {
      throw new Error(`${f.file}: padded to ${info.width}x${info.height}, expected ${targetWidth}x${canvasHeight}`);
    }
    padded.push(data);
    console.log(`  ${f.file}: ${f.height}px tall, padded ${top}px top + ${bottom}px bottom`);
  }

  // Step 4: stack vertically + encode as animated GIF
  const combined = Buffer.concat(padded);
  const outPath = path.join(screenshotsDir, 'demo.gif');
  await sharp(combined, {
    raw: { width: targetWidth, height: canvasHeight * frames.length, channels: 3 },
  })
    .gif({
      pageHeight: canvasHeight,
      delay: frames.map(() => frameDelayMs),
      loop: 0,
    })
    .toFile(outPath);

  const stats = require('fs').statSync(outPath);
  console.log(`\nWrote ${outPath} (${Math.round(stats.size / 1024)} KB, ${frames.length} frames, ${frameDelayMs}ms each)`);
})().catch((e) => {
  console.error('ERROR:', e);
  process.exit(1);
});
