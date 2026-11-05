package com.example.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.data.livedata.LiveMarketDataRegistry
import com.example.data.local.GeneratedImageBinaryCache
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.ChatMessage
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.LiveMarketDataPayload
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.ui.components.LiveMarketChartCard
import com.example.ui.components.MarkdownMessageContent
import com.example.ui.history.ConversationDrawerContent
import com.example.ui.imagegen.DedicatedChatImageGenerationScreen
import com.example.ui.imagegen.saveGeneratedImageToGallery
import com.example.ui.imagegen.shareGeneratedImage
import com.example.ui.modelselector.ModelSelectorBottomSheet
import com.example.ui.theme.StatusAvailableGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PromptSuggestionItem(
    val title: String,
    val subtitle: String,
    val promptText: String,
    val icon: ImageVector
)

private val WELCOME_SUGGESTIONS = listOf(
    PromptSuggestionItem(
        title = "Explain a concept",
        subtitle = "Zero-knowledge proofs in plain terms",
        promptText = "Explain how zero-knowledge proofs work using a clear everyday analogy, then show a concise technical breakdown.",
        icon = Icons.Outlined.Lightbulb
    ),
    PromptSuggestionItem(
        title = "Help with coding",
        subtitle = "Kotlin Flow retry & backoff pattern",
        promptText = "Write an idiomatic Kotlin Coroutines Flow retry-with-exponential-backoff operator and explain how to test it.",
        icon = Icons.Outlined.Code
    ),
    PromptSuggestionItem(
        title = "Write something",
        subtitle = "Product launch announcement memo",
        promptText = "Draft a crisp, high-impact engineering announcement introducing a multi-model AI gateway with strict privacy guarantees.",
        icon = Icons.Outlined.EditNote
    ),
    PromptSuggestionItem(
        title = "Analyze a problem",
        subtitle = "PostgreSQL indexing vs write throughput",
        promptText = "Analyze the trade-offs between GIN full-text indexes and trigram indexes in PostgreSQL for high-write chat applications.",
        icon = Icons.Outlined.Psychology
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onStartNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenSearchScreen: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
    onSignOut: () -> Unit,
    onOpenModelSelector: () -> Unit,
    onCloseModelSelector: () -> Unit,
    onModelSearchQueryChanged: (String) -> Unit,
    onModelCategorySelected: (com.example.domain.models.ModelCategory?) -> Unit,
    onRefreshModelCatalog: () -> Unit,
    onSelectModel: (String) -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onApplyPromptSuggestion: (String) -> Unit,
    onAddAttachment: (MessageAttachment) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerateResponse: () -> Unit,
    onRetryFailedGeneration: () -> Unit,
    onStartEditingMessage: (ChatMessage) -> Unit,
    onCancelEditingMessage: () -> Unit,
    onDismissBanners: () -> Unit,
    onOpenImageStudio: () -> Unit = {},
    onOpenAudioStudio: () -> Unit = {},
    onOpenVoiceChat: () -> Unit = {},
    onRefreshLiveMarketData: (String) -> Unit = {},
    onCancelImageGeneration: () -> Unit = {},
    onRetryImageGeneration: () -> Unit = {},
    onDismissImageGenerationScreen: () -> Unit = {},
    onOpenGeneratedImageFromChat: (ChatMessage, MessageAttachment) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val activeImageSession = uiState.activeImageGenerationSession
    if (activeImageSession != null && activeImageSession.isScreenVisible) {
        DedicatedChatImageGenerationScreen(
            session = activeImageSession,
            onCancelGeneration = onCancelImageGeneration,
            onRetryGeneration = onRetryImageGeneration,
            onContinueInChat = onDismissImageGenerationScreen,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Photo Picker for Vision-capable models (Zero-permission Android Photo Picker)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val encodedDataUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val rawBytes = stream.readBytes()
                            val decoded = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                            val finalBytes = if (decoded != null && decoded.width > 0 && decoded.height > 0) {
                                val maxDim = 896
                                val scale = minOf(1f, maxDim.toFloat() / maxOf(decoded.width, decoded.height))
                                val scaled = if (scale < 1f) {
                                    android.graphics.Bitmap.createScaledBitmap(
                                        decoded,
                                        (decoded.width * scale).toInt().coerceAtLeast(1),
                                        (decoded.height * scale).toInt().coerceAtLeast(1),
                                        true
                                    )
                                } else {
                                    decoded
                                }
                                val out = java.io.ByteArrayOutputStream()
                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                                out.toByteArray()
                            } else {
                                rawBytes
                            }
                            val b64 = android.util.Base64.encodeToString(finalBytes, android.util.Base64.NO_WRAP)
                            "data:image/jpeg;base64,$b64"
                        }
                    }.getOrNull()
                }
                onAddAttachment(
                    MessageAttachment(
                        id = UUID.randomUUID().toString(),
                        uriString = uri.toString(),
                        mimeType = "image/jpeg",
                        fileName = "image_${System.currentTimeMillis()}.jpg",
                        base64DataUrl = encodedDataUrl
                    )
                )
            }
        }
    }

    // Pixel-accurate bottom detection that accounts for tall messages exceeding viewport height
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) {
                true
            } else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                if (lastVisible == null) {
                    true
                } else {
                    val viewportBottom = layoutInfo.viewportEndOffset
                    val itemBottom = lastVisible.offset + lastVisible.size
                    lastVisible.index == totalItems - 1 && (itemBottom - viewportBottom) <= 96
                }
            }
        }
    }

    var isAutoScrollLockedToBottom by rememberSaveable { mutableStateOf(true) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Respect user touch gestures: if the user manually scrolls upward, pause auto-scroll; resume when back at bottom
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .collect { (inProgress, atBottom) ->
                if (inProgress && !isProgrammaticScroll) {
                    isAutoScrollLockedToBottom = atBottom
                } else if (atBottom) {
                    isAutoScrollLockedToBottom = true
                }
            }
    }

    // Re-lock and scroll to bottom when the user sends a message or switches conversations
    LaunchedEffect(uiState.userSendScrollTrigger, uiState.activeConversationId) {
        isAutoScrollLockedToBottom = true
        if (uiState.messages.isNotEmpty()) {
            isProgrammaticScroll = true
            try {
                listState.scrollToBottomEdge(itemCount = uiState.messages.size, animate = false)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    // Follow new messages and streaming tokens (coalesced to avoid excessive layout passes on every token)
    val lastMessageContentLength = uiState.messages.lastOrNull()?.content?.length ?: 0
    val streamingScrollBucket = if (uiState.streamingMessageText.isEmpty()) 0 else (uiState.streamingMessageText.length / 24) + 1
    LaunchedEffect(uiState.messages.size, streamingScrollBucket, lastMessageContentLength, uiState.isGenerating) {
        if (uiState.messages.isNotEmpty() && isAutoScrollLockedToBottom && !listState.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                listState.scrollToBottomEdge(itemCount = uiState.messages.size, animate = false)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawerContent(
                groupedConversations = uiState.groupedConversations,
                activeConversationId = uiState.activeConversationId,
                userDisplayName = uiState.activeUserDisplayName,
                userEmail = uiState.activeUserEmail,
                onNewChatClick = {
                    coroutineScope.launch { drawerState.close() }
                    onStartNewChat()
                },
                onSearchChatsClick = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenSearchScreen()
                },
                onSelectConversation = { id ->
                    coroutineScope.launch { drawerState.close() }
                    onSelectConversation(id)
                },
                onRenameConversation = onRenameConversation,
                onDeleteConversation = onDeleteConversation,
                onOpenSettings = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenSettingsScreen()
                },
                onOpenImageStudio = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenImageStudio()
                },
                onOpenAudioStudio = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenAudioStudio()
                },
                onOpenVoiceChat = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenVoiceChat()
                },
                onSignOut = {
                    coroutineScope.launch { drawerState.close() }
                    onSignOut()
                }
            )
        }
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.systemBars,
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("open_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Menu,
                                contentDescription = "Open conversation sidebar"
                            )
                        }
                    },
                    title = {
                        Column(
                            horizontalAlignment = Alignment.Start
                        ) {
                            // Model Selector Pill
                            Surface(
                                onClick = onOpenModelSelector,
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.testTag("top_bar_model_selector_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = StatusAvailableGreen,
                                        modifier = Modifier.size(7.dp)
                                    ) {}
                                    Text(
                                        text = AiModelCatalog.formatUserFacingModelName(uiState.selectedModel.displayName),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.ExpandMore,
                                        contentDescription = "Switch AI model",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (uiState.activeConversation != null) {
                                Text(
                                    text = uiState.activeConversation.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(start = 6.dp, top = 2.dp)
                                        .testTag("active_conversation_title")
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onOpenVoiceChat,
                            modifier = Modifier.testTag("top_bar_voice_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.RecordVoiceOver,
                                contentDescription = "Open GPT 6 Luna Voice Chat",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = onOpenAudioStudio,
                            modifier = Modifier.testTag("top_bar_audio_studio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.GraphicEq,
                                contentDescription = "Open Voice & Read Aloud"
                            )
                        }
                        IconButton(
                            onClick = onStartNewChat,
                            modifier = Modifier.testTag("top_bar_new_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Start New Chat"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
            ) {
                // Fallback Model Notification Banner
                AnimatedVisibility(
                    visible = !uiState.fallbackBannerNotice.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = StatusWarningAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StatusWarningAmber.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("fallback_model_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = StatusWarningAmber
                            )
                            Text(
                                text = uiState.fallbackBannerNotice.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissBanners,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Error Banner with Retry
                AnimatedVisibility(
                    visible = !uiState.errorBannerMessage.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = StatusErrorRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("chat_error_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = uiState.errorBannerMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusErrorRed,
                                modifier = Modifier.weight(1f)
                            )
                            if (uiState.canRetryLastPrompt) {
                                TextButton(
                                    onClick = onRetryFailedGeneration,
                                    modifier = Modifier.testTag("error_banner_retry_button")
                                ) {
                                    Text("Retry", color = StatusErrorRed, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            IconButton(
                                onClick = onDismissBanners,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Dismiss error",
                                    tint = StatusErrorRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Main Content Area: Either Welcome Screen (New Chat) or Message Stream
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (uiState.activeConversationId == null && uiState.messages.isEmpty() && !uiState.isGenerating) {
                        NewChatWelcomeView(
                            selectedModelName = uiState.selectedModel.displayName,
                            selectedModelId = uiState.selectedModel.providerModelId,
                            recentConversations = uiState.conversations.take(3),
                            onOpenModelSelector = onOpenModelSelector,
                            onSuggestionClicked = onApplyPromptSuggestion,
                            onSelectRecentConversation = onSelectConversation,
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 680.dp)
                        )
                    } else {
                        val lastAssistantId = remember(uiState.messages) {
                            uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                        }
                        val hasTrailingAssistantMessage = remember(uiState.messages) {
                            uiState.messages.lastOrNull()?.role == MessageRole.ASSISTANT
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 720.dp)
                                .testTag("chat_messages_list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(
                                items = uiState.messages,
                                key = { it.id }
                            ) { message ->
                                val isLastAssistant = message.role == MessageRole.ASSISTANT &&
                                    message.id == lastAssistantId
                                val displayMessage = if (
                                    isLastAssistant &&
                                    uiState.isGenerating &&
                                    uiState.streamingMessageText.isNotEmpty()
                                ) {
                                    message.copy(content = uiState.streamingMessageText)
                                } else {
                                    message
                                }

                                val showImageAnalysisForBubble = isLastAssistant &&
                                    uiState.isGenerating &&
                                    uiState.isAnalyzingImage &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.RESPONSE_GENERATION &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.COMPLETED &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.FAILED &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.CANCELLED &&
                                    uiState.streamingMessageText.isEmpty() &&
                                    displayMessage.content.isBlank()

                                val liveMarketPayload = uiState.liveMarketDataByMessageId[message.id]
                                    ?: LiveMarketDataRegistry.get(message.id)

                                MessageBubbleItem(
                                    message = displayMessage,
                                    renderMarkdown = uiState.preferences.renderMarkdown,
                                    isGenerating = uiState.isGenerating && isLastAssistant,
                                    isAnalyzingImage = showImageAnalysisForBubble,
                                    liveActivityStatus = if (isLastAssistant && uiState.isGenerating) uiState.activeLiveDataActivityStatus else null,
                                    liveMarketPayload = liveMarketPayload,
                                    canRegenerate = isLastAssistant && !uiState.isGenerating,
                                    onEditUserMessage = { onStartEditingMessage(message) },
                                    onRegenerate = onRegenerateResponse,
                                    onRefreshLiveMarketData = { onRefreshLiveMarketData(message.id) },
                                    onOpenGeneratedImage = { att -> onOpenGeneratedImageFromChat(displayMessage, att) }
                                )
                            }

                            if (uiState.isGenerating && !hasTrailingAssistantMessage) {
                                item(key = "pending_assistant_generation") {
                                    val showPendingImageAnalysis = uiState.isAnalyzingImage &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.RESPONSE_GENERATION &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.COMPLETED &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.FAILED &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.CANCELLED &&
                                        uiState.streamingMessageText.isEmpty()
                                    MessageBubbleItem(
                                        message = ChatMessage(
                                            id = "pending_assistant",
                                            conversationId = uiState.activeConversationId.orEmpty(),
                                            userId = uiState.activeUserId.orEmpty(),
                                            role = MessageRole.ASSISTANT,
                                            content = uiState.streamingMessageText,
                                            modelId = uiState.selectedModelId,
                                            status = MessageStatus.STREAMING
                                        ),
                                        renderMarkdown = uiState.preferences.renderMarkdown,
                                        isGenerating = true,
                                        isAnalyzingImage = showPendingImageAnalysis,
                                        liveActivityStatus = uiState.activeLiveDataActivityStatus,
                                        liveMarketPayload = null,
                                        canRegenerate = false,
                                        onEditUserMessage = {},
                                        onRegenerate = onRegenerateResponse
                                    )
                                }
                            }
                        }

                        // Floating "Jump to Latest" button when user has scrolled upward
                        if (!isAtBottom && uiState.messages.isNotEmpty()) {
                            Surface(
                                onClick = {
                                    isAutoScrollLockedToBottom = true
                                    coroutineScope.launch {
                                        isProgrammaticScroll = true
                                        try {
                                            listState.scrollToBottomEdge(
                                                itemCount = uiState.messages.size,
                                                animate = true
                                            )
                                        } finally {
                                            isProgrammaticScroll = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                                    .testTag("scroll_to_bottom_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = "Scroll to latest message",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Latest",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Message Composer Bar (no duplicate navigationBarsPadding so keyboard opening has zero black gap)
                ChatComposerBar(
                    composerText = uiState.composerText,
                    selectedModel = uiState.selectedModel,
                    pendingAttachments = uiState.pendingAttachments,
                    editingMessageId = uiState.editingMessageId,
                    isGenerating = uiState.isGenerating,
                    enterToSend = uiState.preferences.enterToSend,
                    onTextChanged = onComposerTextChanged,
                    onPickImage = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemoveAttachment = onRemoveAttachment,
                    onCancelEditing = onCancelEditingMessage,
                    onSendMessage = onSendMessage,
                    onStopGeneration = onStopGeneration,
                    onOpenVoiceChat = onOpenVoiceChat,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (uiState.isModelSelectorSheetOpen) {
        ModelSelectorBottomSheet(
            catalog = uiState.modelCatalog,
            selectedModelId = uiState.selectedModelId,
            searchQuery = uiState.modelSearchQuery,
            selectedCategory = uiState.selectedModelCategory,
            isRefreshing = uiState.isRefreshingCatalog,
            onSearchQueryChanged = onModelSearchQueryChanged,
            onCategorySelected = onModelCategorySelected,
            onRefreshCatalog = onRefreshModelCatalog,
            onModelSelected = onSelectModel,
            onDismiss = onCloseModelSelector
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewChatWelcomeView(
    selectedModelName: String,
    selectedModelId: String,
    recentConversations: List<com.example.domain.models.Conversation>,
    onOpenModelSelector: () -> Unit,
    onSuggestionClicked: (String) -> Unit,
    onSelectRecentConversation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.testTag("new_chat_welcome_view"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Text(
                    text = "How can I help you today?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Surface(
                    onClick = onOpenModelSelector,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = "Active model: $selectedModelName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        // Prompt Suggestion Shortcuts
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                WELCOME_SUGGESTIONS.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { item ->
                            Surface(
                                onClick = { onSuggestionClicked(item.promptText) },
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("prompt_suggestion_${item.title.lowercase().replace(" ", "_")}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Recent Conversations Strip (when available)
        if (recentConversations.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Recent Conversations",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    recentConversations.forEach { conv ->
                        Surface(
                            onClick = { onSelectRecentConversation(conv.id) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = AiModelCatalog.getDisplayName(conv.selectedModelId),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    renderMarkdown: Boolean,
    isGenerating: Boolean,
    isAnalyzingImage: Boolean = false,
    liveActivityStatus: String? = null,
    liveMarketPayload: LiveMarketDataPayload? = null,
    canRegenerate: Boolean,
    onEditUserMessage: () -> Unit,
    onRegenerate: () -> Unit,
    onRefreshLiveMarketData: () -> Unit = {},
    onOpenGeneratedImage: (MessageAttachment) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    if (message.role == MessageRole.USER) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("user_message_item_${message.id}"),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 4.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.widthIn(max = 520.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (message.attachments.isNotEmpty()) {
                        Text(
                            text = "Attached ${message.attachments.size} image(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp, end = 4.dp)
            ) {
                Text(
                    text = timeFormatter.format(Date(message.createdAtEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onEditUserMessage,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("edit_user_message_btn_${message.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit and resubmit message",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    } else {
        // Assistant Message
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("assistant_message_item_${message.id}"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Model Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                Text(
                    text = AiModelCatalog.getDisplayName(message.modelId),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.fallbackUsed) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = StatusWarningAmber.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "Fallback Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusWarningAmber,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!isAnalyzingImage && (message.status == MessageStatus.STREAMING || isGenerating)) {
                    StreamingPulseIndicator()
                }
            }

            if (!liveActivityStatus.isNullOrBlank() && message.content.isBlank()) {
                LiveDataActivityStatusIndicator(
                    statusText = liveActivityStatus,
                    modifier = Modifier.padding(start = 32.dp)
                )
            } else if (isAnalyzingImage && message.content.isBlank()) {
                ImageAnalysisStatusIndicator(
                    modifier = Modifier.padding(start = 32.dp)
                )
            }

            // Interactive Live Market Data & Chart Card (Precious Metals, Crypto, Forex, Currency Conversion, Stocks)
            if (liveMarketPayload != null) {
                LiveMarketChartCard(
                    payload = liveMarketPayload,
                    onRefreshRequested = onRefreshLiveMarketData,
                    modifier = Modifier.padding(start = 32.dp, bottom = 4.dp)
                )
            }

            // Message Body
            if (message.content.isNotBlank()) {
                MarkdownMessageContent(
                    content = message.content,
                    renderMarkdown = renderMarkdown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp)
                )
            } else if (!isAnalyzingImage && liveActivityStatus.isNullOrBlank() && (message.status == MessageStatus.STREAMING || isGenerating)) {
                Text(
                    text = "Generating response…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 32.dp)
                )
            }

            // Generated Image Attachments inside Assistant Chat Bubble
            if (message.attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    message.attachments.forEach { attachment ->
                        val imageBytes = remember(attachment.id, attachment.uriString, attachment.base64DataUrl) {
                            GeneratedImageBinaryCache.getBytes(
                                attachmentId = attachment.id,
                                uriString = attachment.uriString,
                                base64DataUrl = attachment.base64DataUrl
                            )
                        }
                        val bitmap = remember(imageBytes) {
                            imageBytes?.let {
                                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenGeneratedImage(attachment) }
                                .testTag("chat_generated_image_card_${attachment.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (bitmap != null) {
                                    val ratio = if (bitmap.width > 0 && bitmap.height > 0) {
                                        bitmap.width.toFloat() / bitmap.height.toFloat()
                                    } else {
                                        1f
                                    }
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = message.content,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(ratio.coerceIn(0.55f, 1.8f))
                                            .clip(RoundedCornerShape(12.dp))
                                            .testTag("chat_generated_image_preview")
                                    )
                                }
                                if (imageBytes != null) {
                                    val imgObj = remember(attachment.id, imageBytes) {
                                        GeneratedImageResult(
                                            id = attachment.id,
                                            prompt = message.content,
                                            modelId = message.modelId,
                                            width = bitmap?.width ?: 1024,
                                            height = bitmap?.height ?: 1024,
                                            seed = 42L,
                                            imageBytes = imageBytes,
                                            mimeType = attachment.mimeType,
                                            imageUrl = attachment.uriString,
                                            localFilePath = attachment.uriString
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    saveGeneratedImageToGallery(context, imgObj)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("chat_save_generated_image_button")
                                        ) {
                                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Save")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    shareGeneratedImage(context, imgObj)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("chat_share_generated_image_button")
                                        ) {
                                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Share")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Interrupted or Error State Notice inside the message
            if (message.status == MessageStatus.INTERRUPTED || message.status == MessageStatus.ERROR) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StatusErrorRed.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .padding(start = 32.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = message.errorMessage ?: if (message.status == MessageStatus.INTERRUPTED) {
                                "Response generation was interrupted."
                            } else {
                                "Failed to generate response."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusErrorRed,
                            modifier = Modifier.weight(1f)
                        )
                        if (canRegenerate) {
                            TextButton(onClick = onRegenerate) {
                                Text("Retry", color = StatusErrorRed)
                            }
                        }
                    }
                }
            }

            // Action Toolbar (Regenerate & Timestamp only — Copy buttons are rendered inside structured code/script/command blocks)
            if (message.status != MessageStatus.STREAMING) {
                Row(
                    modifier = Modifier.padding(start = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (canRegenerate) {
                        IconButton(
                            onClick = onRegenerate,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("regenerate_assistant_response_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Regenerate response",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = timeFormatter.format(Date(message.createdAtEpochMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingPulseIndicator() {
    val transition = rememberInfiniteTransition(label = "streaming_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .alpha(alpha)
            .testTag("streaming_response_indicator")
    ) {
        Surface(
            shape = CircleShape,
            color = StatusAvailableGreen,
            modifier = Modifier.size(6.dp)
        ) {}
        Text(
            text = "Streaming",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ImageAnalysisStatusIndicator(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "image_analysis_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "image_analysis_alpha"
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = modifier.testTag("image_analysis_status_indicator")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(16.dp)
                    .alpha(alpha)
            )
            Text(
                text = "Analyzing Provided Image",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LiveDataActivityStatusIndicator(
    statusText: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "live_data_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_data_alpha"
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = modifier.testTag("live_data_activity_indicator")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
            ) {}
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ChatComposerBar(
    composerText: String,
    selectedModel: com.example.domain.models.AiModelDefinition,
    pendingAttachments: List<MessageAttachment>,
    editingMessageId: String?,
    isGenerating: Boolean,
    enterToSend: Boolean,
    onTextChanged: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onCancelEditing: () -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    onOpenVoiceChat: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    // Editing Message Banner
                    if (editingMessageId != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Editing message (subsequent replies will be regenerated)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = onCancelEditing,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Cancel editing",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    // Pending Attachments Row
                    if (pendingAttachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pendingAttachments.forEach { att ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = att.fileName,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                        IconButton(
                                            onClick = { onRemoveAttachment(att.id) },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Remove attachment",
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Attachment Button (Enabled when selected model supports Vision)
                        if (selectedModel.supportsVisionInput) {
                            IconButton(
                                onClick = onPickImage,
                                enabled = !isGenerating,
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("attach_image_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AttachFile,
                                    contentDescription = "Attach image for Vision model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // Multiline Text Input
                        OutlinedTextField(
                            value = composerText,
                            onValueChange = { newText ->
                                if (enterToSend && newText.endsWith("\n") && !isGenerating) {
                                    onTextChanged(newText.trimEnd('\n'))
                                    onSendMessage()
                                } else {
                                    onTextChanged(newText)
                                }
                            },
                            placeholder = {
                                Text(
                                    text = "Message ${AiModelCatalog.formatUserFacingModelName(selectedModel.displayName)}…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (!isGenerating) onSendMessage()
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_composer_input")
                        )

                        // Dedicated GPT 6 Luna Voice Chat shortcut button when composer is idle
                        if (!isGenerating && composerText.isBlank() && pendingAttachments.isEmpty()) {
                            IconButton(
                                onClick = onOpenVoiceChat,
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("composer_voice_chat_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.RecordVoiceOver,
                                    contentDescription = "Talk with GPT 6 Luna Voice Chat",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        // Send or Stop Generation Button
                        if (isGenerating) {
                            IconButton(
                                onClick = onStopGeneration,
                                modifier = Modifier
                                    .size(46.dp)
                                    .testTag("stop_generation_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.StopCircle,
                                    contentDescription = "Stop generating",
                                    tint = StatusErrorRed,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        } else {
                            val canSend = composerText.isNotBlank() || pendingAttachments.isNotEmpty()
                            Button(
                                onClick = onSendMessage,
                                enabled = canSend,
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("send_message_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun LazyListState.scrollToBottomEdge(itemCount: Int, animate: Boolean) {
    val total = maxOf(layoutInfo.totalItemsCount, itemCount)
    if (total <= 0) return
    val lastIndex = total - 1
    val lastItemInfo = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset

    if (lastItemInfo != null && viewportHeight > 0) {
        val targetOffset = (lastItemInfo.size - viewportHeight + 48).coerceAtLeast(0)
        if (animate) {
            animateScrollToItem(lastIndex, targetOffset)
        } else {
            scrollToItem(lastIndex, targetOffset)
        }
    } else {
        if (animate) {
            animateScrollToItem(lastIndex)
        } else {
            scrollToItem(lastIndex)
        }
        val updatedLast = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        val updatedViewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        if (updatedLast != null && updatedViewport > 0 && updatedLast.size > updatedViewport) {
            val offset = (updatedLast.size - updatedViewport + 48).coerceAtLeast(0)
            scrollToItem(lastIndex, offset)
        }
    }
}

