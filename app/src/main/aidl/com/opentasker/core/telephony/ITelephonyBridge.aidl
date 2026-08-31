package com.opentasker.core.telephony;

/**
 * Telephony operations that need `MODIFY_PHONE_STATE`, run in a Shizuku-spawned privileged process
 * (uid 2000 / shell). Shell holds MODIFY_PHONE_STATE granted=true on this device, which is what makes
 * switching the data SIM possible without root.
 *
 * Bound with Shizuku.bindUserService, exactly like IKeyGrabberService — no binary is copied to
 * /data/local/tmp. We go through ISub rather than `service call isub <n>` because the transaction
 * numbers are build-specific and calling the wrong one on the telephony service is not a safe way to
 * find out.
 */
interface ITelephonyBridge {
    /** Destroy id reserved by the Shizuku server (called on unbind-with-remove). */
    void destroy() = 16777114;

    /** Set the SIM that carries mobile data. Returns "" on success, else a human-readable reason. */
    String setDefaultDataSubId(int subId) = 1;

    /** The subscription id currently carrying data, or -1 when it cannot be read. */
    int getDefaultDataSubId() = 2;
}
