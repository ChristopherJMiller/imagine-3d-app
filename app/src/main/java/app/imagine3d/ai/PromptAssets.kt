package app.imagine3d.ai

import android.content.Context
import app.imagine3d.data.Measurement
import org.json.JSONObject

/**
 * The prompt files shipped in assets/prompts. tools/eval/run_eval.py reads the same files and
 * builds messages the same way, so host eval results carry over to the phone.
 */
class PromptAssets(context: Context) {
    private val assets = context.assets
    private fun read(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    val system: String = read("prompts/system.md")
    private val userTemplate = read("prompts/user.md")
    private val repairTemplate = read("prompts/repair.md")
    private val refineTemplate = read("prompts/refine.md")
    val fatalPatterns: List<Regex> = JSONObject(read("prompts/diagnostics.json")).getJSONArray("fatal").let { arr ->
        (0 until arr.length()).map { Regex(arr.getString(it)) }
    }

    fun userMessage(request: String, measurements: List<Measurement>, notes: String?): String {
        val meas = if (measurements.isEmpty()) "" else
            "Measurements:\n" + measurements.joinToString("") { "- ${it.label.ifBlank { "measurement" }}: ${formatMm(it.mm)} mm\n" }
        val notesLine = if (notes.isNullOrBlank()) "" else "Notes: ${notes.trim()}\n"
        return userTemplate.replace("{{request}}", request.trim())
            .replace("{{measurements}}", meas)
            .replace("{{notes}}", notesLine)
            .trim() + "\n"
    }

    fun repairMessage(problems: List<String>): String = repairTemplate.replace("{{errors}}", problems.take(6).joinToString("\n"))

    fun refineMessage(change: String): String = refineTemplate.replace("{{change}}", change.trim())

    companion object {
        /** Same as Python's "{:g}" for the values we produce: 19.0 -> "19", 1.5 -> "1.5". */
        fun formatMm(v: Double): String {
            val r = Math.round(v * 10) / 10.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }

        const val VISION_SYSTEM =
            "You look at photos of rooms and furniture for someone who wants to 3D print a small part (hook, holder, clip, organizer). " +
                "Describe only what matters for designing that part: the surfaces and edges it would attach to or rest on, " +
                "the objects it must hold, and anything drawn or circled on the photo. Two or three short sentences. No measurements unless written on the photo."

        fun visionUser(request: String) = "The person wants: ${request.trim()}\nWhat in this photo matters for designing it?"
    }
}
