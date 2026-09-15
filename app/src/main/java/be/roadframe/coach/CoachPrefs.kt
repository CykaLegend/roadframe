package be.roadframe.coach

import android.content.Context
import androidx.core.content.edit

/** Small persisted settings; nothing here leaves the phone. */
class CoachPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("roadframe", Context.MODE_PRIVATE)

    var structure: CoachStructure
        get() = enumOr(prefs.getString(KEY_STRUCTURE, null), CoachStructure.ROADFRAME)
        set(value) = prefs.edit { putString(KEY_STRUCTURE, value.name) }

    var category: ShotCategory
        get() = enumOr(prefs.getString(KEY_CATEGORY, null), ShotCategory.FRONT_THREE_QUARTER)
        set(value) = prefs.edit { putString(KEY_CATEGORY, value.name) }

    var subject: SubjectPreference
        get() = enumOr(prefs.getString(KEY_SUBJECT, null), SubjectPreference.AUTO)
        set(value) = prefs.edit { putString(KEY_SUBJECT, value.name) }

    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_GPU, false)
        set(value) = prefs.edit { putBoolean(KEY_GPU, value) }

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_STATS, true)
        set(value) = prefs.edit { putBoolean(KEY_STATS, value) }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private companion object {
        const val KEY_STRUCTURE = "structure"
        const val KEY_CATEGORY = "category"
        const val KEY_SUBJECT = "subject"
        const val KEY_GPU = "gpu"
        const val KEY_STATS = "stats"
    }
}
