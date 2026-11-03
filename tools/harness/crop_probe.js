// Crop-preview geometry of the harness (engine.js previewCrop) for a grid of sizes / scales / focus
// points. The JVM test PreviewCropTests compares every line with core/PreviewCrop.java, so the
// geometry validated by the harness pixel test (preview_crop) is the one the app uses.
// Output: srcW srcH outW outH ai workScale fx fy visW visH -> JSON geometry or null
'use strict';
const fs = require('fs'), path = require('path'), vm = require('vm');
const ctx = { window: {}, Math, Float32Array, Array, Object, Error, JSON };
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(__dirname, 'engine.js'), 'utf8'), ctx);
const { previewCrop } = ctx.window.Engine;

const sizes = [[3840, 2160, 3840, 2160], [1920, 1080, 3840, 2160], [1280, 720, 1920, 1080], [3840, 2160, 1920, 1080],
  [1920, 1080, 2560, 1440], [854, 480, 3416, 1920], [1919, 1077, 2560, 1440], [512, 512, 768, 768], [512, 512, 1024, 1024]];
const out = [];
for (const [sw, sh, ow, oh] of sizes)
  for (const [ai, ws] of [[false, 1], [true, 1], [true, 0.5], [true, 0.75]])
    for (const [fx, fy] of [[0.5, 0.5], [0, 0], [1, 1], [0.62, 0.41]])
      for (const [vw, vh] of [[960, 540], [1920, 1080], [154, 154]]) {
        const g = previewCrop(sw, sh, ow, oh, ai, ws, 12, fx, fy, vw, vh);
        const key = [sw, sh, ow, oh, ai ? 1 : 0, ws, fx, fy, vw, vh].join(' ');
        out.push(key + ' ' + (g ? [g.x0, g.y0, g.w, g.h, g.outW, g.outH, g.visX, g.visY, g.visW, g.visH, g.srcX, g.srcY, g.srcW, g.srcH, g.margin, g.fullFrame ? 1 : 0].join(' ') : 'null'));
      }
process.stdout.write(out.join('\n') + '\n');
