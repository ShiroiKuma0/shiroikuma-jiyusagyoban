package com.opentasker.core.input;

/** Privileged grabber → app. One call per gesture. pressType: 0=short, 1=long, 2=double. oneway. */
oneway interface IKeyGrabberCallback {
    void onKey(int evCode, int pressType);
}
