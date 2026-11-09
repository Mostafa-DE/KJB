package app.lyrify.tags

import kotlinx.serialization.Serializable

/** One field an edit changed, by TagLib property name: its values before and after (empty: no field). */
@Serializable
data class FieldChange(val key: String, val old: List<String>, val new: List<String>)
