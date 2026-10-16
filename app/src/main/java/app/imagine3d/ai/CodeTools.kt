package app.imagine3d.ai

/** A top-level `name = number; // comment` assignment, exposed as a slider. */
data class ScadParam(val name: String, val value: Double, val comment: String, val min: Double, val max: Double, val step: Double)

object CodeTools {
    private val fenced = Regex("```(?:openscad|scad)?[ \\t]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
    private val unterminated = Regex("```(?:openscad|scad)?[ \\t]*\\n(.*)", RegexOption.DOT_MATCHES_ALL)
    private val assignment = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(-?\\d+(?:\\.\\d+)?)\\s*;\\s*(?://\\s*(.*))?$")

    /** Same rules as tools/eval/run_eval.py: the longest fenced block, else an unterminated one, else everything. */
    fun extractCode(reply: String): String {
        val blocks = fenced.findAll(reply).map { it.groupValues[1] }.toList()
        if (blocks.isNotEmpty()) return blocks.maxBy { it.length }
        unterminated.find(reply)?.let { return it.groupValues[1] }
        return reply
    }

    private val nameLine = Regex("^[ \\t]*//[ \\t]*Name:[ \\t]*(.*?)[ \\t]*\\r?\\n", RegexOption.MULTILINE)

    /**
     * The part's title from the `// Name:` line the model writes first, once that line is
     * complete (so it can be read while the reply is still streaming). Capped at 6 words.
     */
    fun extractName(reply: String): String? {
        val raw = nameLine.find(reply)?.groupValues?.get(1) ?: return null
        val words = raw.trim().trim('"', '\'', '`', '.', ':').split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.take(6).joinToString(" ").ifBlank { null }
    }

    /** What to show while a reply streams in: just the code, without the ``` fences around it. */
    fun streamingView(reply: String): String {
        val open = reply.indexOf("```")
        if (open < 0) return reply
        val bodyStart = reply.indexOf('\n', open)
        if (bodyStart < 0) return ""  // still receiving the opening fence line
        val body = reply.substring(bodyStart + 1)
        val close = body.indexOf("```")
        return if (close >= 0) body.substring(0, close) else body
    }

    /** True once the reply has an opening fence and a closing one after it: generation can stop. */
    fun codeBlockClosed(reply: String): Boolean {
        val open = reply.indexOf("```")
        if (open < 0) return false
        val bodyStart = reply.indexOf('\n', open)
        return bodyStart >= 0 && reply.indexOf("```", bodyStart) >= 0
    }

    fun parseParams(code: String): List<ScadParam> {
        val out = mutableListOf<ScadParam>()
        var depth = 0
        for (line in code.lineSequence()) {
            if (depth == 0) {
                assignment.find(line)?.let { m ->
                    val name = m.groupValues[1]
                    val v = m.groupValues[2].toDouble()
                    if (out.none { it.name == name }) out += makeParam(name, v, m.groupValues[3].trim())
                }
            }
            val stripped = line.substringBefore("//")
            depth += stripped.count { it == '{' } - stripped.count { it == '}' }
            if (depth < 0) depth = 0
        }
        return out
    }

    private fun makeParam(name: String, v: Double, comment: String): ScadParam {
        val lower = name.lowercase()
        val isCount = v == Math.floor(v) && v in 0.0..50.0 &&
            listOf("count", "cols", "rows", "num", "holes", "sides", "n_", "number").any { lower.contains(it) || lower == "n" }
        return when {
            isCount -> ScadParam(name, v, comment, 0.0, maxOf(10.0, v * 2), 1.0)
            lower.contains("angle") || lower.endsWith("_a") -> ScadParam(name, v, comment, 0.0, maxOf(90.0, v * 1.5), 1.0)
            v == 0.0 -> ScadParam(name, v, comment, 0.0, 10.0, 0.5)
            else -> {
                val max = maxOf(v * 2.5, v + 20)
                ScadParam(name, v, comment, 0.0, Math.ceil(max), if (max <= 40) 0.5 else 1.0)
            }
        }
    }

    /** Applies slider values by rewriting the assignments, so the saved code reflects them. */
    fun applyOverrides(code: String, overrides: Map<String, Double>): String {
        if (overrides.isEmpty()) return code
        var depth = 0
        return code.lineSequence().joinToString("\n") { line ->
            var result = line
            if (depth == 0) {
                assignment.find(line)?.let { m ->
                    overrides[m.groupValues[1]]?.let { v ->
                        val range = m.groups[2]!!.range
                        result = line.replaceRange(range, PromptAssets.formatMm(v))
                    }
                }
            }
            val stripped = line.substringBefore("//")
            depth = (depth + stripped.count { it == '{' } - stripped.count { it == '}' }).coerceAtLeast(0)
            result
        }
    }
}
