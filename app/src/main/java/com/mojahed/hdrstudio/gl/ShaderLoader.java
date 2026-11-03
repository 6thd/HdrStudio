package com.mojahed.hdrstudio.gl;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles shader sources from assets/shaders exactly like tools/harness/shaderlib.js, so the
 * code tested on the desktop is byte-for-byte the code compiled on the phone:
 * version line, the file's #extension lines, precision qualifiers, common.glsl, body.
 */
public final class ShaderLoader {
    private static final String PRECISION =
            "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler2DArray;\n";
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*//#include\\s+(\\w+)\\s*$");
    private final AssetManager assets;
    private final Map<String, String> cache = new HashMap<>();

    public ShaderLoader(AssetManager assets) { this.assets = assets; }

    public String raw(String name) {
        String s = cache.get(name);
        if (s != null) return s;
        try (InputStream in = assets.open("shaders/" + name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            s = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing shader asset " + name, e);
        }
        cache.put(name, s);
        return s;
    }

    public String assemble(String name) {
        String src = raw(name);
        StringBuilder ext = new StringBuilder(), body = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            if (line.trim().startsWith("#extension")) ext.append(line).append('\n');
            else body.append(line).append('\n');
        }
        String text = body.toString();
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        Matcher m = INCLUDE.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(raw(m.group(1) + ".glsl")));
        m.appendTail(sb);
        boolean vert = name.endsWith(".vert");
        return "#version 300 es\n" + ext + PRECISION + (vert ? "" : raw("common.glsl") + "\n") + sb;
    }
}
