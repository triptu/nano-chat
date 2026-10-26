package ai.tushar.nanochat.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ai.tushar.nanochat.model.FakeScenario
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Sampling knobs sent with each request. */
data class GenerationParams(val temperature: Float, val topK: Int, val maxOutputTokens: Int) {
    companion object {
        val Default = GenerationParams(temperature = 0.7f, topK = 40, maxOutputTokens = 512)
        val TemperatureRange = 0f..1f
        val TopKRange = 1..64
        val MaxOutputRange = 64..1024
    }
}

/** Which model backend runs. Only the dev tools can change it; with dev tools off it is always [Auto]. */
enum class BackendChoice(val label: String) { Auto("Auto"), Real("Real"), Fake("Fake") }

private val Context.store by preferencesDataStore("settings")

class Settings(context: Context) {
    private val store = context.store

    private object Keys {
        val displayName = stringPreferencesKey("display_name")
        val devTools = booleanPreferencesKey("dev_tools")
        val temperature = floatPreferencesKey("default_temperature")
        val topK = intPreferencesKey("default_top_k")
        val maxOutput = intPreferencesKey("default_max_output_tokens")
        val backend = stringPreferencesKey("backend_choice")
        val fakeScenario = stringPreferencesKey("fake_scenario")
    }

    val displayName: Flow<String> = store.data.map { it[Keys.displayName].orEmpty() }.distinctUntilChanged()

    /**
     * The one switch for every developer surface. Everything under `devtools/` is shown only when this is true.
     * On by default because the app is for experimenting with the model.
     */
    val devToolsEnabled: Flow<Boolean> = store.data.map { it[Keys.devTools] ?: true }.distinctUntilChanged()

    val defaultParams: Flow<GenerationParams> = store.data.map { it.params() }.distinctUntilChanged()

    val backendChoice: Flow<BackendChoice> = combine(devToolsEnabled, store.data) { dev, prefs ->
        if (!dev) BackendChoice.Auto else BackendChoice.entries.firstOrNull { it.name == prefs[Keys.backend] } ?: BackendChoice.Auto
    }.distinctUntilChanged()

    val fakeScenario: Flow<FakeScenario> = store.data
        .map { prefs -> FakeScenario.entries.firstOrNull { it.name == prefs[Keys.fakeScenario] } ?: FakeScenario.Normal }
        .distinctUntilChanged()

    suspend fun setBackendChoice(c: BackendChoice) = store.edit { it[Keys.backend] = c.name }
    suspend fun setFakeScenario(s: FakeScenario) = store.edit { it[Keys.fakeScenario] = s.name }

    suspend fun defaultParamsNow(): GenerationParams = store.data.first().params()

    suspend fun setDisplayName(name: String) = store.edit { it[Keys.displayName] = name }
    suspend fun setDevToolsEnabled(on: Boolean) = store.edit { it[Keys.devTools] = on }
    suspend fun setDefaultParams(p: GenerationParams) = store.edit {
        it[Keys.temperature] = p.temperature
        it[Keys.topK] = p.topK
        it[Keys.maxOutput] = p.maxOutputTokens
    }

    private fun Preferences.params() = GenerationParams(
        temperature = this[Keys.temperature] ?: GenerationParams.Default.temperature,
        topK = this[Keys.topK] ?: GenerationParams.Default.topK,
        maxOutputTokens = this[Keys.maxOutput] ?: GenerationParams.Default.maxOutputTokens,
    )
}
