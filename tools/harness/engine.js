// Desktop mirror of the Android frame pipeline (stages/FramePipeline.java + ai/SrEngine.java
// + stages/SceneAnalyzer.java). Same shaders, same pass order, same CPU-side decisions, so
// that the harness exercises the real processing chain (decode/encode excluded).
(function (root) {
  const P = 36, MARGIN = 2;          // SR tile padding (>= 34-layer receptive field + margin)

  class SrEngine {
    constructor(k, blob) {           // blob: Float32Array (app asset layout)
      this.k = k; this.layers = SrEngine.parse(blob); this.wtex = this.buildWeights();
    }
    static parse(a) {
      const L = []; let off = 0;
      const shapes = [[64, 3]]; for (let i = 0; i < 32; i++) shapes.push([64, 64]); shapes.push([48, 64]);
      shapes.forEach(([o, c], i) => {
        const w = a.subarray(off, off + o * c * 9); off += o * c * 9;
        const b = a.subarray(off, off + o); off += o;
        let s = null; if (i < 33) { s = a.subarray(off, off + 64); off += 64; }
        L.push({ o, c, w, b, s });
      });
      if (off !== a.length) throw new Error('weight blob size');
      return L;
    }
    buildWeights() {
      const W = 576, H = 34 * 16, data = new Float32Array(W * H * 4);
      const put = (row, col, v) => data.set(v, (row * W + col) * 4);
      this.layers.forEach((l, li) => {
        const gin = l.c === 3 ? 1 : 16;
        const wv = (o, i, t) => (i < l.c && o < l.o) ? l.w[((o * l.c + i) * 3 + Math.floor(t / 3)) * 3 + (t % 3)] : 0;
        for (let og = 0; og < 16; og++) {
          const outs = li < 33 ? [og * 4, og * 4 + 1, og * 4 + 2, og * 4 + 3] : [og, 16 + og, 32 + og, 999];
          for (let ig = 0; ig < gin; ig++) for (let t = 0; t < 9; t++) for (let j = 0; j < 4; j++)
            put(li * 16 + og, (ig * 9 + t) * 4 + j, outs.map(o => wv(o, ig * 4 + j, t)));
        }
      });
      return this.k.tex(W, H, { data, nearest: true });
    }
    // lr: E_SDR texture (model input). out: texture at output resolution (written per tile).
    run(lr, out, tileCore = 128, stats = null) {
      const k = this.k, T = tileCore, F = T + 2 * P;
      if (!this.A || this.A.w !== F) {
        [this.A, this.B, this.SR].forEach(t => t && k.del(t));
        this.A = k.texArray(F, F, 16); this.B = k.texArray(F, F, 16);
        this.SR = k.tex((T + 2 * MARGIN) * 4, (T + 2 * MARGIN) * 4, { nearest: true });
      }
      const LRw = lr.w, LRh = lr.h;
      const fx = out.w / (LRw * 4), fy = out.h / (LRh * 4);
      let tiles = 0;
      for (let y0 = 0; y0 < LRh; y0 += T) for (let x0 = 0; x0 < LRw; x0 += T) {
        const cw = Math.min(T, LRw - x0), ch = Math.min(T, LRh - y0);
        const ox = x0 - P, oy = y0 - P;
        const valid = [Math.max(0, -ox), Math.max(0, -oy), Math.min(F, LRw - ox), Math.min(F, LRh - oy)];
        k.run('sr_load.frag', [{ tex: this.A, layer: 0 }], { uLR: lr, uOrigin: [ox, oy] });
        let src = this.A, dst = this.B;
        for (let li = 0; li < 33; li++) {
          const l = this.layers[li];
          for (let ogb = 0; ogb < 16; ogb += 4) {
            const bias = [], slope = [];
            for (let q = 0; q < 4; q++) for (let c = 0; c < 4; c++) { bias.push(l.b[(ogb + q) * 4 + c]); slope.push(l.s[(ogb + q) * 4 + c]); }
            k.run('sr_conv.frag', [0, 1, 2, 3].map(q => ({ tex: dst, layer: ogb + q })), {
              uIn: src, uW: this.wtex, uGin: li === 0 ? 1 : 16, uRow0: li * 16 + ogb,
              uBias: bias, uSlope: slope, uValid: valid,
            });
          }
          [src, dst] = [dst, src];
        }
        const l = this.layers[33], bias = [];
        for (let kk = 0; kk < 16; kk++) bias.push(l.b[kk], l.b[16 + kk], l.b[32 + kk], 0);
        const rx0 = Math.max(0, x0 - MARGIN), ry0 = Math.max(0, y0 - MARGIN);
        const rx1 = Math.min(LRw, x0 + cw + MARGIN), ry1 = Math.min(LRh, y0 + ch + MARGIN);
        k.run('sr_final.frag', this.SR, {
          uIn: src, uW: this.wtex, uRow0: 33 * 16, uBias: bias, uLR: lr, uOrigin: [ox, oy],
          uFeatStart: [rx0 - ox, ry0 - oy], uHrOrigin: [0, 0],
        }, [0, 0, (rx1 - rx0) * 4, (ry1 - ry0) * 4]);
        const tx0 = Math.round(x0 * 4 * fx), tx1 = Math.round((x0 + cw) * 4 * fx);
        const ty0 = Math.round(y0 * 4 * fy), ty1 = Math.round((y0 + ch) * 4 * fy);
        k.run('sr_resample.frag', out, { uSR: this.SR, uSrOrigin: [rx0 * 4, ry0 * 4], uScale: [fx, fy], uSrValid: [(rx1 - rx0) * 4, (ry1 - ry0) * 4] },
          [0, 0, out.w, out.h], [tx0, ty0, tx1 - tx0, ty1 - ty0]);
        tiles++;
      }
      return tiles;
    }
  }

  // ---------------------------------------------------------------- scene analysis (CPU)
  class SceneAnalyzer {
    constructor() { this.prev = null; this.expand = null; }
    static stats(a) {
      let m = 0; for (const v of a) m += v; m /= a.length;
      let s = 0; for (const v of a) s += (v - m) * (v - m); s = Math.sqrt(s / a.length);
      return [m, s];
    }
    // thumb: luma of the coarsest pyramid level (Float32Array, one value per texel)
    isCut(thumb) {
      const prev = this.prev; this.prev = thumb;
      if (!prev || prev.length !== thumb.length) return { cut: true, d: 0, hd: 0, first: true };
      const [mc, sc] = SceneAnalyzer.stats(thumb), [mp, sp] = SceneAnalyzer.stats(prev);
      let d = 0;
      for (let i = 0; i < thumb.length; i++) d += Math.abs((thumb[i] - mc) / (sc + 0.02) - (prev[i] - mp) / (sp + 0.02));
      d /= thumb.length;
      const hc = new Float32Array(32), hp = new Float32Array(32);
      for (let i = 0; i < thumb.length; i++) {
        hc[Math.min(31, Math.max(0, Math.floor(thumb[i] * 32)))]++;
        hp[Math.min(31, Math.max(0, Math.floor(prev[i] * 32)))]++;
      }
      let hd = 0; for (let i = 0; i < 32; i++) hd += Math.abs(hc[i] - hp[i]); hd /= 2 * thumb.length;
      // structure changed a lot AND tonal distribution changed -> cut. A flash changes the
      // histogram but not the normalised structure; a fast pan changes structure but keeps
      // the histogram. Both together = new shot.
      const cut = d > 0.45;
      return { cut, d, hd };
    }
    // SDR scene statistics -> adapted expansion (smoothed, reset on cuts)
    adaptExpand(thumb, user, knee, dt, cut, sdrGamma) {
      const g = sdrGamma === 1 ? 2.2 : 2.4;
      let bright = 0;
      for (const v of thumb) if (Math.pow(Math.min(Math.max(v, 0), 1), g) > knee) bright++;
      const frac = bright / thumb.length;
      const sstep = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
      const target = user * (1 - 0.6 * sstep(0.10, 0.45, frac));
      if (cut || this.expand === null) this.expand = target;
      else this.expand += (target - this.expand) * (1 - Math.exp(-dt / 0.4));
      return { expand: this.expand, frac };
    }
  }

  // ---------------------------------------------------------------- pipeline
  class FramePipeline {
    constructor(k, srcW, srcH, outW, outH, params, sr) {
      Object.assign(this, { k, srcW, srcH, outW, outH, p: params, sr });
      // only what the enabled stages use (mirrors FramePipeline.java and core/MemoryBudget.java)
      const t = (w, h, o) => k.tex(w, h, o);
      this.a = t(srcW, srcH); this.b = t(srcW, srcH);
      this.lv = []; this.lvPrev = []; this.mv = [];
      let w = srcW, h = srcH;
      for (let i = 0; i < 4; i++) {
        w = Math.ceil(w / 2); h = Math.ceil(h / 2); this.lv.push(t(w, h));
        if (params.temporal) { this.lvPrev.push(t(w, h)); this.mv.push(t(w, h, { nearest: true })); }
      }
      if (params.temporal) {
        this.mvMed = t(this.lv[1].w, this.lv[1].h, { nearest: true });
        this.mvFinal = t(this.lv[0].w, this.lv[0].h, { nearest: true });
      }
      this.zero = t(1, 1, { nearest: true, data: new Float32Array(4) });   // never sample a render target
      this.hist = null; this.histNext = null;
      this.scene = new SceneAnalyzer(); this.sigma = 0.004; this.frame = 0;
      const resized = outW !== srcW || outH !== srcH;
      if (resized) { this.tmpH = t(outW, srcH); this.o1 = t(outW, outH); }
      if (resized || params.ai) this.o2 = t(outW, outH);
      if (params.ai) {
        const s = params.ai.workScale;
        this.lrW = Math.round(srcW * s); this.lrH = Math.round(srcH * s);
        if (s !== 1) { this.lrTmp = t(this.lrW, srcH); this.lr = t(this.lrW, this.lrH); }
        this.aiOut = t(outW, outH);
      }
      this.fin = t(outW, outH);
      this.info = {};
    }
    resize(src, dst, tmp) {
      const k = this.k;
      k.run('resize.frag', tmp, { uIn: src, uDir: 0, uScale: tmp.w / src.w, uLobes: 3, uAntiring: 0.8 });
      k.run('resize.frag', dst, { uIn: tmp, uDir: 1, uScale: dst.h / tmp.h, uLobes: 3, uAntiring: 0.8 });
    }
    estimateNoise(tex) {
      const k = this.k, w = Math.ceil(tex.w / 8), h = Math.ceil(tex.h / 8);
      const r = k.tex(w, h, { nearest: true });
      k.run('reduce.frag', r, { uIn: tex, uMode: 2, uDomain: this.p.workDomain });
      const px = k.read(r); k.del(r);
      const vals = [];
      for (let i = 0; i < w * h; i++) if (px[i * 4 + 2] > 0.99) vals.push(px[i * 4 + 1]);
      vals.sort((x, y) => x - y);
      const v = vals.length ? vals[Math.floor(vals.length * 0.2)] : 0;
      return v * Math.sqrt(Math.PI / 2) / 6;
    }
    process(input, dt = 1 / 30) {
      const k = this.k, p = this.p; let cur = input;
      const info = { frame: this.frame };
      const rg = this.region;           // crop of a larger frame: whole-frame statistics, crop origin
      if (p.deband) { k.run('deband.frag', this.a, { uIn: cur, uThreshold: p.deband.threshold, uRadius: p.deband.radius, uIterations: p.deband.iterations, uOrigin: rg ? [rg.x, rg.y] : [0, 0] }); cur = this.a; }
      if (rg) this.sigma = rg.sigma;
      else if (this.frame % 8 === 0 || this.lastCut) this.sigma = this.estimateNoise(cur);
      info.sigma = this.sigma;
      if (p.denoise && (p.denoise.luma > 0 || p.denoise.chroma > 0)) {
        const dst = cur === this.a ? this.b : this.a;
        k.run('denoise.frag', dst, { uIn: cur, uSigma: this.sigma, uLumaAmount: p.denoise.luma, uChromaAmount: p.denoise.chroma, uGrainKeep: p.denoise.grainKeep, uDomain: p.workDomain });
        cur = dst;
        // residual noise after spatial denoising drives the temporal/sharpen thresholds
        if (rg) this.sigmaPost = rg.sigmaPost;
        else if (this.frame % 8 === 0 || this.lastCut) this.sigmaPost = this.estimateNoise(cur);
      } else this.sigmaPost = this.sigma;
      info.sigmaPost = this.sigmaPost;
      // pyramid + motion
      if (p.temporal) [this.lv, this.lvPrev] = [this.lvPrev, this.lv];
      k.run('luma_down.frag', this.lv[0], { uIn: cur, uFromRgb: true, uDomain: p.workDomain });
      for (let i = 1; i < 4; i++) k.run('luma_down.frag', this.lv[i], { uIn: this.lv[i - 1], uFromRgb: false });
      const L4 = this.lv[3];
      const thumbRaw = k.read(L4); const thumb = new Float32Array(L4.w * L4.h);
      for (let i = 0; i < thumb.length; i++) thumb[i] = thumbRaw[i * 4];
      const sc = this.scene.isCut(thumb); info.cut = sc.cut; info.cutD = sc.d; info.cutH = sc.hd;
      this.lastCut = sc.cut;
      const temporal = p.temporal && p.temporal.strength > 0 && !sc.cut;
      if (temporal) {
        k.run('me_search.frag', this.mv[3], { uCur: this.lv[3], uPrev: this.lvPrev[3], uCoarse: this.zero, uHasCoarse: false, uNeighbours: false, uRadius: 4, uHalf: 2, uLambda: 0.002 });
        k.run('me_search.frag', this.mv[2], { uCur: this.lv[2], uPrev: this.lvPrev[2], uCoarse: this.mv[3], uHasCoarse: true, uNeighbours: true, uRadius: 1, uHalf: 2, uLambda: 0.002 });
        k.run('me_search.frag', this.mv[1], { uCur: this.lv[1], uPrev: this.lvPrev[1], uCoarse: this.mv[2], uHasCoarse: true, uNeighbours: true, uRadius: 1, uHalf: 2, uLambda: 0.004 });
        k.run('mv_median.frag', this.mvMed, { uIn: this.mv[1] });
        k.run('me_search.frag', this.mv[0], { uCur: this.lv[0], uPrev: this.lvPrev[0], uCoarse: this.mvMed, uHasCoarse: true, uNeighbours: false, uRadius: 1, uHalf: 1, uLambda: 0.004 });
        k.run('mv_median.frag', this.mvFinal, { uIn: this.mv[0] });   // removes noisy sub-pixel jitter
      }
      if (p.workDomain === 0 && p.outMode === 0) {
        if (rg) info.expand = rg.expand;
        else { const a = this.scene.adaptExpand(thumb, p.hdr.expand, p.hdr.knee, dt, sc.cut, p.hdr.sdrGamma); info.expand = a.expand; info.brightFrac = a.frac; }
      }
      const resized = this.outW !== this.srcW || this.outH !== this.srcH;
      let res;
      if (p.ai) {
        let lr = cur;
        if (p.ai.workScale !== 1) { this.resize(cur, this.lr, this.lrTmp); lr = this.lr; }
        info.tiles = this.sr.run(lr, this.aiOut, p.ai.tile || 128);
        let base = cur;
        if (resized) { this.resize(cur, this.o1, this.tmpH); base = this.o1; }
        k.run('ai_blend.frag', this.o2, { uBase: base, uAi: this.aiOut, uAmount: p.ai.amount, uSkinProtect: p.ai.skinProtect });
        res = this.o2;
        if (temporal) res = this.temporalPass(res, this.outW / this.srcW);
        this.keepHistory(res);                 // AI path: temporal + history at OUTPUT resolution
      } else {
        // classic path: temporal + history at SOURCE resolution, then resize
        res = cur;
        if (temporal) res = this.temporalPass(res, 1);
        this.keepHistory(res);
        if (resized) { this.resize(res, this.o1, this.tmpH); res = this.o1; }
      }
      if (p.sharpen && p.sharpen.amount > 0) {
        const dst = this.pick(res, [res, this.hist]);
        k.run('sharpen.frag', dst, { uIn: res, uAmount: p.sharpen.amount, uSigma: this.sigmaPost, uSkinProtect: p.sharpen.skinProtect ?? 0.5, uDomain: p.workDomain });
        res = dst;
      }
      const h = p.hdr;
      k.run('hdr_map.frag', this.fin, { uIn: res, uInDomain: p.workDomain, uOutMode: p.outMode, uSdrGamma: h.sdrGamma, uRefWhite: h.refWhite, uPeak: h.peak,
        uExpand: info.expand ?? h.expand, uKnee: h.knee, uVibrance: h.vibrance, uSkinProtect: h.skinProtect, uSrcPeak: h.srcPeak || 1000, uSdrPeakNits: h.sdrPeakNits || 250 });
      this.frame++; this.info = info;
      return this.fin;
    }
    // scratch buffer of the same size as 'like' that is not one of 'avoid'
    pick(like, avoid) {
      const t = [this.a, this.b, this.o1, this.o2].find(x => x && x.w === like.w && x.h === like.h && !avoid.includes(x));
      if (!t) throw new Error('no free ' + like.w + 'x' + like.h + ' buffer');
      return t;
    }
    temporalPass(cur, mvToTarget) {
      if (!this.hist) return cur;
      if (this.hist.w !== cur.w || this.hist.h !== cur.h) throw new Error('temporal history size mismatch');
      const dst = this.pick(cur, [cur, this.hist]);
      this.k.run('temporal.frag', dst, { uCur: cur, uPrev: this.hist, uMv: this.mvFinal, uMvScale: 2 * mvToTarget, uSigma: this.sigmaPost, uStrength: this.p.temporal.strength, uClampK: 1.5, uDomain: this.p.workDomain });
      return dst;
    }
    keepHistory(res) {
      if (!this.p.temporal) return;
      if (!this.histTex) { this.histTex = [this.k.tex(res.w, res.h), this.k.tex(res.w, res.h)]; this.hi = 0; }
      const dst = this.histTex[this.hi]; this.hi ^= 1;
      this.k.run('copy.frag', dst, { uIn: res });
      this.hist = dst;
    }
  }
  // ---------------------------------------------------------------- crop preview geometry (core/PreviewCrop.java)
  const gcd = (a, b) => { while (b) [a, b] = [b, a % b]; return a; };
  const lcm = (a, b) => a / gcd(a, b) * b;
  const fdiv = (v, q) => Math.floor(v / q) * q, cdiv = (v, q) => Math.ceil(v / q) * q;
  function cropMargin(src, out, ai, ws, debandR) {
    const down = Math.max(1, src / out), perOut = src / out;
    let r = Math.ceil(debandR) + 1 + 2 + 4 * down + 2 * perOut;
    if (ai) r += 38 / ws + 4 * Math.max(1, 1 / ws);
    return Math.ceil(r) + 4;
  }
  function cropStep(src, out, ai, ws) {
    let q = src / gcd(src, out);
    if (ai && ws !== 1) { const lr = Math.max(8, Math.round(src * ws)); q = lcm(q, src / gcd(src, lr)); }
    return q;
  }
  function previewCrop(srcW, srcH, outW, outH, ai, ws, debandR, fx, fy, visOutW, visOutH) {
    const qx = cropStep(srcW, outW, ai, ws), qy = cropStep(srcH, outH, ai, ws);
    const vw = Math.min(srcW, cdiv(Math.ceil(visOutW * srcW / outW), qx)), vh = Math.min(srcH, cdiv(Math.ceil(visOutH * srcH / outH), qy));
    if (vw >= Math.floor(srcW * 9 / 10) && vh >= Math.floor(srcH * 9 / 10))
      return { x0: 0, y0: 0, w: srcW, h: srcH, outW, outH, visX: 0, visY: 0, visW: outW, visH: outH, srcX: 0, srcY: 0, srcW, srcH, margin: 0, fullFrame: true };
    if (qx * 4 > vw || qy * 4 > vh) return null;
    const m = cropMargin(srcW, outW, ai, ws, debandR), my = cropMargin(srcH, outH, ai, ws, debandR);
    const place = (f, size, v, q) => Math.max(0, Math.min(fdiv(size - v, q), fdiv(Math.round(Math.max(0, Math.min(1, f)) * size - v / 2), q)));
    const sc = (x, src, out) => Math.floor(x * out / src);
    const vx = place(fx, srcW, vw, qx), vy = place(fy, srcH, vh, qy);
    const x0 = Math.max(0, fdiv(vx - m, qx)), y0 = Math.max(0, fdiv(vy - my, qy));
    const x1 = Math.min(srcW, cdiv(vx + vw + m, qx)), y1 = Math.min(srcH, cdiv(vy + vh + my, qy));
    const ox0 = sc(x0, srcW, outW), oy0 = sc(y0, srcH, outH), ovx = sc(vx, srcW, outW), ovy = sc(vy, srcH, outH);
    return { x0, y0, w: x1 - x0, h: y1 - y0, outW: sc(x1, srcW, outW) - ox0, outH: sc(y1, srcH, outH) - oy0,
      visX: ovx - ox0, visY: ovy - oy0, visW: sc(vx + vw, srcW, outW) - ovx, visH: sc(vy + vh, srcH, outH) - ovy,
      srcX: vx, srcY: vy, srcW: vw, srcH: vh, margin: Math.max(m, my), fullFrame: false };
  }

  root.Engine = { SrEngine, SceneAnalyzer, FramePipeline, previewCrop };
})(window);
