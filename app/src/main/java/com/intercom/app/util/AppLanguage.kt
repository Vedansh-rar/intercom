package com.intercom.app.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language the app shows. [System] follows the phone (and Android 13's per-app language
 * picker); the others force one regardless of the phone, which matters on a phone sold with
 * an English-only setup menu to a rider who reads Hindi.
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Hindi("hi");

    companion object {
        /** Unknown or missing names fall back to [System], never to a crash. */
        fun parse(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: System

        /** The languages the app has translations for. */
        val supportedTags: List<String> get() = entries.mapNotNull { it.tag }
    }
}

/** Gives any [Context] the resources of the rider's chosen language. */
object Localized {
    fun context(base: Context, language: AppLanguage): Context {
        val tag = language.tag ?: return base
        val locale = Locale.forLanguageTag(tag)
        if (base.resources.configuration.locales.get(0) == locale) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Read straight from the preferences file, so it works before the Application's [Prefs] is used. */
    fun languageOf(base: Context): AppLanguage =
        AppLanguage.parse(Prefs.rawLanguage(base))
}
