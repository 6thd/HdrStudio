package com.mojahed.hdrstudio.gl;

/** GL texture handle. Convention: row 0 = bottom image row (GL convention). */
public final class Tex {
    public final int id, w, h, layers;
    public final boolean array;

    Tex(int id, int w, int h, int layers, boolean array) {
        this.id = id; this.w = w; this.h = h; this.layers = layers; this.array = array;
    }

    /** Render target descriptor: a 2D texture, or one layer of an array texture. */
    public static final class Target {
        public final Tex tex; public final int layer;
        public Target(Tex tex, int layer) { this.tex = tex; this.layer = layer; }
    }
    public Target layer(int l) { return new Target(this, l); }
}
