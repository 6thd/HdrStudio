// Minimal WebGL2 helper for the desktop harness. Mirrors gl/GlKit.java on Android:
// RGBA16F textures (row 0 = bottom image row), FBOs, MRT into texture-array layers,
// programs with uniform reflection, float readback.
(function (root) {
  class GlKit {
    constructor(gl, sources) {
      this.gl = gl; this.sources = sources; this.progs = {};
      if (!gl.getExtension('EXT_color_buffer_float')) throw new Error('EXT_color_buffer_float missing');
      gl.getExtension('OES_texture_float_linear');
      this.vao = gl.createVertexArray();
      this.fb = gl.createFramebuffer();
    }
    program(name) {
      if (this.progs[name]) return this.progs[name];
      const gl = this.gl;
      const mk = (type, src, label) => {
        const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
        if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(label + ': ' + gl.getShaderInfoLog(s));
        return s;
      };
      const p = gl.createProgram();
      gl.attachShader(p, mk(gl.VERTEX_SHADER, ShaderLib.assemble('fullscreen.vert', this.sources), 'fullscreen.vert'));
      gl.attachShader(p, mk(gl.FRAGMENT_SHADER, ShaderLib.assemble(name, this.sources), name));
      gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(name + ' link: ' + gl.getProgramInfoLog(p));
      const uniforms = {};
      const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
      for (let i = 0; i < n; i++) {
        const info = gl.getActiveUniform(p, i);
        const base = info.name.replace(/\[0\]$/, '');
        uniforms[base] = { loc: gl.getUniformLocation(p, info.name), type: info.type, size: info.size };
      }
      return (this.progs[name] = { p, uniforms, name });
    }
    tex(w, h, opts = {}) {
      const gl = this.gl, t = gl.createTexture();
      const fmt = opts.fmt || 'rgba16f';
      gl.bindTexture(gl.TEXTURE_2D, t);
      const ifmt = { rgba16f: gl.RGBA16F, rgba32f: gl.RGBA32F, rgba8: gl.RGBA8 }[fmt];
      const type = fmt === 'rgba8' ? gl.UNSIGNED_BYTE : gl.FLOAT;
      gl.texImage2D(gl.TEXTURE_2D, 0, ifmt, w, h, 0, gl.RGBA, type, opts.data || null);
      const filt = opts.nearest ? gl.NEAREST : gl.LINEAR;
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filt);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filt);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      return { t, w, h, kind: '2d' };
    }
    texArray(w, h, layers) {
      const gl = this.gl, t = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D_ARRAY, t);
      gl.texStorage3D(gl.TEXTURE_2D_ARRAY, 1, gl.RGBA16F, w, h, layers);
      for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.NEAREST], [gl.TEXTURE_MAG_FILTER, gl.NEAREST],
        [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D_ARRAY, k, v);
      return { t, w, h, layers, kind: 'array' };
    }
    del(tex) { if (tex) this.gl.deleteTexture(tex.t); }
    // targets: a texture, or [{tex, layer}] for MRT into array layers. viewport: [x,y,w,h]
    run(name, targets, uniforms = {}, viewport = null, scissor = null) {
      const gl = this.gl, pr = this.program(name);
      gl.useProgram(pr.p);
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fb);
      const list = Array.isArray(targets) ? targets : [{ tex: targets }];
      const bufs = [];
      for (let i = 0; i < 8; i++) {
        const tg = list[i];
        if (!tg) { if (i < 4) gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, null, 0); continue; }
        if (tg.tex.kind === 'array') gl.framebufferTextureLayer(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, tg.tex.t, 0, tg.layer);
        else gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, tg.tex.t, 0);
        bufs.push(gl.COLOR_ATTACHMENT0 + i);
      }
      gl.drawBuffers(bufs);
      const st = gl.checkFramebufferStatus(gl.FRAMEBUFFER);
      if (st !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo incomplete ' + st + ' for ' + name);
      const t0 = list[0].tex;
      const vp = viewport || [0, 0, t0.w, t0.h];
      gl.viewport(vp[0], vp[1], vp[2], vp[3]);
      if (scissor) { gl.enable(gl.SCISSOR_TEST); gl.scissor(...scissor); } else gl.disable(gl.SCISSOR_TEST);
      let unit = 0;
      for (const [k, v] of Object.entries(uniforms)) {
        const u = pr.uniforms[k];
        if (!u) continue;                      // optimised away
        const T = gl;
        switch (u.type) {
          case T.SAMPLER_2D: case T.SAMPLER_2D_ARRAY:
            gl.activeTexture(gl.TEXTURE0 + unit);
            gl.bindTexture(u.type === T.SAMPLER_2D ? gl.TEXTURE_2D : gl.TEXTURE_2D_ARRAY, v.t);
            gl.uniform1i(u.loc, unit++); break;
          case T.FLOAT: u.size > 1 ? gl.uniform1fv(u.loc, v) : gl.uniform1f(u.loc, v); break;
          case T.FLOAT_VEC2: gl.uniform2fv(u.loc, v); break;
          case T.FLOAT_VEC3: gl.uniform3fv(u.loc, v); break;
          case T.FLOAT_VEC4: gl.uniform4fv(u.loc, v); break;
          case T.INT: case T.BOOL: gl.uniform1i(u.loc, typeof v === 'boolean' ? (v ? 1 : 0) : v); break;
          case T.INT_VEC2: gl.uniform2iv(u.loc, v); break;
          case T.INT_VEC4: gl.uniform4iv(u.loc, v); break;
          case T.FLOAT_MAT3: gl.uniformMatrix3fv(u.loc, false, v); break;
          default: throw new Error('uniform type ' + u.type + ' for ' + k);
        }
      }
      gl.bindVertexArray(this.vao);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      gl.disable(gl.SCISSOR_TEST);
    }
    read(tex, x = 0, y = 0, w = tex.w, h = tex.h) {
      const gl = this.gl;
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.fb);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex.t, 0);
      for (let i = 1; i < 4; i++) gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, null, 0);
      gl.readBuffer(gl.COLOR_ATTACHMENT0);
      const out = new Float32Array(w * h * 4);
      gl.readPixels(x, y, w, h, gl.RGBA, gl.FLOAT, out);
      return out;
    }
  }
  root.GlKit = GlKit;
})(window);
