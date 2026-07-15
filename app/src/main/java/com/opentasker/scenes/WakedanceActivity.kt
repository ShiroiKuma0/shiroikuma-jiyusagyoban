package com.opentasker.scenes

/**
 * The over-lockscreen wakedance Activity — a [SceneActivity] with an OPAQUE black theme.
 *
 * The base [SceneActivity] uses a translucent theme (so modal scenes can dim the app behind them), which
 * let the live wallpaper flash through during the launch/finish window transitions over the keyguard. This
 * subclass exists only to carry an opaque-black, fullscreen theme (declared in the manifest); all the
 * show-when-locked + wakelock + self-sleep logic is inherited. Launched by `scene.show showWhenLocked`.
 */
class WakedanceActivity : SceneActivity()
