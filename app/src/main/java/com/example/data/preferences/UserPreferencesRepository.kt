package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AppPreferences
import com.example.domain.models.ThemeMode
import com.example.domain.repositories.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_ai_preferences")

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : PreferencesRepository {

    constructor(context: Context) : this(context.applicationContext.dataStore)

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val FALLBACK_MODEL_ID = stringPreferencesKey("fallback_model_id")
        val ALLOW_MODEL_FALLBACK = booleanPreferencesKey("allow_model_fallback")
        val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        val REDUCED_ANIMATIONS = booleanPreferencesKey("reduced_animations")
        val STREAM_RESPONSES = booleanPreferencesKey("stream_responses")
        val RENDER_MARKDOWN = booleanPreferencesKey("render_markdown")
    }

    override val preferencesFlow: Flow<AppPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val themeName = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
            val themeMode = ThemeMode.entries.firstOrNull { it.name == themeName } ?: ThemeMode.DARK
            val storedDefault = prefs[Keys.DEFAULT_MODEL_ID]?.takeIf {
                AiModelCatalog.isSupportedApinexModel(it)
            } ?: AiModelCatalog.DEFAULT_MODEL_ID
            val storedFallback = prefs[Keys.FALLBACK_MODEL_ID]?.takeIf {
                AiModelCatalog.isSupportedApinexModel(it)
            }
            AppPreferences(
                themeMode = themeMode,
                defaultModelId = storedDefault,
                fallbackModelId = storedFallback,
                allowModelFallback = prefs[Keys.ALLOW_MODEL_FALLBACK] ?: false,
                enterToSend = prefs[Keys.ENTER_TO_SEND] ?: false,
                reducedAnimations = prefs[Keys.REDUCED_ANIMATIONS] ?: false,
                streamResponses = prefs[Keys.STREAM_RESPONSES] ?: true,
                renderMarkdown = prefs[Keys.RENDER_MARKDOWN] ?: true
            )
        }

    override suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = themeMode.name }
    }

    override suspend fun setDefaultModelId(modelId: String) {
        dataStore.edit { it[Keys.DEFAULT_MODEL_ID] = modelId }
    }

    override suspend fun setFallbackModel(modelId: String?, allowFallback: Boolean) {
        dataStore.edit { prefs ->
            if (modelId.isNullOrBlank()) {
                prefs.remove(Keys.FALLBACK_MODEL_ID)
                prefs[Keys.ALLOW_MODEL_FALLBACK] = false
            } else {
                prefs[Keys.FALLBACK_MODEL_ID] = modelId
                prefs[Keys.ALLOW_MODEL_FALLBACK] = allowFallback
            }
        }
    }

    override suspend fun setEnterToSend(enabled: Boolean) {
        dataStore.edit { it[Keys.ENTER_TO_SEND] = enabled }
    }

    override suspend fun setReducedAnimations(enabled: Boolean) {
        dataStore.edit { it[Keys.REDUCED_ANIMATIONS] = enabled }
    }

    override suspend fun setStreamResponses(enabled: Boolean) {
        dataStore.edit { it[Keys.STREAM_RESPONSES] = enabled }
    }

    override suspend fun setRenderMarkdown(enabled: Boolean) {
        dataStore.edit { it[Keys.RENDER_MARKDOWN] = enabled }
    }
}
