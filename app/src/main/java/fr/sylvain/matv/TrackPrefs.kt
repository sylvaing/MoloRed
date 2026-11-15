package fr.sylvain.matv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * Audio / subtitle tracks of SFR live streams, with remembered preferences.
 *
 * SFR manifests use: audio `fra`/`fre` (main), `qaa` (original version), `qad`
 * (audio description), other languages (Arte: `ger`); subtitles TTML (`stpp`) per
 * language, the one with DASH Accessibility value 2 being for the deaf and hard of hearing.
 */
@OptIn(UnstableApi::class)
object TrackPrefs {
    data class Option(val key: String, val label: String, val group: Tracks.Group?)

    private const val PREFS = "tracks"
    const val AUDIO_DEFAULT = "default"
    const val TEXT_OFF = "off"

    private val ISO3 = mapOf("fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho")

    /** ISO 639-2 code; Media3 turns "fra"/"fre" into "fr", so map 2-letter codes back (keeps qaa/qad). */
    private fun lang(f: Format): String {
        val l = (f.language ?: "und").lowercase(Locale.ROOT).substringBefore('-')
        val three = if (l.length == 2) runCatching { Locale(l).isO3Language }.getOrDefault(l) else l
        return ISO3[three] ?: three
    }

    private fun langName(code: String): String = when (code) {
        "fra" -> "Français"
        "deu" -> "Allemand"
        "eng" -> "Anglais"
        "ita" -> "Italien"
        "spa" -> "Espagnol"
        "und" -> "Inconnue"
        else -> Locale(code).getDisplayLanguage(Locale.FRENCH).replaceFirstChar { it.uppercase() }.ifEmpty { code }
    }

    private fun isHearingImpaired(f: Format) =
        f.roleFlags and (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_ENHANCED_DIALOG_INTELLIGIBILITY) != 0

    fun audioKey(f: Format): String {
        val l = lang(f)
        return when {
            l == "qaa" -> "vo"
            l == "qad" || f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 -> "ad"
            else -> "lang:$l"
        }
    }

    fun textKey(f: Format) = lang(f) + if (isHearingImpaired(f)) "-sme" else ""

    private fun audioLabel(key: String) = when {
        key == "vo" -> "Version originale (VO)"
        key == "ad" -> "Audiodescription"
        key.startsWith("lang:") -> langName(key.removePrefix("lang:"))
        else -> key
    }

    private fun textLabel(key: String) =
        if (key.endsWith("-sme")) "${langName(key.removeSuffix("-sme"))} (sourds et malentendants)"
        else langName(key)

    fun audioOptions(tracks: Tracks): List<Option> {
        val opts = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
            .map { g -> val k = audioKey(g.getTrackFormat(0)); Option(k, audioLabel(k), g) }
            .distinctBy { it.key }
        // Main language first, then VO, other languages, audio description.
        return opts.sortedBy { when (it.key) { "lang:fra" -> 0; "vo" -> 1; "ad" -> 3; else -> 2 } }
    }

    fun textOptions(tracks: Tracks): List<Option> =
        listOf(Option(TEXT_OFF, "Désactivés", null)) +
            tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
                .map { g -> val k = textKey(g.getTrackFormat(0)); Option(k, textLabel(k), g) }
                .distinctBy { it.key }
                // French first (plain, then for the deaf and hard of hearing), then other languages.
                .sortedWith(compareBy({ if (it.key.startsWith("fra")) 0 else 1 }, { it.key }))

    fun selectedAudioKey(tracks: Tracks): String? =
        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { audioKey(it.getTrackFormat(0)) }

    fun selectedTextKey(tracks: Tracks): String =
        tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { textKey(it.getTrackFormat(0)) } ?: TEXT_OFF

    // ---- preferences (global, applied to every channel that offers them) ----

    fun audioPref(ctx: Context): String = ctx.getSharedPreferences(PREFS, 0).getString("audio", AUDIO_DEFAULT)!!
    fun textPref(ctx: Context): String = ctx.getSharedPreferences(PREFS, 0).getString("text", TEXT_OFF)!!
    fun setAudioPref(ctx: Context, key: String) = ctx.getSharedPreferences(PREFS, 0).edit().putString("audio", key).apply()
    fun setTextPref(ctx: Context, key: String) = ctx.getSharedPreferences(PREFS, 0).edit().putString("text", key).apply()

    /** Applies the remembered choices to [player]; falls back to the main audio / no subtitles. */
    fun apply(ctx: Context, player: Player) {
        val tracks = player.currentTracks
        val b = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)

        val audio = audioOptions(tracks)
        val wanted = audio.firstOrNull { it.key == audioPref(ctx) }
            ?: audio.firstOrNull { it.key == "lang:fra" }
            ?: audio.firstOrNull { it.group?.getTrackFormat(0)?.roleFlags?.and(C.ROLE_FLAG_MAIN) != 0 }
        wanted?.group?.let { g -> b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, (0 until g.length).toList())) }

        val text = textOptions(tracks).firstOrNull { it.key == textPref(ctx) && it.group != null }
        if (text?.group == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setOverrideForType(TrackSelectionOverride(text.group.mediaTrackGroup, (0 until text.group.length).toList()))
        }
        player.trackSelectionParameters = b.build()
    }
}
