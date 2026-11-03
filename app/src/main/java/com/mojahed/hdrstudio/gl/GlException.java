package com.mojahed.hdrstudio.gl;

import android.opengl.GLES20;

public final class GlException extends RuntimeException {
    public GlException(String msg) { super(msg); }

    public static void check(String where) {
        int e = GLES20.glGetError();
        if (e != GLES20.GL_NO_ERROR) throw new GlException("GL error 0x" + Integer.toHexString(e) + " at " + where);
    }
}
