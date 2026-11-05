package com.example.data.chat

import com.example.data.local.ChatCacheDao
import com.example.data.local.ConversationEntity
import com.example.data.local.GeneratedImageBinaryCache
import com.example.data.local.MessageEntity
import com.example.data.network.SupabaseAndGatewayClient
import com.example.domain.models.AiErrorType
import com.example.domain.models.AiGatewayException
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationSearchResult
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.ImageGenerationStage
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.StreamChunk
import com.example.domain.repositories.AuthRepository
import com.example.domain.repositories.ChatRepository
import com.example.domain.repositories.ModelCatalogRepository
import com.example.domain.usecases.ConversationContextManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepositoryImpl(
    private val chatCacheDao: ChatCacheDao,
    private val networkClient: SupabaseAndGatewayClient,
    private val authRepository: AuthRepository,
    private val modelCatalogRepository: ModelCatalogRepository
) : ChatRepository {

    private val cloudSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun observeConversations(userId: String): Flow<List<Conversation>> {
        return chatCacheDao.observeConversationsForUser(userId)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun observeMessages(conversationId: String, userId: String): Flow<List<ChatMessage>> {
        return chatCacheDao.observeMessagesForConversation(conversationId, userId)
            .map { list -> list.map { it.toDomain() } }
    }

    override suspend fun syncConversationsFromCloud(
        userId: String,
        limit: Int,
        offset: Int
    ): Result<List<Conversation>> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(
                AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized cross-user access blocked.")
            )
        }
        if (!networkClient.isLiveCloudConfigured || !session.hasValidCloudJwt) {
            return Result.success(emptyList())
        }
        return try {
            val remoteConversations = networkClient.fetchConversations(session.accessToken, userId, limit, offset)
            chatCacheDao.upsertConversations(
                remoteConversations.map { ConversationEntity.fromDomain(it, isSynced = true) }
            )
            Result.success(remoteConversations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncMessagesForConversation(
        conversationId: String,
        userId: String,
        limit: Int,
        offset: Int
    ): Result<List<ChatMessage>> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(
                AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized cross-user access blocked.")
            )
        }
        if (!networkClient.isLiveCloudConfigured || !session.hasValidCloudJwt) {
            val local = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset).map { it.toDomain() }
            return Result.success(local)
        }
        return try {
            val existingLocalById = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset)
                .associateBy { it.id }

            val remoteMessages = networkClient.fetchMessages(session.accessToken, conversationId, userId, limit, offset)
                .mapNotNull { msg ->
                    val localExisting = existingLocalById[msg.id]
                    // Never overwrite an actively streaming local message or a local message with longer content
                    if (localExisting != null) {
                        if (localExisting.status == MessageStatus.STREAMING.dbValue) {
                            return@mapNotNull null
                        }
                        if (localExisting.content.length > msg.content.length) {
                            return@mapNotNull null
                        }
                    }
                    // Normalize any stale STREAMING row fetched from remote cloud so it never appears stuck
                    if (msg.status == MessageStatus.STREAMING) {
                        msg.copy(
                            status = if (msg.content.isNotBlank()) MessageStatus.COMPLETED else MessageStatus.INTERRUPTED,
                            errorMessage = if (msg.content.isBlank()) "Response generation was interrupted." else msg.errorMessage
                        )
                    } else {
                        msg
                    }
                }
            if (remoteMessages.isNotEmpty()) {
                chatCacheDao.upsertMessages(
                    remoteMessages.map { MessageEntity.fromDomain(it, isSynced = true) }
                )
            }
            val mergedLocal = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset).map { it.toDomain() }
            Result.success(mergedLocal.ifEmpty { remoteMessages })
        } catch (e: Exception) {
            val localFallback = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset).map { it.toDomain() }
            if (localFallback.isNotEmpty()) {
                Result.success(localFallback)
            } else {
                Result.failure(e)
            }
        }
    }

    override suspend fun createConversation(
        userId: String,
        title: String,
        selectedModelId: String
    ): Result<Conversation> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(
                AiGatewayException(AiErrorType.UNAUTHORIZED, "Cannot create conversation for unauthenticated user.")
            )
        }
        val nowMs = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = title.trim().ifBlank { "New Conversation" },
            selectedModelId = selectedModelId,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs
        )
        val canSyncToCloud = networkClient.isLiveCloudConfigured && session.hasValidCloudJwt
        chatCacheDao.upsertConversation(
            ConversationEntity.fromDomain(conversation, isSynced = !canSyncToCloud)
        )

        if (canSyncToCloud) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    val synced = networkClient.insertConversation(token, conversation)
                    chatCacheDao.upsertConversation(ConversationEntity.fromDomain(synced, isSynced = true))
                }
            }
        }
        return Result.success(conversation)
    }

    override suspend fun renameConversation(
        conversationId: String,
        userId: String,
        newTitle: String
    ): Result<Conversation> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val existing = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation not found for this user."))
        val cleaned = newTitle.trim().ifBlank { existing.title }
        val updated = existing.copy(
            title = cleaned,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        chatCacheDao.upsertConversation(updated)

        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    networkClient.patchConversation(
                        accessToken = token,
                        conversationId = conversationId,
                        userId = userId,
                        title = cleaned
                    )
                }
            }
        }
        return Result.success(updated.toDomain())
    }

    override suspend fun updateConversationModel(
        conversationId: String,
        userId: String,
        newModelId: String
    ): Result<Conversation> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val existing = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation not found for this user."))
        val updated = existing.copy(
            selectedModelId = newModelId,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        chatCacheDao.upsertConversation(updated)

        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    networkClient.patchConversation(
                        accessToken = token,
                        conversationId = conversationId,
                        userId = userId,
                        selectedModelId = newModelId
                    )
                }
            }
        }
        return Result.success(updated.toDomain())
    }

    override suspend fun deleteConversation(conversationId: String, userId: String): Result<Unit> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        chatCacheDao.deleteMessagesForConversation(conversationId, userId)
        chatCacheDao.deleteConversation(conversationId, userId)
        ConversationContextManager.clearSummary(conversationId)
        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    networkClient.deleteConversation(token, conversationId, userId)
                }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun clearAllUserConversations(userId: String): Result<Unit> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        chatCacheDao.deleteAllMessagesForUser(userId)
        chatCacheDao.deleteAllConversationsForUser(userId)
        ConversationContextManager.clearAllSummaries()
        return Result.success(Unit)
    }

    override suspend fun searchConversations(
        userId: String,
        query: String,
        limit: Int,
        offset: Int
    ): Result<List<ConversationSearchResult>> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized search blocked."))
        }
        val trimmed = query.trim()

        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            runCatching {
                val remoteResults = networkClient.searchChatsRpc(
                    accessToken = session.accessToken,
                    userId = userId,
                    query = trimmed,
                    limit = limit,
                    offset = offset
                )
                if (remoteResults.isNotEmpty()) {
                    return Result.success(remoteResults)
                }
            }
        }

        val matchingConvs = chatCacheDao.searchLocalConversations(userId, trimmed, limit, offset)
        val results = matchingConvs.map { convEntity ->
            val matchedMsg = if (trimmed.isNotEmpty()) {
                chatCacheDao.findMatchingMessageForSnippet(convEntity.id, userId, trimmed)
            } else {
                null
            }
            val latestMsg = matchedMsg ?: chatCacheDao.getLatestMessageForConversation(convEntity.id, userId)
            val snippet = when {
                matchedMsg != null -> buildHighlightedSnippet(matchedMsg.content, trimmed)
                latestMsg != null && latestMsg.content.isNotBlank() -> latestMsg.content.take(140)
                else -> "Model: ${com.example.domain.models.AiModelCatalog.getDisplayName(convEntity.selectedModelId)}"
            }
            ConversationSearchResult(
                conversation = convEntity.toDomain(),
                snippet = snippet,
                matchedInMessage = matchedMsg != null
            )
        }
        return Result.success(results)
    }

    override suspend fun saveUserMessage(
        conversationId: String,
        userId: String,
        content: String,
        modelId: String,
        attachments: List<MessageAttachment>,
        clientRequestId: String
    ): Result<ChatMessage> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val conv = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation does not belong to current user."))

        // Prevent duplicate message insertion for the same clientRequestId
        val existingDuplicate = chatCacheDao.findByClientRequestId(conversationId, clientRequestId, userId)
        if (existingDuplicate != null) {
            return Result.success(existingDuplicate.toDomain())
        }

        val nowMs = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            userId = userId,
            role = MessageRole.USER,
            content = content.trim(),
            modelId = modelId,
            status = MessageStatus.COMPLETED,
            attachments = attachments,
            clientRequestId = clientRequestId,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs
        )

        val canSyncToCloud = networkClient.isLiveCloudConfigured && session.hasValidCloudJwt
        chatCacheDao.upsertMessage(MessageEntity.fromDomain(message, isSynced = !canSyncToCloud))
        chatCacheDao.upsertConversation(conv.copy(updatedAtEpochMs = nowMs))

        if (canSyncToCloud) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    val synced = networkClient.insertMessage(token, message)
                    chatCacheDao.upsertMessage(MessageEntity.fromDomain(synced, isSynced = true))
                }
            }
        }
        return Result.success(message)
    }

    override suspend fun saveAssistantMessage(
        conversationId: String,
        userId: String,
        content: String,
        modelId: String,
        attachments: List<MessageAttachment>,
        clientRequestId: String
    ): Result<ChatMessage> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val conv = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation does not belong to current user."))

        val existingDuplicate = chatCacheDao.findByClientRequestId(conversationId, clientRequestId, userId)
        if (existingDuplicate != null) {
            return Result.success(existingDuplicate.toDomain())
        }

        val nowMs = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            userId = userId,
            role = MessageRole.ASSISTANT,
            content = content.trim(),
            modelId = modelId,
            status = MessageStatus.COMPLETED,
            attachments = attachments,
            clientRequestId = clientRequestId,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs
        )

        val canSyncToCloud = networkClient.isLiveCloudConfigured && session.hasValidCloudJwt
        chatCacheDao.upsertMessage(MessageEntity.fromDomain(message, isSynced = !canSyncToCloud))
        chatCacheDao.upsertConversation(conv.copy(updatedAtEpochMs = nowMs))

        if (canSyncToCloud) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    // Strip large base64 data URLs before syncing cloud row; preserve URI/metadata
                    val cloudSafeAttachments = attachments.map { att ->
                        if ((att.base64DataUrl?.length ?: 0) > 32_768 && att.uriString.isNotBlank()) {
                            att.copy(base64DataUrl = null)
                        } else {
                            att
                        }
                    }
                    val synced = networkClient.insertMessage(token, message.copy(attachments = cloudSafeAttachments))
                    chatCacheDao.upsertMessage(MessageEntity.fromDomain(synced.copy(attachments = attachments), isSynced = true))
                }
            }
        }
        return Result.success(message)
    }

    override suspend fun generateChatImage(
        prompt: String,
        modelId: String,
        width: Int,
        height: Int,
        enhance: Boolean,
        onStageChanged: ((ImageGenerationStage) -> Unit)?
    ): Result<GeneratedImageResult> {
        val session = authRepository.sessionFlow.value
        return try {
            val generated = networkClient.generatePollinationsImage(
                accessToken = session?.accessToken,
                prompt = prompt,
                modelId = modelId,
                width = width,
                height = height,
                enhance = enhance,
                onStageChanged = onStageChanged
            )
            val localUri = GeneratedImageBinaryCache.putGeneratedImage(
                attachmentId = generated.id,
                imageBytes = generated.imageBytes,
                mimeType = generated.mimeType,
                remoteUrl = generated.imageUrl
            )
            Result.success(generated.copy(localFilePath = localUri))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun editUserMessageAndTruncateSubsequent(
        conversationId: String,
        userId: String,
        messageId: String,
        newContent: String
    ): Result<ChatMessage> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val messages = chatCacheDao.getMessagesPaged(conversationId, userId, 500, 0)
        val target = messages.firstOrNull { it.id == messageId && it.userId == userId }
            ?: return Result.failure(IllegalArgumentException("Message not found."))

        chatCacheDao.deleteMessagesAfter(conversationId, userId, target.createdAtEpochMs)
        val updated = target.copy(
            content = newContent.trim(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        chatCacheDao.upsertMessage(updated)
        return Result.success(updated.toDomain())
    }

    override suspend fun deleteMessage(messageId: String, userId: String): Result<Unit> {
        chatCacheDao.deleteMessage(messageId, userId)
        return Result.success(Unit)
    }

    override fun streamAssistantResponse(
        conversationId: String,
        userId: String,
        requestedModelId: String,
        fallbackModelId: String?,
        allowFallback: Boolean,
        history: List<ChatMessage>,
        streamEnabled: Boolean,
        clientRequestId: String
    ): Flow<StreamChunk> = flow {
        val cachedSession = authRepository.sessionFlow.value
        val session = if (
            cachedSession != null &&
            cachedSession.user.id == userId &&
            (cachedSession.expiresAtEpochMs <= 0L || cachedSession.expiresAtEpochMs - System.currentTimeMillis() > 60_000L)
        ) {
            cachedSession
        } else {
            authRepository.refreshSessionIfNeeded().getOrNull()
        }
        if (session == null || session.user.id != userId) {
            emit(
                StreamChunk.Error(
                    AiGatewayException(
                        errorType = AiErrorType.UNAUTHORIZED,
                        userFriendlyMessage = "Your session has expired. Please sign in again.",
                        requestedModelId = requestedModelId,
                        canRetry = false
                    ),
                    partialText = ""
                )
            )
            return@flow
        }

        // Verify ownership of the conversation before making any provider call
        val convEntity = chatCacheDao.getConversation(conversationId, userId)
        if (convEntity == null) {
            emit(
                StreamChunk.Error(
                    AiGatewayException(
                        errorType = AiErrorType.UNAUTHORIZED,
                        userFriendlyMessage = "Access denied: Conversation does not belong to the active user.",
                        requestedModelId = requestedModelId,
                        canRetry = false
                    ),
                    partialText = ""
                )
            )
            return@flow
        }

        // Validate that a chat model is configured and available in the catalog
        if (requestedModelId.isBlank()) {
            emit(
                StreamChunk.Error(
                    AiGatewayException(
                        errorType = AiErrorType.UNSUPPORTED_MODEL,
                        userFriendlyMessage = "No chat AI provider or model is currently configured.",
                        requestedModelId = requestedModelId,
                        canRetry = false
                    ),
                    partialText = ""
                )
            )
            return@flow
        }

        val requestedModelDef = modelCatalogRepository.getModelById(requestedModelId)
        var effectiveModelId = requestedModelId
        var fallbackTriggered = false

        if (requestedModelDef.availabilityStatus == AvailabilityStatus.UNAVAILABLE) {
            if (allowFallback && !fallbackModelId.isNullOrBlank() && fallbackModelId != requestedModelId) {
                val fallbackDef = modelCatalogRepository.getModelById(fallbackModelId)
                if (fallbackDef.availabilityStatus != AvailabilityStatus.UNAVAILABLE) {
                    effectiveModelId = fallbackModelId
                    fallbackTriggered = true
                    emit(StreamChunk.Meta(effectiveModelId, requestedModelId, fallbackUsed = true))
                } else {
                    emit(
                        StreamChunk.Error(
                            AiGatewayException(
                                errorType = AiErrorType.UNSUPPORTED_MODEL,
                                userFriendlyMessage = "The selected model and fallback model are currently unavailable.",
                                requestedModelId = requestedModelId,
                                canRetry = false
                            ),
                            partialText = ""
                        )
                    )
                    return@flow
                }
            } else {
                emit(
                    StreamChunk.Error(
                        AiGatewayException(
                            errorType = AiErrorType.UNSUPPORTED_MODEL,
                            userFriendlyMessage = "The model '${requestedModelDef.displayName}' ($requestedModelId) is currently unavailable. Please select an available Smarter AI model.",
                            requestedModelId = requestedModelId,
                            canRetry = false
                        ),
                        partialText = ""
                    )
                )
                return@flow
            }
        }

        val assistantMsgId = UUID.randomUUID().toString()
        val assistantClientReqId = "${clientRequestId}_assistant"
        val startMs = System.currentTimeMillis()

        // Create initial streaming message placeholder in local cache
        val initialEntity = MessageEntity(
            id = assistantMsgId,
            conversationId = conversationId,
            userId = userId,
            role = MessageRole.ASSISTANT.apiValue,
            content = "",
            modelId = effectiveModelId,
            requestedModelId = requestedModelId,
            fallbackUsed = fallbackTriggered,
            status = MessageStatus.STREAMING.dbValue,
            clientRequestId = assistantClientReqId,
            createdAtEpochMs = startMs,
            updatedAtEpochMs = startMs,
            isSyncedWithCloud = false
        )
        chatCacheDao.upsertMessage(initialEntity)

        val effectiveStream = streamEnabled && requestedModelDef.supportsStreaming
        val partialBuilder = StringBuilder()

        try {
            val upstreamFlow = networkClient.streamChatCompletion(
                accessToken = session.accessToken,
                conversationId = conversationId,
                requestedModelId = effectiveModelId,
                fallbackModelId = fallbackModelId,
                allowFallback = allowFallback,
                history = history,
                streamEnabled = effectiveStream,
                clientRequestId = clientRequestId
            )

            emitAll(
                upstreamFlow.map { chunk ->
                    when (chunk) {
                        is StreamChunk.Meta -> {
                            val newActiveModelId = chunk.activeModelId
                            val newFallback = chunk.fallbackUsed || fallbackTriggered
                            if (newActiveModelId != effectiveModelId || newFallback != fallbackTriggered) {
                                effectiveModelId = newActiveModelId
                                fallbackTriggered = newFallback
                                chatCacheDao.upsertMessage(
                                    initialEntity.copy(
                                        modelId = effectiveModelId,
                                        fallbackUsed = fallbackTriggered,
                                        updatedAtEpochMs = System.currentTimeMillis()
                                    )
                                )
                            }
                            chunk.copy(
                                requestedModelId = requestedModelId,
                                fallbackUsed = fallbackTriggered
                            )
                        }
                        is StreamChunk.ReasoningDelta -> {
                            chunk
                        }
                        is StreamChunk.Delta -> {
                            partialBuilder.append(chunk.textDelta)
                            effectiveModelId = chunk.activeModelId
                            fallbackTriggered = chunk.fallbackUsed || fallbackTriggered
                            // Emit UI token deltas immediately with zero SQLite disk I/O blocking the stream;
                            // final or interrupted content is persisted on Completed, Error, or Cancellation.
                            chunk
                        }
                        is StreamChunk.Completed -> {
                            val finalContent = chunk.fullText.ifEmpty { partialBuilder.toString() }
                            val now = System.currentTimeMillis()
                            val completedEntity = initialEntity.copy(
                                content = finalContent,
                                modelId = chunk.activeModelId,
                                fallbackUsed = chunk.fallbackUsed || fallbackTriggered,
                                status = MessageStatus.COMPLETED.dbValue,
                                updatedAtEpochMs = now,
                                isSyncedWithCloud = true
                            )
                            chatCacheDao.upsertMessage(completedEntity)
                            chatCacheDao.upsertConversation(convEntity.copy(updatedAtEpochMs = now))
                            val dbPersistedAtMs = System.currentTimeMillis()

                            if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
                                val token = session.accessToken
                                val domainMsg = completedEntity.toDomain()
                                cloudSyncScope.launch {
                                    runCatching {
                                        networkClient.insertMessage(token, domainMsg)
                                    }
                                }
                            }
                            chunk.copy(
                                fullText = finalContent,
                                fallbackUsed = chunk.fallbackUsed || fallbackTriggered,
                                timingMetrics = chunk.timingMetrics?.copy(dbPersistedEpochMs = dbPersistedAtMs)
                            )
                        }
                        is StreamChunk.Error -> {
                            val now = System.currentTimeMillis()
                            val savedText = chunk.partialText.ifEmpty { partialBuilder.toString() }
                            val finalStatus = if (savedText.isNotBlank()) {
                                MessageStatus.INTERRUPTED.dbValue
                            } else {
                                MessageStatus.ERROR.dbValue
                            }
                            chatCacheDao.upsertMessage(
                                initialEntity.copy(
                                    content = savedText,
                                    status = finalStatus,
                                    errorMessage = chunk.exception.userFriendlyMessage,
                                    errorCode = chunk.exception.errorType.name,
                                    updatedAtEpochMs = now
                                )
                            )
                            val dbPersistedAtMs = System.currentTimeMillis()
                            chunk.copy(
                                partialText = savedText,
                                timingMetrics = chunk.timingMetrics?.copy(dbPersistedEpochMs = dbPersistedAtMs)
                            )
                        }
                    }
                }
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                val savedText = partialBuilder.toString()
                if (savedText.isNotBlank()) {
                    chatCacheDao.upsertMessage(
                        initialEntity.copy(
                            content = savedText,
                            status = MessageStatus.INTERRUPTED.dbValue,
                            errorMessage = "Generation stopped by user.",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                } else {
                    chatCacheDao.deleteMessage(assistantMsgId, userId)
                }
            }
            throw ce
        }
    }

    private fun buildHighlightedSnippet(content: String, query: String): String {
        val clean = content.replace("\n", " ").trim()
        val index = clean.indexOf(query, ignoreCase = true)
        if (index < 0) return clean.take(140)
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + query.length + 80).coerceAtMost(clean.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < clean.length) "…" else ""
        return prefix + clean.substring(start, end) + suffix
    }
}
