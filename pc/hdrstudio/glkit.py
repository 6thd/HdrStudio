"""Desktop twin of the phone app's GlKit: RGBA16F textures, full-screen passes, float read-back.

Shaders are the phone app's files, assembled the same way (common.glsl prepended, //#include
resolved); only the version line differs (desktop GL 3.3 core instead of GLSL ES 3.00).
A GlKit belongs to the thread that created it.
"""
from __future__ import annotations

import re
from pathlib import Path

import moderngl
import numpy as np

SHADER_DIR = Path(__file__).parent / "shaders"
_INCLUDE = re.compile(r"^[ \t]*//#include[ \t]+(\w+)[ \t]*$", re.M)


class Tex:
    __slots__ = ("t", "w", "h", "fbo")

    def __init__(self, t, w, h):
        self.t, self.w, self.h, self.fbo = t, w, h, None


class GlKit:
    def __init__(self):
        self.ctx = moderngl.create_standalone_context(require=330)
        self.ctx.disable(moderngl.BLEND | moderngl.DEPTH_TEST | moderngl.CULL_FACE)
        self._progs: dict[str, tuple] = {}
        self._raw: dict[str, str] = {}
        self.tex_bytes = 0
        self.info = {k: self.ctx.info.get(k, "") for k in ("GL_RENDERER", "GL_VERSION", "GL_VENDOR")}

    # ------------------------------------------------------------------ shaders
    def raw(self, name):
        if name not in self._raw:
            self._raw[name] = (SHADER_DIR / name).read_text("utf-8")
        return self._raw[name]

    def assemble(self, name):
        body = "\n".join(l for l in self.raw(name).split("\n") if not l.strip().startswith("#extension"))
        body = _INCLUDE.sub(lambda m: self.raw(m.group(1) + ".glsl"), body)
        return "#version 330 core\n" + ("" if name.endswith(".vert") else self.raw("common.glsl") + "\n") + body

    def program(self, frag):
        if frag not in self._progs:
            try:
                p = self.ctx.program(vertex_shader=self.assemble("fullscreen.vert"), fragment_shader=self.assemble(frag))
            except Exception as e:
                raise RuntimeError(f"{frag}: {e}") from None
            self._progs[frag] = (p, self.ctx.vertex_array(p, []))
        return self._progs[frag]

    # ------------------------------------------------------------------ textures
    def tex(self, w, h, nearest=False, data=None, components=4, dtype="f2"):
        t = self.ctx.texture((w, h), components, data=data, dtype=dtype, alignment=1)
        f = moderngl.NEAREST if nearest or dtype in ("u1", "u2") else moderngl.LINEAR
        t.filter = (f, f)
        t.repeat_x = t.repeat_y = False
        self.tex_bytes += w * h * components * int(dtype[-1])
        return Tex(t, w, h)

    def delete(self, tex):
        if tex is None:
            return
        if tex.fbo is not None:
            tex.fbo.release()
        self.tex_bytes -= tex.w * tex.h * tex.t.components * int(tex.t.dtype[-1])
        tex.t.release()

    def _fbo(self, tex):
        if tex.fbo is None:
            tex.fbo = self.ctx.framebuffer([tex.t])
        return tex.fbo

    # ------------------------------------------------------------------ drawing
    def run(self, frag, target: Tex, uniforms: dict, scissor=None):
        prog, vao = self.program(frag)
        unit = 0
        for name, v in uniforms.items():
            u = prog.get(name, None)
            if u is None:           # optimised out by the compiler
                continue
            if isinstance(v, Tex):
                v.t.use(unit)
                u.value = unit
                unit += 1
            elif isinstance(v, np.ndarray):
                u.write(v.tobytes())
            elif isinstance(v, (list, tuple)):
                u.value = tuple(v)
            else:
                u.value = v
        fbo = self._fbo(target)
        fbo.use()
        fbo.viewport = (0, 0, target.w, target.h)
        fbo.scissor = scissor
        vao.render(moderngl.TRIANGLES, vertices=3)
        fbo.scissor = None

    def read(self, tex: Tex, components=4, dtype="f4") -> np.ndarray:
        raw = self._fbo(tex).read(components=components, dtype=dtype, alignment=1)
        a = np.frombuffer(raw, dtype)
        return a.reshape(tex.h, tex.w, components) if components > 1 else a.reshape(tex.h, tex.w)

    def finish(self):
        self.ctx.finish()

    def release(self):
        self.ctx.release()
