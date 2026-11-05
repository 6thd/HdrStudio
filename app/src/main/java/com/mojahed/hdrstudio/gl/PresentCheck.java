package com.mojahed.hdrstudio.gl;

import android.opengl.GLES20;
import android.opengl.GLES30;

import java.util.Locale;

/**
 * GL state right after the final draw into the encoder surface (default framebuffer), before the swap:
 * framebuffer binding, viewport, draw buffer, scissor, blend, colour mask and pending GL errors.
 * Only reads state; it changes nothing.
 */
public final class PresentCheck {
    private PresentCheck() {}

    /** @return null if the draw went to the default framebuffer with the full w x h viewport, else what differs */
    public static String gl(int w, int h, StringBuilder facts) {
        int[] fb = new int[1], vp = new int[4], db = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fb, 0);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, vp, 0);
        GLES20.glGetIntegerv(GLES30.GL_DRAW_BUFFER0, db, 0);
        boolean scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST), blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        boolean discard = GLES20.glIsEnabled(GLES30.GL_RASTERIZER_DISCARD);
        boolean[] mask = new boolean[4];
        GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, mask, 0);
        StringBuilder errs = new StringBuilder();
        for (int i = 0, e; i < 8 && (e = GLES20.glGetError()) != GLES20.GL_NO_ERROR; i++) errs.append(" 0x").append(Integer.toHexString(e));
        facts.append(String.format(Locale.US, "GL: framebuffer %d · viewport %d,%d,%d,%d · draw buffer 0x%x · scissor %s · blend %s · rasterizer-discard %s · colour mask %s%s%s%s · أخطاء GL%s",
                fb[0], vp[0], vp[1], vp[2], vp[3], db[0], scissor, blend, discard, mask[0] ? "R" : "-", mask[1] ? "G" : "-", mask[2] ? "B" : "-", mask[3] ? "A" : "-",
                errs.length() == 0 ? " لا شيء" : errs.toString()));
        StringBuilder bad = new StringBuilder();
        if (fb[0] != 0) bad.append("الرسم النهائي ليس على framebuffer الافتراضي (").append(fb[0]).append("); ");
        if (vp[0] != 0 || vp[1] != 0 || vp[2] != w || vp[3] != h) bad.append("viewport لا يغطي سطح المرمّز; ");
        if (db[0] != GLES20.GL_BACK) bad.append("draw buffer ليس GL_BACK; ");
        if (scissor) bad.append("scissor مفعل; ");
        if (discard) bad.append("rasterizer-discard مفعل; ");
        if (!mask[0] || !mask[1] || !mask[2]) bad.append("colour mask يحجب قنوات; ");
        if (errs.length() > 0) bad.append("أخطاء GL معلقة").append(errs).append("; ");
        return bad.length() == 0 ? null : bad.toString();
    }
}
