package com.sysadmindoc.ruledeck.i18n

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * The language of this app alone — the first section of the UI page (白い熊, 2026-11-24).
 *
 * The choice is a BCP-47 tag, empty for "System default", kept under [KEY] in the UI page's own
 * preferences file, so it travels in the appearance export with the rest of the page.
 *
 * ## How it is applied
 *
 * The app's text comes from two places, and both must follow: Android string resources (upstream's
 * screens) and the `tr()` tables (the fork's own), which read [Locale.getDefault].
 *
 * - **Everywhere:** [install] runs first thing in `Application.onCreate` and sets the default locale
 *   and the application's own resources, then gives every activity the same before it draws. It is
 *   repeated after each configuration change, which resets both. So notifications, widgets and
 *   everything else built off the application context follow too — the system's own per-app locale
 *   reaches activity contexts only below Android 13, and EMUI under-reports its API level.
 * - **From Android 13 as well:** the system is told ([LocaleManager]), so its per-app language
 *   setting agrees and it recreates the activities itself. EMUI does not keep that value across a
 *   sideload update, which is why the stored tag — not the system's — is the one that counts and is
 *   asserted again at every start.
 */
object AppLanguage {

    private const val PREFS_FILE = "shiroikuma_ui_theme"
    const val KEY = "app_language"

    private val activities = ArrayList<WeakReference<Activity>>()
    private var installed = false

    /** The stored tag; empty means the system's language. */
    fun tag(context: Context): String =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    /** The system's own first language, untouched by this app's choice. */
    private fun systemLocale(): Locale =
        android.content.res.Resources.getSystem().configuration.locales[0]

    fun locale(context: Context): Locale =
        tag(context).takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it) } ?: systemLocale()

    /** Called once, first thing in `Application.onCreate`, in every process. */
    fun install(app: Application) {
        if (installed) return
        installed = true
        assert(app)
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = applyTo(app)
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = applyTo(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyTo(activity)
                synchronized(activities) {
                    activities.removeAll { it.get() == null }
                    activities += WeakReference(activity)
                }
            }
            override fun onActivityDestroyed(activity: Activity) {
                synchronized(activities) { activities.removeAll { it.get() == null || it.get() === activity } }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    /** Stores [tag] (empty for the system's language) and applies it at once, to this app only. */
    fun set(context: Context, tag: String) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        val systemRecreates = assert(app)
        if (!systemRecreates) {
            synchronized(activities) { activities.mapNotNull { it.get() } }.forEach { it.recreate() }
        }
    }

    /** Applies the stored tag. True when the system took the change and recreates the activities itself. */
    private fun assert(app: Context): Boolean {
        applyTo(app)
        if (Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            val manager = app.getSystemService(LocaleManager::class.java) ?: return false
            val wanted = LocaleList.forLanguageTags(tag(app))
            val changed = manager.applicationLocales != wanted
            if (changed) manager.applicationLocales = wanted
            changed
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun applyTo(context: Context) {
        val locale = locale(context)
        if (Locale.getDefault() != locale) Locale.setDefault(locale)
        val resources = context.resources
        if (resources.configuration.locales[0] == locale) return
        val config = Configuration(resources.configuration)
        config.setLocales(LocaleList(locale))
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    /**
     * The languages this build ships, read from the locale declaration the BUILD generates
     * (`generateLocaleConfig = true` → `xml/_generated_res_locale_config`, named by the merged
     * manifest) — so picker and declaration cannot drift. It is looked up by name: the resource
     * exists only after the build made it.
     */
    fun shippedTags(context: Context): List<String> {
        val tags = ArrayList<String>()
        runCatching {
            val id = context.resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)
            context.resources.getXml(id).use { parser ->
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                        parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let { tags += it }
                    }
                }
            }
        }
        // A debug build also declares the two pseudo-locales (en-XA, ar-XB); they are no languages.
        tags.removeAll { it.endsWith("-XA") || it.endsWith("-XB") }
        return tags.ifEmpty { listOf("en", "ja") }
    }

    /**
     * One line of the picker: the language in its own language, its country, the tag as it is shown,
     * and the country code that picks the flag. [tag] is what is stored and applied — the declared
     * one. A language declared without a country (`ja`) is SHOWN with its main country (`ja-JP`,
     * Japan's flag), taken from ICU's likely subtags; [country] stays empty only where there is no
     * sensible one, and the picker draws a globe there.
     */
    data class Entry(val tag: String, val name: String, val region: String, val shownTag: String = tag, val country: String = "")

    fun entry(tag: String): Entry {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
        val country = locale.country.ifEmpty {
            runCatching {
                android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale.forLanguageTag(tag)).country
            }.getOrDefault("").takeIf { it.length == 2 }.orEmpty()
        }
        val shown = if (locale.country.isEmpty() && country.isNotEmpty()) "${locale.language}-$country" else tag
        val region = if (country.isEmpty()) "" else Locale(locale.language, country).getDisplayCountry(locale)
        return Entry(tag, name.ifEmpty { tag }, region, shown, country)
    }

    /** The shipped languages, sorted by their own names. */
    fun entries(context: Context): List<Entry> = shippedTags(context).map(::entry).sortedBy { it.name }
}
