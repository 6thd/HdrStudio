// Assembles shader sources exactly like the Android ShaderLoader (gl/ShaderLoader.java):
//   #version 300 es
//   <#extension lines of the file>
//   precision qualifiers
//   common.glsl
//   <file body with //#include name replaced>
// Works in the browser (sources object) and in Node (fs).
(function (root) {
  const HEADER_PRECISION =
    'precision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler2DArray;\n';
  function assemble(name, sources) {
    const src = sources[name];
    if (src === undefined) throw new Error('missing shader ' + name);
    const ext = [], body = [];
    for (const line of src.split('\n')) {
      if (line.trim().startsWith('#extension')) ext.push(line); else body.push(line);
    }
    let text = body.join('\n').replace(/^\s*\/\/#include\s+(\w+)\s*$/gm, (m, inc) => {
      const s = sources[inc + '.glsl'];
      if (s === undefined) throw new Error('missing include ' + inc);
      return s;
    });
    const isVert = name.endsWith('.vert');
    return '#version 300 es\n' + ext.join('\n') + (ext.length ? '\n' : '') + HEADER_PRECISION +
      (isVert ? '' : sources['common.glsl'] + '\n') + text;
  }
  root.ShaderLib = { assemble };
  if (typeof module !== 'undefined') module.exports = root.ShaderLib;
})(typeof window !== 'undefined' ? window : globalThis);
