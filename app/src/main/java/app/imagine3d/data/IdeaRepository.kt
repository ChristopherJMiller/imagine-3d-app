package app.imagine3d.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Ideas live in files/ideas/<id>/ as idea.json plus photos and STL files, so they are easy
 * to inspect with `adb shell run-as app.imagine3d`.
 */
class IdeaRepository(context: Context) {
    private val root = File(context.filesDir, "ideas").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val _ideas = MutableStateFlow<List<Idea>>(emptyList())
    val ideas: StateFlow<List<Idea>> = _ideas.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _ideas.value = root.listFiles().orEmpty()
            .mapNotNull { dir -> File(dir, "idea.json").takeIf { it.exists() }?.let { runCatching { json.decodeFromString<Idea>(it.readText()) }.getOrNull() } }
            .sortedByDescending { it.createdAt }
    }

    fun get(id: String): Idea? = _ideas.value.firstOrNull { it.id == id }

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun file(ideaId: String, name: String): File = File(dir(ideaId), name)

    fun newIdea(): Idea = Idea(id = UUID.randomUUID().toString().take(8), createdAt = System.currentTimeMillis())

    suspend fun save(idea: Idea) = withContext(Dispatchers.IO) {
        File(dir(idea.id), "idea.json").writeText(json.encodeToString(Idea.serializer(), idea))
        _ideas.value = (_ideas.value.filter { it.id != idea.id } + idea).sortedByDescending { it.createdAt }
    }

    suspend fun update(id: String, transform: (Idea) -> Idea): Idea? {
        val current = get(id) ?: return null
        val next = transform(current)
        save(next)
        return next
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        _ideas.value = _ideas.value.filter { it.id != id }
    }

    suspend fun writeBitmap(ideaId: String, name: String, bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG) =
        withContext(Dispatchers.IO) {
            file(ideaId, name).outputStream().use { bitmap.compress(format, 90, it) }
            name
        }

    suspend fun writeBytes(ideaId: String, name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        file(ideaId, name).writeBytes(bytes)
        name
    }
}
