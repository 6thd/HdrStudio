// Harness tests. Each returns a JSON summary; heavy outputs are saved for tools/harness/check.py.
(function () {
  const meta = () => fetch('/data/meta.json').then(r => r.json());
  async function load(name, w, h) {
    const buf = await (await fetch('/data/' + name + '.f32')).arrayBuffer();
    return K.tex(w, h, { data: new Float32Array(buf) });
  }
  // chunked: very large POST bodies are mirrored into DevTools protocol events and can overflow the runner
  async function save(name, f32) {
    const CH = 4 << 20;   // floats per chunk (16 MB)
    for (let off = 0, i = 0; off < f32.length || i === 0; off += CH, i++) {
      const part = f32.subarray(off, Math.min(f32.length, off + CH));
      await fetch('/save?name=' + name + (i ? '&append=1' : ''), { method: 'POST', body: part.slice().buffer });
    }
  }
  const sdrHdr = (o = {}) => Object.assign({ sdrGamma: 0, refWhite: 203, peak: 1000, expand: 0.8, knee: 0.5, vibrance: 0.0, skinProtect: 0.6, srcPeak: 1000, sdrPeakNits: 250 }, o);
  let srEngine = null;
  async function sr() {
    if (srEngine) return srEngine;
    const buf = await (await fetch('/asset/models/srvgg_general_x4v3.bin')).arrayBuffer();
    return (srEngine = new Engine.SrEngine(K, new Float32Array(buf)));
  }
  function hdrMap(input, out, h, inDomain, outMode, expand) {
    K.run('hdr_map.frag', out, { uIn: input, uInDomain: inDomain, uOutMode: outMode, uSdrGamma: h.sdrGamma, uRefWhite: h.refWhite, uPeak: h.peak,
      uExpand: expand ?? h.expand, uKnee: h.knee, uVibrance: h.vibrance, uSkinProtect: h.skinProtect, uSrcPeak: h.srcPeak, uSdrPeakNits: h.sdrPeakNits });
  }

  window.TESTS = {
    async compile() {
      const ok = [], skipped = [];
      for (const n of Object.keys(SRC)) {
        if (!n.endsWith('.frag')) continue;
        if (n.startsWith('input_ext_')) { skipped.push(n + ' (Android external sampler)'); continue; }
        if (n.startsWith('v0_')) continue;
        K.program(n); ok.push(n);
      }
      return { compiled: ok, skipped };
    },

    async color() {
      const N = 1024, out = K.tex(N, 1, { nearest: true });
      const ramp = new Float32Array(N * 4); for (let i = 0; i < N; i++) ramp.set([i / (N - 1), i / (N - 1), i / (N - 1), 1], i * 4);
      const inT = K.tex(N, 1, { data: ramp, nearest: true });
      for (const [nm, h, exp] of [['ramp_e0', sdrHdr(), 0], ['ramp_e1', sdrHdr(), 1], ['ramp_e1_g22', sdrHdr({ sdrGamma: 1 }), 1]]) {
        hdrMap(inT, out, h, 0, 0, exp); await save(nm + '.f32', K.read(out));
      }
      hdrMap(inT, out, sdrHdr(), 0, 1, 0); await save('ramp_sdr_identity.f32', K.read(out));
      // random colours SDR->SDR identity, and SDR->HDR hue check
      const M = 4096, rnd = new Float32Array(M * 4);
      let s = 7; const r = () => (s = (s * 16807) % 2147483647) / 2147483647;
      for (let i = 0; i < M; i++) rnd.set([r(), r(), r(), 1], i * 4);
      const rT = K.tex(64, 64, { data: rnd, nearest: true }), rO = K.tex(64, 64, { nearest: true });
      await save('rand_in.f32', rnd);
      hdrMap(rT, rO, sdrHdr({ skinProtect: 0 }), 0, 1, 0); await save('rand_sdr.f32', K.read(rO));
      hdrMap(rT, rO, sdrHdr({ skinProtect: 0 }), 0, 0, 1); await save('rand_hdr_e1.f32', K.read(rO));
      hdrMap(rT, rO, sdrHdr({ skinProtect: 0, vibrance: 0.5 }), 0, 0, 1); await save('rand_hdr_vib.f32', K.read(rO));
      // PQ -> SDR tone mapping of a nits ramp (input in E_PQ)
      const pq = new Float32Array(N * 4);
      const pqE = n => { const y = Math.pow(n / 10000, 0.1593017578125); return Math.pow((0.8359375 + 18.8515625 * y) / (1 + 18.6875 * y), 78.84375); };
      for (let i = 0; i < N; i++) { const e = pqE(1000 * i / (N - 1)); pq.set([e, e, e, 1], i * 4); }
      const pT = K.tex(N, 1, { data: pq, nearest: true });
      hdrMap(pT, out, sdrHdr(), 1, 1); await save('pq_to_sdr.f32', K.read(out));
      hdrMap(pT, out, sdrHdr(), 1, 0); await save('pq_to_pq.f32', K.read(out));
      // Y'CbCr input path: codes prepared by check.py -> /data/yuv_*.f32 (if present)
      const res = {};
      for (const [nm, u] of [
        ['yuv_pq10', { uCodeScale: 1023.984375, uBitDepth: 10, uFullRange: false, uMatrix: 1, uTransfer: 1, uSrc2020: true, uWorkDomain: 1, uHlgPeak: 1000 }],
        ['yuv_hlg10', { uCodeScale: 1023.984375, uBitDepth: 10, uFullRange: false, uMatrix: 1, uTransfer: 2, uSrc2020: true, uWorkDomain: 1, uHlgPeak: 1000 }],
        ['yuv_sdr8', { uCodeScale: 255, uBitDepth: 8, uFullRange: false, uMatrix: 0, uTransfer: 0, uSrc2020: false, uWorkDomain: 0, uHlgPeak: 1000 }]]) {
        const rsp = await fetch('/data/' + nm + '.f32'); if (!rsp.ok) { res[nm] = 'missing'; continue; }
        const d = new Float32Array(await rsp.arrayBuffer()); const n = d.length / 4;
        const yT = K.tex(n, 1, { data: d, nearest: true }), yO = K.tex(n, 1, { nearest: true });
        K.run('input_tex_yuv.frag', yO, Object.assign({ uTex: yT }, u));
        await save(nm + '_out.f32', K.read(yO)); res[nm] = n;
      }
      // present: dither statistics on a constant field
      const cst = new Float32Array(256 * 256 * 4).fill(0.5);
      const cT = K.tex(256, 256, { data: cst, nearest: true }), cO = K.tex(256, 256, { nearest: true });
      K.run('present.frag', cO, { uIn: cT, uComp: [1, 0, 0, 0, 1, 0, 0, 0, 1], uCompOffset: [0, 0, 0], uLsb: 1 / 1023, uDither: true, uFlipY: false });
      await save('present_dither.f32', K.read(cO));
      return res;
    },

    async sr_reference() {
      const m = await meta(), e = await sr();
      const inT = await load('sr_crop', m.sr_crop.w, m.sr_crop.h);
      const out = K.tex(m.sr_crop.w * 4, m.sr_crop.h * 4, { nearest: true });
      const tiles = e.run(inT, out, 64);
      await save('sr_crop_out.f32', K.read(out));
      return { tiles };
    },

    async sr_tiling() {
      const m = await meta(), e = await sr(), d = m.sr_tile_img;
      const inT = await load('sr_tile_img', d.w, d.h);
      const res = {};
      for (const [nm, T, f] of [['t128_f1', 128, 1], ['t24_f1', 24, 1], ['t128_f05', 128, 0.5], ['t24_f05', 24, 0.5], ['t40_f075', 40, 0.75], ['t128_f075', 128, 0.75]]) {
        const out = K.tex(Math.round(d.w * 4 * f), Math.round(d.h * 4 * f), { nearest: true });
        res[nm] = e.run(inT, out, T);
        await save('srt_' + nm + '.f32', K.read(out)); K.del(out);
      }
      return res;
    },

    async motion() {
      const m = await meta(), s = m.seqs.pan;
      const p = { workDomain: 0, outMode: 1, temporal: { strength: 0.8 }, hdr: sdrHdr() };
      const fp = new Engine.FramePipeline(K, s.w, s.h, s.w, s.h, p, null);
      const vecs = [];
      for (let i = 0; i < 4; i++) {
        const t = await load('pan_clean_' + String(i).padStart(2, '0'), s.w, s.h);
        fp.process(t); K.del(t);
        if (i > 0) vecs.push(Array.from(K.read(fp.mvFinal)).filter((_, j) => j % 4 < 2));
        if (i === 3) { for (let L = 0; L < 4; L++) await save('mv_lvl' + L + '.f32', K.read(fp.mv[L])); await save('mv_lvl0.f32', K.read(fp.mvFinal)); }
      }
      await save('mv_pan.f32', new Float32Array(vecs.flat()));
      return { mvW: fp.mv[0].w, mvH: fp.mv[0].h, frames: vecs.length, cuts: fp.info.cut };
    },

    async temporal() {
      const m = await meta(), res = {};
      for (const [seq, prefix] of [['pan', 'pan_noisy_'], ['obj', 'obj_noisy_']]) {
        const s = m.seqs[seq];
        for (const [mode, p] of [
          ['off', { workDomain: 0, outMode: 1, hdr: sdrHdr() }],
          ['spatial', { workDomain: 0, outMode: 1, denoise: { luma: 0.6, chroma: 0.6, grainKeep: 0.15 }, hdr: sdrHdr() }],
          ['temporal', { workDomain: 0, outMode: 1, temporal: { strength: 0.8 }, hdr: sdrHdr() }],
          ['both', { workDomain: 0, outMode: 1, denoise: { luma: 0.4, chroma: 0.6, grainKeep: 0.2 }, temporal: { strength: 0.8 }, hdr: sdrHdr() }]]) {
          const fp = new Engine.FramePipeline(K, s.w, s.h, s.w, s.h, p, null);
          const sig = [];
          for (let i = 0; i < s.n; i++) {
            const t = await load(prefix + String(i).padStart(2, '0'), s.w, s.h);
            const o = fp.process(t); K.del(t);
            await save(`tmp_${seq}_${mode}_${String(i).padStart(2, '0')}.f32`, K.read(o));
            sig.push(fp.info.sigma);
          }
          res[seq + '_' + mode] = { sigma: sig[0] };
        }
      }
      return res;
    },

    async debug_obj() {
      const m = await meta(), s = m.seqs.obj;
      const fp = new Engine.FramePipeline(K, s.w, s.h, s.w, s.h, { workDomain: 0, outMode: 1, temporal: { strength: 0.8 }, hdr: sdrHdr() }, null);
      const info = [];
      for (let i = 0; i < 8; i++) {
        const t = await load('obj_noisy_' + String(i).padStart(2, '0'), s.w, s.h);
        fp.process(t); K.del(t); info.push([fp.info.cut, fp.info.sigma]);
        if (i === 6) await save('dbg_mv0.f32', K.read(fp.mvFinal));
      }
      return info;
    },

    // Temporal filtering combined with a resolution change (classic path, no AI): x1.5 and x0.5
    async resize_temporal() {
      const m = await meta(), s = m.seqs.pan, res = {};
      for (const [tag, f] of [['up15', 1.5], ['down05', 0.5]]) {
        const ow = Math.round(s.w * f / 2) * 2, oh = Math.round(s.h * f / 2) * 2;
        for (const [mode, prefix, p] of [
          ['ref', 'pan_clean_', { workDomain: 0, outMode: 1, hdr: sdrHdr() }],
          ['off', 'pan_noisy_', { workDomain: 0, outMode: 1, hdr: sdrHdr() }],
          ['temporal', 'pan_noisy_', { workDomain: 0, outMode: 1, temporal: { strength: 0.8 }, hdr: sdrHdr() }]]) {
          const fp = new Engine.FramePipeline(K, s.w, s.h, ow, oh, p, null);
          for (let i = 0; i < s.n; i++) {
            const t = await load(prefix + String(i).padStart(2, '0'), s.w, s.h);
            const o = fp.process(t); K.del(t);
            if (o.w !== ow || o.h !== oh) throw new Error('output size ' + o.w + 'x' + o.h);
            await save(`rt_${tag}_${mode}_${String(i).padStart(2, '0')}.f32`, K.read(o));
          }
        }
        res[tag] = { w: ow, h: oh };
      }
      return res;
    },

    async flash() {
      const m = await meta(), s = m.seqs.flash, out = [];
      const p = { workDomain: 0, outMode: 1, temporal: { strength: 0.8 }, hdr: sdrHdr() };
      const fp = new Engine.FramePipeline(K, s.w, s.h, s.w, s.h, p, null);
      for (let i = 0; i < s.n; i++) {
        const t = await load('flash_' + String(i).padStart(2, '0'), s.w, s.h);
        const o = fp.process(t); K.del(t);
        await save('flash_out_' + String(i).padStart(2, '0') + '.f32', K.read(o));
        out.push({ cut: fp.info.cut, d: fp.info.cutD, hd: fp.info.cutH });
      }
      return out;
    },

    async scenecut() {
      const m = await meta(), s = m.seqs.megamind, res = [];
      const sa = new Engine.SceneAnalyzer();
      const lv = []; let w = s.w, h = s.h;
      for (let i = 0; i < 4; i++) { w = Math.ceil(w / 2); h = Math.ceil(h / 2); lv.push(K.tex(w, h)); }
      for (let i = 0; i < s.n; i++) {
        const t = await load('mm_' + String(i).padStart(3, '0'), s.w, s.h);
        K.run('luma_down.frag', lv[0], { uIn: t, uFromRgb: true, uDomain: 0 });
        for (let j = 1; j < 4; j++) K.run('luma_down.frag', lv[j], { uIn: lv[j - 1], uFromRgb: false });
        K.del(t);
        const r = K.read(lv[3]); const th = new Float32Array(lv[3].w * lv[3].h);
        for (let j = 0; j < th.length; j++) th[j] = r[j * 4];
        const c = sa.isCut(th); res.push([i, c.cut ? 1 : 0, +c.d.toFixed(3), +c.hd.toFixed(3)]);
      }
      return res;
    },

    async stills() {
      const m = await meta(), e = await sr(), res = {};
      const cfgs = {
        classic: { workDomain: 0, outMode: 0, deband: { threshold: 1.5 / 255, radius: 12, iterations: 2 }, denoise: { luma: 0.35, chroma: 0.5, grainKeep: 0.25 }, sharpen: { amount: 0.5 }, hdr: sdrHdr() },
        ai: { workDomain: 0, outMode: 0, deband: { threshold: 1.5 / 255, radius: 12, iterations: 2 }, ai: { amount: 0.8, workScale: 1.0, skinProtect: 0.5, tile: 128 }, sharpen: { amount: 0.25 }, hdr: sdrHdr() },
        ai_half: { workDomain: 0, outMode: 0, deband: { threshold: 1.5 / 255, radius: 12, iterations: 2 }, ai: { amount: 0.8, workScale: 0.5, skinProtect: 0.5, tile: 128 }, sharpen: { amount: 0.25 }, hdr: sdrHdr() },
      };
      const list = m.stills.filter(s => s.name === 'still_0014').concat(m.crops);
      for (const st of list) {
        const inT = await load(st.name, st.w, st.h);
        const small = st.w * st.h <= 200 * 200;
        for (const [cn, p] of Object.entries(cfgs)) {
          if (cn === 'ai_half' && small) continue;
          const up = small ? 2 : 1;      // small inputs: x2 upscale (same-res otherwise)
          const ow = st.w * up, oh = st.h * up;
          const fp = new Engine.FramePipeline(K, st.w, st.h, ow, oh, p, e);
          const t0 = performance.now();
          const o = fp.process(inT);
          await save(`still_${st.name}_${cn}_${ow}x${oh}.f32`, K.read(o));
          res[st.name + '_' + cn] = { w: ow, h: oh, ms: Math.round(performance.now() - t0), tiles: fp.info.tiles, sigma: fp.info.sigma, expand: fp.info.expand };
        }
        K.del(inT);
      }
      return res;
    },

    // Same-resolution enhancement (the 4K->4K use case, on small crops): classic vs AI at 50% / 100% work scale
    async stills_same() {
      const m = await meta(), e = await sr(), res = {};
      const base = { workDomain: 0, outMode: 0, deband: { threshold: 1.5 / 255, radius: 12, iterations: 2 }, hdr: sdrHdr() };
      const cfgs = {
        same_classic: Object.assign({}, base, { denoise: { luma: 0.35, chroma: 0.5, grainKeep: 0.25 }, sharpen: { amount: 0.5 } }),
        same_ai50: Object.assign({}, base, { ai: { amount: 0.7, workScale: 0.5, skinProtect: 0.5, tile: 128 }, sharpen: { amount: 0.25 } }),
        same_ai100: Object.assign({}, base, { ai: { amount: 0.7, workScale: 1.0, skinProtect: 0.5, tile: 128 }, sharpen: { amount: 0.25 } }),
      };
      for (const st of m.crops) {
        const inT = await load(st.name, st.w, st.h);
        for (const [cn, p] of Object.entries(cfgs)) {
          const fp = new Engine.FramePipeline(K, st.w, st.h, st.w, st.h, p, e);
          const t0 = performance.now();
          const o = fp.process(inT);
          await save(`still_${st.name}_${cn}_${st.w}x${st.h}.f32`, K.read(o));
          res[st.name + '_' + cn] = { w: st.w, h: st.h, ms: Math.round(performance.now() - t0), tiles: fp.info.tiles };
        }
        K.del(inT);
      }
      return res;
    },

    // Same-resolution restoration against ground truth (prepare_data: degraded crops + originals).
    // The x4 model at work scale s, resampled to the output: s=1 is x4-then-downscale-x4, s=0.25 is the
    // model's native x4 from a quarter-size input. SDR out (outMode 1: identity mapping) for direct comparison.
    async same_res_eval() {
      const m = await meta(), e = await sr(), res = {};
      const deb = { threshold: 1.5 / 255, radius: 12, iterations: 2 };
      const base = { workDomain: 0, outMode: 1, deband: deb, hdr: sdrHdr() };
      const cfgs = { classic: Object.assign({}, base, { denoise: { luma: 0.35, chroma: 0.5, grainKeep: 0.25 }, sharpen: { amount: 0.5 } }) };
      for (const ws of [1, 0.5, 0.25]) {
        const tag = String(Math.round(ws * 100));
        cfgs['ai' + tag] = Object.assign({}, base, { ai: { amount: 0.8, workScale: ws, skinProtect: 0.8, tile: 128 }, sharpen: { amount: 0.25 } });
        cfgs['model' + tag] = Object.assign({}, base, { deband: null, ai: { amount: 1, workScale: ws, skinProtect: 0, tile: 128 } });
      }
      for (const d of m.degraded) {
        const g = d.deg, inT = await load(g.name, g.w, g.h);
        for (const [cn, p] of Object.entries(cfgs)) {
          const fp = new Engine.FramePipeline(K, g.w, g.h, g.w, g.h, p, e);
          const t0 = performance.now(); const o = fp.process(inT);
          await save(`sre_${g.name}_${cn}.f32`, K.read(o));
          res[g.name + '_' + cn] = { ms: Math.round(performance.now() - t0), tiles: fp.info.tiles || 0 };
        }
        K.del(inT);
      }
      return res;
    },

    // Crop preview (core/PreviewCrop.java): crop + context margin processed with whole-frame statistics
    // must equal the same region of the full-frame run. A naive crop (visible window only, its own
    // statistics) is saved for contrast. Skin protection 0.8 as in the final defaults.
    async preview_crop() {
      const m = await meta(), e = await sr(), st = m.stills.find(s => s.name === 'still_baboon'), res = {};
      const inT = await load(st.name, st.w, st.h);
      const deb = { threshold: 1.5 / 255, radius: 12, iterations: 2 };
      const base = { workDomain: 0, outMode: 0, deband: deb, hdr: sdrHdr() };
      const cfgs = {
        classic_up15: [Math.round(st.w * 1.5 / 2) * 2, Math.round(st.h * 1.5 / 2) * 2,
          Object.assign({}, base, { denoise: { luma: 0.35, chroma: 0.5, grainKeep: 0.25 }, sharpen: { amount: 0.5 } })],
        ai_same: [st.w, st.h, Object.assign({}, base, { ai: { amount: 0.8, workScale: 1, skinProtect: 0.8, tile: 128 }, sharpen: { amount: 0.25 } })],
        ai_x2_half: [st.w * 2, st.h * 2, Object.assign({}, base, { ai: { amount: 0.8, workScale: 0.5, skinProtect: 0.8, tile: 128 }, sharpen: { amount: 0.25 } })],
      };
      for (const [nm, [ow, oh, p]] of Object.entries(cfgs)) {
        const full = new Engine.FramePipeline(K, st.w, st.h, ow, oh, p, e);
        const fo = full.process(inT), fi = full.info;
        const g = Engine.previewCrop(st.w, st.h, ow, oh, !!p.ai, p.ai ? p.ai.workScale : 1, deb.radius, 0.62, 0.41, Math.round(ow * 0.3), Math.round(oh * 0.3));
        if (!g || g.fullFrame) throw new Error('crop geometry ' + JSON.stringify(g));
        const ox = Math.floor(g.x0 * ow / st.w) + g.visX, oy = Math.floor(g.y0 * oh / st.h) + g.visY;
        await save(`pc_${nm}_full.f32`, K.read(fo, ox, oy, g.visW, g.visH));
        // statistics as the app's PreviewRenderer gets them: a classic same-size run (no AI, no sharpen)
        const an = new Engine.FramePipeline(K, st.w, st.h, st.w, st.h, Object.assign({}, p, { ai: null, sharpen: null }), null);
        an.process(inT); const ai_ = an.info;
        const ct = K.tex(g.w, g.h); K.run('crop.frag', ct, { uIn: inT, uOffset: [g.x0, g.y0] });
        const cp = new Engine.FramePipeline(K, g.w, g.h, g.outW, g.outH, p, e);
        cp.region = { x: g.x0, y: g.y0, sigma: ai_.sigma, sigmaPost: ai_.sigmaPost, expand: ai_.expand };
        const t0 = performance.now(); const co = cp.process(ct); const msCrop = Math.round(performance.now() - t0);
        await save(`pc_${nm}_crop.f32`, K.read(co, g.visX, g.visY, g.visW, g.visH));
        const nt = K.tex(g.srcW, g.srcH); K.run('crop.frag', nt, { uIn: inT, uOffset: [g.srcX, g.srcY] });
        const naive = new Engine.FramePipeline(K, g.srcW, g.srcH, g.visW, g.visH, p, e);
        await save(`pc_${nm}_naive.f32`, K.read(naive.process(nt)));
        res[nm] = { geom: g, out: [ow, oh], sigma: fi.sigma, sigmaPost: fi.sigmaPost, expand: fi.expand,
          analysis_stats_equal_full_run: ai_.sigma === fi.sigma && ai_.sigmaPost === fi.sigmaPost && ai_.expand === fi.expand,
          tiles_full: fi.tiles || 0, tiles_crop: cp.info.tiles || 0, ms_crop: msCrop, processed_fraction: (g.w * g.h) / (st.w * st.h) };
        K.del(ct); K.del(nt);
      }
      K.del(inT);
      return res;
    },

    // 4K -> 4K: SDR source to HDR10 (classic chain), and HDR10 (PQ Y'CbCr) source kept in PQ
    async uhd_sdr() {
      const m = await meta(), a = m.uhd_sdr, inT = await load('uhd_sdr', a.w, a.h);
      const fp = new Engine.FramePipeline(K, a.w, a.h, a.w, a.h, { workDomain: 0, outMode: 0, deband: { threshold: 1.5 / 255, radius: 12, iterations: 2 },
        denoise: { luma: 0.3, chroma: 0.5, grainKeep: 0.25 }, sharpen: { amount: 0.4 }, hdr: sdrHdr() }, null);
      const t0 = performance.now(); const o = fp.process(inT);
      await save('uhd_sdr_out.f32', K.read(o));
      return { w: o.w, h: o.h, ms: Math.round(performance.now() - t0), sigma: fp.info.sigma };
    },

    async uhd_pq() {
      const m = await meta(), b = m.uhd_pq_yuv, yuvT = await load('uhd_pq_yuv', b.w, b.h), pqT = K.tex(b.w, b.h);
      K.run('input_tex_yuv.frag', pqT, { uTex: yuvT, uCodeScale: 1023.984375, uBitDepth: 10, uFullRange: false, uMatrix: 1, uTransfer: 1, uSrc2020: true, uWorkDomain: 1, uHlgPeak: 1000 });
      K.del(yuvT);
      const fp = new Engine.FramePipeline(K, b.w, b.h, b.w, b.h, { workDomain: 1, outMode: 0, hdr: sdrHdr({ srcPeak: 1000 }) }, null);
      const t0 = performance.now(); const o = fp.process(pqT);
      // read back in 4 strips to keep each transfer small
      const out = new Float32Array(o.w * o.h * 4), sh = o.h / 4;
      for (let i = 0; i < 4; i++) out.set(K.read(o, 0, i * sh, o.w, sh), i * sh * o.w * 4);
      await save('uhd_pq_out.f32', out);
      return { w: o.w, h: o.h, ms: Math.round(performance.now() - t0) };
    },

    // v0 engine (baseline) on the same crops, v0 default settings, for the old-vs-new comparison
    async v0_compare() {
      const m = await meta(), res = {};
      for (const st of m.crops) {
        const inT = await load(st.name, st.w, st.h), o = K.tex(st.w, st.h);
        K.run('v0_baseline.frag', o, { uTex: inT, uExpand: 0.8, uSat: 1.2, uSharp: 0.6, uContrast: 0.15 });
        await save(`still_${st.name}_v0_${st.w}x${st.h}.f32`, K.read(o)); res[st.name] = 'ok';
        K.del(inT); K.del(o);
      }
      return res;
    },

    async banding() {
      const m = await meta(), d = m.sky8, res = {};
      const inT = await load('sky8', d.w, d.h);
      for (const [nm, deb] of [['nodeband', null], ['deband', { threshold: 1.5 / 255, radius: 16, iterations: 2 }]]) {
        const fp = new Engine.FramePipeline(K, d.w, d.h, d.w, d.h, { workDomain: 0, outMode: 0, deband: deb, hdr: sdrHdr({ expand: 1, knee: 0.3 }) }, null);
        const o = fp.process(inT);
        await save('band_' + nm + '.f32', K.read(o));
      }
      return res;
    },

    async video() {
      const m = await meta(), s = m.seqs.vtest, cll = [];
      const p = { workDomain: 0, outMode: 0, deband: { threshold: 1.5 / 255, radius: 12, iterations: 2 }, denoise: { luma: 0.4, chroma: 0.6, grainKeep: 0.2 }, temporal: { strength: 0.75 }, sharpen: { amount: 0.4 }, hdr: sdrHdr() };
      const fp = new Engine.FramePipeline(K, s.w, s.h, s.w, s.h, p, null);
      let r1 = null, r2 = null;
      for (let i = 0; i < s.n; i++) {
        const t = await load('vt_' + String(i).padStart(2, '0'), s.w, s.h);
        const o = fp.process(t, 1 / s.fps); K.del(t);
        await save('vt_out_' + String(i).padStart(2, '0') + '.f32', K.read(o));
        // MaxCLL / MaxFALL measurement exactly as on device: reduce(mode 1) then reduce(mode 0) to <= 16x16
        let w = Math.ceil(o.w / 8), h = Math.ceil(o.h / 8);
        r1 = r1 || K.tex(w, h, { nearest: true });
        K.run('reduce.frag', r1, { uIn: o, uMode: 1, uDomain: 1 });
        let cur = r1;
        while (cur.w > 16 || cur.h > 16) {
          const nw = Math.ceil(cur.w / 8), nh = Math.ceil(cur.h / 8);
          const nx = K.tex(nw, nh, { nearest: true });
          K.run('reduce.frag', nx, { uIn: cur, uMode: 0, uDomain: 1 });
          if (cur !== r1) K.del(cur); cur = nx;
        }
        const px = K.read(cur); let mx = 0, sum = 0, ws = 0;
        for (let j = 0; j < cur.w * cur.h; j++) { mx = Math.max(mx, px[j * 4]); sum += px[j * 4 + 1] * px[j * 4 + 2]; ws += px[j * 4 + 2]; }
        if (cur !== r1) K.del(cur);
        cll.push({ max: mx, mean: sum / ws, cut: fp.info.cut, expand: fp.info.expand });
      }
      return { frames: s.n, cll };
    },
  };
})();
