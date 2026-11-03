// Counts the texture bytes the harness FramePipeline constructor allocates (8 bytes per texel,
// as on the device) for a grid of stage combinations, without a GPU. The JVM test MemoryTests
// compares every line with core/MemoryBudget.pipeline().
// Output: srcW srcH outW outH temporal ai workScale bytes
'use strict';
const fs = require('fs'), path = require('path'), vm = require('vm');
const ctx = { window: {}, Math, Float32Array, Array, Object, Error };
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(__dirname, 'engine.js'), 'utf8'), ctx);
const { FramePipeline } = ctx.window.Engine;

const sizes = [[1920, 1080, 1920, 1080], [1920, 1080, 3840, 2160], [3840, 2160, 3840, 2160],
  [1280, 720, 1920, 1080], [3840, 2160, 1920, 1080], [854, 480, 3416, 1920], [1919, 1077, 2560, 1440]];
const out = [];
for (const [sw, sh, ow, oh] of sizes)
  for (const temporal of [false, true])
    for (const ai of [false, true])
      for (const scale of ai ? [1, 0.5] : [1]) {
        let bytes = 0;
        const k = { tex: (w, h) => { bytes += 8 * w * h; return { w, h }; } };
        const params = { temporal: temporal ? { strength: 0.5 } : null, ai: ai ? { workScale: scale } : null };
        new FramePipeline(k, sw, sh, ow, oh, params, null);
        out.push([sw, sh, ow, oh, temporal ? 1 : 0, ai ? 1 : 0, scale, bytes].join(' '));
      }
process.stdout.write(out.join('\n') + '\n');
