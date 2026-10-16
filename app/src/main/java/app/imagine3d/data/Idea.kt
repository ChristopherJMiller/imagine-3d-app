package app.imagine3d.data

import kotlinx.serialization.Serializable

/** A point on the idea's photo, normalised to 0..1 in both directions. */
@Serializable
data class PhotoPoint(val x: Float, val y: Float)

@Serializable
data class Measurement(
    val id: String,
    val label: String,
    val mm: Double,
    /** "ar" when measured with ARCore, "manual" when typed. */
    val source: String = "manual",
    /** Where the two ends were on the photo (AR measurements only). */
    val a: PhotoPoint? = null,
    val b: PhotoPoint? = null,
    /** What ARCore hit at each end, e.g. "vertical plane", "horizontal plane", "depth", "point". */
    val surfaceA: String? = null,
    val surfaceB: String? = null,
)

@Serializable
data class Stroke(val color: Long, val width: Float, val points: List<PhotoPoint>)

@Serializable
data class DesignStats(
    val model: String = "",
    val promptTokens: Int = 0,
    val cachedTokens: Int = 0,
    val prefillMs: Long = 0,
    val genTokens: Int = 0,
    val genMs: Long = 0,
    val compileMs: Long = 0,
    val attempts: Int = 1,
    val totalMs: Long = 0,
)

@Serializable
data class DesignVersion(
    val id: String,
    val createdAt: Long,
    /** What was asked for this version: the original request or a refinement. */
    val instruction: String,
    val code: String,
    val ok: Boolean,
    val problems: List<String> = emptyList(),
    /** Slider overrides applied on top of the code (OpenSCAD -D values). */
    val overrides: Map<String, Double> = emptyMap(),
    val sizeMm: List<Double>? = null,
    val stats: DesignStats = DesignStats(),
    /** File name of the exported STL inside the idea folder, if compiled. */
    val stlFile: String? = null,
)

@Serializable
data class Idea(
    val id: String,
    val createdAt: Long,
    val title: String = "",
    val description: String = "",
    val photoFile: String? = null,
    val annotatedFile: String? = null,
    val strokes: List<Stroke> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    /** What the vision model saw in the photo (cached so refinements don't re-run it). */
    val photoNotes: String? = null,
    val designs: List<DesignVersion> = emptyList(),
) {
    val latestDesign: DesignVersion? get() = designs.lastOrNull()
    val displayTitle: String
        get() = title.ifBlank { description.lineSequence().firstOrNull()?.take(60)?.ifBlank { null } ?: "Untitled idea" }
}
