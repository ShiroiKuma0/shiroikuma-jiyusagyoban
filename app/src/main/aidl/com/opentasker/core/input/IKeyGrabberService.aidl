package com.opentasker.core.input;

import com.opentasker.core.input.IKeyGrabberCallback;

/**
 * The key grabber, running in a Shizuku-spawned privileged process (uid 2000). The app binds it via
 * Shizuku.bindUserService — no binary is copied to /data/local/tmp; the native grab code (libevgrab.so)
 * is loaded straight from the APK inside that process.
 */
interface IKeyGrabberService {
    /** Destroy id reserved by the Shizuku server (called on unbind-with-remove). */
    void destroy() = 16777114;

    /**
     * Grab [evCodes] and report presses through [cb]. Codes in [doubleCodes] get double-tap detection and
     * codes in [tripleCodes] triple-tap (their single short is held for [doublePressMs] before firing).
     * Returns the number of input devices grabbed (>0 = running); <=0 = grabbing impossible (caller falls back).
     */
    int start(long longPressMs, long doublePressMs, in int[] evCodes, in int[] doubleCodes, in int[] tripleCodes, IKeyGrabberCallback cb) = 1;

    /** Release all grabs and stop the loop. */
    void stop() = 2;

    /**
     * Tell the grabber the screen state. Screen on → a SINGLE tap is consumed (so the app can repurpose it,
     * e.g. the volume panel); screen off → the single tap is re-injected so system volume works unchanged.
     * Long/double/triple are always consumed regardless.
     */
    void setScreenOn(boolean on) = 3;
}
