package com.example.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores generated image binaries on disk (or in memory during JVM unit tests) so conversation
 * history can persist clean file/URL references in SQLite (`cached_messages.attachmentsJson`)
 * without bloating SQLite rows with large Base64 strings.
 */
object GeneratedImageBinaryCache {
    private val memoryByAttachmentId = ConcurrentHashMap<String, ByteArray>()
    private val memoryByUri = ConcurrentHashMap<String, ByteArray>()

    fun putGeneratedImage(
        attachmentId: String,
        imageBytes: ByteArray,
        mimeType: String = "image/png",
        remoteUrl: String? = null
    ): String {
        if (attachmentId.isNotBlank() && imageBytes.isNotEmpty()) {
            memoryByAttachmentId[attachmentId] = imageBytes
        }
        val ext = when {
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val fileUri = runCatching {
            val dir = File(System.getProperty("java.io.tmpdir"), "smarter_ai_generated_images")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, "gen_${attachmentId.ifBlank { System.currentTimeMillis().toString() }}.$ext")
            outFile.writeBytes(imageBytes)
            outFile.toURI().toString()
        }.getOrNull() ?: remoteUrl.orEmpty().ifBlank { "pollinations://generated/$attachmentId" }

        if (fileUri.isNotBlank() && imageBytes.isNotEmpty()) {
            memoryByUri[fileUri] = imageBytes
        }
        if (!remoteUrl.isNullOrBlank() && imageBytes.isNotEmpty()) {
            memoryByUri[remoteUrl] = imageBytes
        }
        return fileUri
    }

    fun getBytes(
        attachmentId: String,
        uriString: String,
        base64DataUrl: String? = null
    ): ByteArray? {
        if (attachmentId.isNotBlank()) {
            memoryByAttachmentId[attachmentId]?.let { return it }
        }
        if (uriString.isNotBlank()) {
            memoryByUri[uriString]?.let { return it }
            if (uriString.startsWith("file:")) {
                val diskBytes = runCatching {
                    val file = File(java.net.URI(uriString))
                    if (file.exists() && file.canRead()) file.readBytes() else null
                }.getOrNull()
                if (diskBytes != null && diskBytes.isNotEmpty()) {
                    memoryByUri[uriString] = diskBytes
                    if (attachmentId.isNotBlank()) memoryByAttachmentId[attachmentId] = diskBytes
                    return diskBytes
                }
            }
        }
        if (!base64DataUrl.isNullOrBlank()) {
            val rawBase64 = base64DataUrl.substringAfter("base64,", base64DataUrl)
            val decoded = runCatching {
                java.util.Base64.getDecoder().decode(rawBase64)
            }.getOrNull()
            if (decoded != null && decoded.isNotEmpty()) {
                return decoded
            }
        }
        return null
    }
}

@Entity(
    tableName = "cached_conversations",
    indices = [
        Index(value = ["userId", "updatedAtEpochMs"]),
        Index(value = ["userId", "title"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val selectedModelId: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val archivedAtEpochMs: Long? = null,
    val isSyncedWithCloud: Boolean = true
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        userId = userId,
        title = title,
        selectedModelId = selectedModelId,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        archivedAtEpochMs = archivedAtEpochMs
    )

    companion object {
        fun fromDomain(domain: Conversation, isSynced: Boolean = true): ConversationEntity =
            ConversationEntity(
                id = domain.id,
                userId = domain.userId,
                title = domain.title,
                selectedModelId = domain.selectedModelId,
                createdAtEpochMs = domain.createdAtEpochMs,
                updatedAtEpochMs = domain.updatedAtEpochMs,
                archivedAtEpochMs = domain.archivedAtEpochMs,
                isSyncedWithCloud = isSynced
            )
    }
}

@Entity(
    tableName = "cached_messages",
    indices = [
        Index(value = ["conversationId", "createdAtEpochMs"]),
        Index(value = ["userId"]),
        Index(value = ["conversationId", "clientRequestId"], unique = true)
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userId: String,
    val role: String,
    val content: String,
    val modelId: String,
    val requestedModelId: String? = null,
    val fallbackUsed: Boolean = false,
    val status: String,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val attachmentsJson: String = "[]",
    val clientRequestId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isSyncedWithCloud: Boolean = true
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        conversationId = conversationId,
        userId = userId,
        role = MessageRole.fromApiValue(role),
        content = content,
        modelId = modelId,
        requestedModelId = requestedModelId,
        fallbackUsed = fallbackUsed,
        status = MessageStatus.fromDbValue(status),
        errorMessage = errorMessage,
        errorCode = errorCode,
        attachments = parseAttachments(attachmentsJson),
        clientRequestId = clientRequestId,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        private const val MAX_INLINE_SQLITE_BASE64_CHARS = 32_768
        private val inMemoryBase64Cache = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun fromDomain(domain: ChatMessage, isSynced: Boolean = true): MessageEntity =
            MessageEntity(
                id = domain.id,
                conversationId = domain.conversationId,
                userId = domain.userId,
                role = domain.role.apiValue,
                content = domain.content,
                modelId = domain.modelId,
                requestedModelId = domain.requestedModelId,
                fallbackUsed = domain.fallbackUsed,
                status = domain.status.dbValue,
                errorMessage = domain.errorMessage,
                errorCode = domain.errorCode,
                attachmentsJson = encodeAttachments(domain.attachments),
                clientRequestId = domain.clientRequestId,
                createdAtEpochMs = domain.createdAtEpochMs,
                updatedAtEpochMs = domain.updatedAtEpochMs,
                isSyncedWithCloud = isSynced
            )

        private fun encodeAttachments(list: List<MessageAttachment>): String {
            if (list.isEmpty()) return "[]"
            val arr = JSONArray()
            for (item in list) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("uriString", item.uriString)
                obj.put("mimeType", item.mimeType)
                obj.put("fileName", item.fileName)
                val dataUrl = item.base64DataUrl
                if (!dataUrl.isNullOrEmpty()) {
                    if (item.id.isNotBlank()) {
                        inMemoryBase64Cache[item.id] = dataUrl
                    }
                    // Never bloat SQLite CursorWindow with base64 strings when a URI/file reference is present;
                    // only inline when no uriString exists and payload is small.
                    if (item.uriString.isBlank() && dataUrl.length <= MAX_INLINE_SQLITE_BASE64_CHARS) {
                        obj.put("base64DataUrl", dataUrl)
                    }
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        private fun parseAttachments(json: String): List<MessageAttachment> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                val arr = JSONArray(json)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optString("id")
                        val uriString = obj.optString("uriString")
                        val mimeType = obj.optString("mimeType", "image/jpeg")
                        val storedBase64 = obj.optString("base64DataUrl").takeIf { it.isNotEmpty() }
                            ?: if (id.isNotBlank()) inMemoryBase64Cache[id] else null
                        add(
                            MessageAttachment(
                                id = id,
                                uriString = uriString,
                                mimeType = mimeType,
                                fileName = obj.optString("fileName", "image.jpg"),
                                base64DataUrl = storedBase64
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

@Dao
interface ChatCacheDao {
    // Strictly enforce userId ownership on every local query as defense-in-depth alongside Supabase RLS
    @Query("SELECT * FROM cached_conversations WHERE userId = :userId AND archivedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC")
    fun observeConversationsForUser(userId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM cached_conversations WHERE id = :conversationId AND userId = :userId LIMIT 1")
    suspend fun getConversation(conversationId: String, userId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM cached_conversations WHERE id = :conversationId AND userId = :userId")
    suspend fun deleteConversation(conversationId: String, userId: String)

    @Query("DELETE FROM cached_conversations WHERE userId = :userId")
    suspend fun deleteAllConversationsForUser(userId: String)

    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId AND userId = :userId ORDER BY createdAtEpochMs ASC")
    fun observeMessagesForConversation(conversationId: String, userId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId AND userId = :userId ORDER BY createdAtEpochMs ASC LIMIT :limit OFFSET :offset")
    suspend fun getMessagesPaged(conversationId: String, userId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM cached_messages WHERE conversationId = :conversationId AND clientRequestId = :clientRequestId AND userId = :userId LIMIT 1")
    suspend fun findByClientRequestId(conversationId: String, clientRequestId: String, userId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("DELETE FROM cached_messages WHERE id = :messageId AND userId = :userId")
    suspend fun deleteMessage(messageId: String, userId: String)

    @Query("DELETE FROM cached_messages WHERE conversationId = :conversationId AND userId = :userId AND createdAtEpochMs > :afterTimestampMs")
    suspend fun deleteMessagesAfter(conversationId: String, userId: String, afterTimestampMs: Long)

    @Query("DELETE FROM cached_messages WHERE conversationId = :conversationId AND userId = :userId")
    suspend fun deleteMessagesForConversation(conversationId: String, userId: String)

    @Query("DELETE FROM cached_messages WHERE userId = :userId")
    suspend fun deleteAllMessagesForUser(userId: String)

    @Query(
        """
        SELECT DISTINCT c.* FROM cached_conversations c
        LEFT JOIN cached_messages m ON m.conversationId = c.id AND m.userId = :userId
        WHERE c.userId = :userId AND c.archivedAtEpochMs IS NULL
          AND (:query = '' OR LOWER(c.title) LIKE '%' || LOWER(:query) || '%' OR LOWER(m.content) LIKE '%' || LOWER(:query) || '%')
        ORDER BY c.updatedAtEpochMs DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchLocalConversations(userId: String, query: String, limit: Int, offset: Int): List<ConversationEntity>

    @Query(
        """
        SELECT * FROM cached_messages
        WHERE conversationId = :conversationId AND userId = :userId
          AND LOWER(content) LIKE '%' || LOWER(:query) || '%'
        ORDER BY createdAtEpochMs DESC LIMIT 1
        """
    )
    suspend fun findMatchingMessageForSnippet(conversationId: String, userId: String, query: String): MessageEntity?

    @Query(
        """
        SELECT * FROM cached_messages
        WHERE conversationId = :conversationId AND userId = :userId
        ORDER BY createdAtEpochMs DESC LIMIT 1
        """
    )
    suspend fun getLatestMessageForConversation(conversationId: String, userId: String): MessageEntity?

    // Recovery after app relaunch: mark any in-flight 'streaming' or 'sending' messages as 'interrupted'
    @Query("UPDATE cached_messages SET status = 'interrupted' WHERE userId = :userId AND (status = 'streaming' OR status = 'sending')")
    suspend fun markInFlightMessagesInterrupted(userId: String)
}

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NexusAiLocalDatabase : RoomDatabase() {
    abstract fun chatCacheDao(): ChatCacheDao
}
