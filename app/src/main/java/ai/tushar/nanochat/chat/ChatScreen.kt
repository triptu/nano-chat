package ai.tushar.nanochat.chat

import ai.tushar.nanochat.attachments.DraftAttachment
import ai.tushar.nanochat.data.Agent
import ai.tushar.nanochat.data.Attachment
import ai.tushar.nanochat.data.AttachmentKind
import ai.tushar.nanochat.gate.GateActions
import ai.tushar.nanochat.gate.ModelGate
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import java.io.File
import ai.tushar.nanochat.data.Message
import ai.tushar.nanochat.data.Role
import ai.tushar.nanochat.devtools.ChatInternalsSheet
import ai.tushar.nanochat.devtools.DevOnly
import ai.tushar.nanochat.devtools.ReplyStats
import ai.tushar.nanochat.ui.Ico
import ai.tushar.nanochat.ui.Icons
import ai.tushar.nanochat.ui.mascot.Blob
import ai.tushar.nanochat.ui.mascot.BlobAvatar
import ai.tushar.nanochat.ui.mascot.ThinkingDots
import ai.tushar.nanochat.ui.theme.LocalChatColors
import ai.tushar.nanochat.ui.theme.tone
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val BubbleMaxWidth = 560.dp
private const val BubbleMaxFraction = 0.82f
private val BubbleRadius = 20.dp
private val BubbleTail = 6.dp
private val AvatarSize = 30.dp
private val AvatarGutter = AvatarSize + 8.dp

/** Actions on the agent itself, handled by the shell because they can close this screen. */
data class AgentActions(
    val onEdit: (Agent) -> Unit,
    val onClear: (Agent) -> Unit,
    val onDelete: (Agent) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, showBack: Boolean, onBack: () -> Unit, actions: AgentActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val agent = state.agent
    val dark = isSystemInDarkTheme()
    val tint = agent?.color?.tone(dark) ?: MaterialTheme.colorScheme.primary
    var showInternals by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Ico(Icons.Back, "Back") }
                },
                title = {
                    if (agent != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BlobAvatar(tint, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(agent.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val waking = state.model?.let { it.ready && it.info.loading } == true
                                val status = when {
                                    state.pending?.thinking == true -> "thinking…"
                                    state.pending != null -> "typing…"
                                    waking -> "waking up…"
                                    else -> "on your phone"
                                }
                                AnimatedContent(status, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "status") {
                                    Text(
                                        it,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (state.pending != null || waking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    DevOnly {
                        IconButton(onClick = { showInternals = true }) { Ico(Icons.Info, "Chat internals") }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Ico(Icons.More, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Edit ${agent?.name.orEmpty()}") },
                                leadingIcon = { Ico(Icons.Edit, null, size = 20.dp) },
                                onClick = { menuOpen = false; agent?.let(actions.onEdit) },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear chat") },
                                leadingIcon = { Ico(Icons.Clear, null, size = 20.dp) },
                                enabled = state.messages.isNotEmpty(),
                                onClick = { menuOpen = false; confirm = Confirm.Clear },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete ${agent?.name.orEmpty()}") },
                                leadingIcon = { Ico(Icons.Delete, null, size = 20.dp) },
                                onClick = { menuOpen = false; confirm = Confirm.Delete },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        ) {
            val model = state.model
            if (agent == null || model == null) {
                Spacer(Modifier.weight(1f))
                return@Column
            }
            // The gate stands in for the messages and composer until the companion's model can answer.
            AnimatedContent(
                targetState = model.ready,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "gate",
            ) { ready ->
                if (!ready) {
                    ModelGate(
                        agentName = agent.name,
                        tint = tint,
                        id = model.id,
                        state = model.state,
                        info = model.info,
                        actions = GateActions(
                            onDownload = { vm.download(model.id) },
                            onCancel = { vm.cancelDownload(model.id) },
                            onRetry = { vm.recheck(model.id) },
                            onUseOther = { vm.useModel(it) },
                            onUseFake = { vm.useFakeModels() },
                        ),
                    )
                } else {
                    ChatBody(vm, state, agent, model, tint, onOpenPhoto = { viewing = it })
                }
            }
        }
    }

    viewing?.let { name -> PhotoViewer(vm.file(name), onClose = { viewing = null }) }

    if (showInternals) {
        val generation by vm.lastGeneration.collectAsStateWithLifecycle()
        val infos by vm.modelInfos.collectAsStateWithLifecycle()
        state.model?.let { ChatInternalsSheet(generation, it.info, infos, onDismiss = { showInternals = false }) }
    }

    confirm?.let { c ->
        val name = agent?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (c == Confirm.Clear) "Clear this chat?" else "Delete $name?") },
            text = {
                Text(
                    if (c == Confirm.Clear) "All messages with $name will be deleted. $name stays in your list. This can't be undone."
                    else "$name and your chat will be deleted. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        agent?.let { if (c == Confirm.Clear) actions.onClear(it) else actions.onDelete(it) }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(if (c == Confirm.Clear) "Clear chat" else "Delete $name") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private enum class Confirm { Clear, Delete }

/** Messages and composer, once the model is ready. */
@Composable
private fun ChatBody(vm: ChatViewModel, state: ChatState, agent: Agent, model: ChatModel, tint: Color, onOpenPhoto: (String) -> Unit) {
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    var denied by rememberSaveable { mutableStateOf<Denied?>(null) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loaded && state.messages.isEmpty() && state.pending == null) {
                EmptyChat(agent, tint)
            } else {
                MessageList(state, agent, tint, vm::file, onOpenPhoto)
            }
        }
        AnimatedVisibility(state.problem != null) {
            state.problem?.let { ProblemCard(it.message, it.canRetry, onRetry = vm::retry) }
        }
        AnimatedVisibility(vm.attachProblem != null) {
            vm.attachProblem?.let { ProblemCard(it.message, canRetry = false, onRetry = {}, onDismiss = { vm.attachProblem = null }) }
        }
        AnimatedVisibility(denied != null) {
            denied?.let { PermissionCard(it, onDismiss = { denied = null }) }
        }
        val media = model.media
        val photos = drafts.count { it.kind == AttachmentKind.Image }
        val notes = drafts.count { it.kind == AttachmentKind.Audio }
        val rec = recording
        if (rec != null) {
            RecordingBar(rec, onCancel = vm::cancelRecording, onStop = { vm.stopRecording() }, onSend = { vm.stopRecording(andSend = true) })
        } else {
            Composer(
                value = vm.draft,
                onValueChange = { vm.draft = it },
                placeholder = "Message ${agent.name}",
                busy = state.pending != null,
                onSend = vm::send,
                onStop = vm::stop,
                drafts = drafts,
                fileOf = vm::file,
                onRemoveDraft = vm::removeDraft,
                attach = if (media.images || media.audio) {
                    {
                        AttachButton(
                            options = AttachOptions(
                                photosLeft = (media.maxImages - photos).coerceAtLeast(0),
                                maxPhotos = media.maxImages,
                                voiceNote = if (media.audio) notes < media.maxAudioClips else null,
                            ),
                            onPhotos = { uris, then -> vm.addPhotos(uris, then) },
                            onRecord = vm::startRecording,
                            onDenied = { denied = it },
                        )
                    }
                } else null,
            )
        }
    }
}

private sealed interface ChatRow {
    val key: Any
    data class Bubble(val message: Message, val lastInGroup: Boolean, val firstInGroup: Boolean) : ChatRow {
        override val key: Any get() = message.id
    }
    data class Pending(val text: String) : ChatRow {
        override val key: Any get() = "pending"
    }
}

private fun rows(messages: List<Message>, pending: String?): List<ChatRow> {
    val out = ArrayList<ChatRow>(messages.size + 1)
    if (pending != null) out += ChatRow.Pending(pending)
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        val next = messages.getOrNull(i + 1)
        val prev = messages.getOrNull(i - 1)
        val nextRole = next?.role ?: if (pending != null) Role.Agent else null
        out += ChatRow.Bubble(m, lastInGroup = nextRole != m.role, firstInGroup = prev?.role != m.role)
    }
    return out
}

@Composable
private fun MessageList(state: ChatState, agent: Agent, tint: Color, fileOf: (String) -> File, onOpenPhoto: (String) -> Unit) {
    val listState = rememberLazyListState()
    val voice = rememberVoicePlayer()
    val items = remember(state.messages, state.pending) { rows(state.messages, state.pending?.text) }
    val lastId = state.messages.lastOrNull()?.id
    LaunchedEffect(lastId, state.pending?.thinking) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxBubble = minOf(BubbleMaxWidth, maxWidth * BubbleMaxFraction)
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            itemsIndexed(items, key = { _, r -> r.key }) { _, row ->
                when (row) {
                    is ChatRow.Pending -> AgentLine(tint, showAvatar = true, topGap = 10.dp, maxBubble = maxBubble) {
                        if (row.text.isEmpty()) {
                            Bubble(Role.Agent, tail = true) { ThinkingDots(tint, Modifier.padding(vertical = 6.dp, horizontal = 2.dp)) }
                        } else {
                            Bubble(Role.Agent, tail = true) { BubbleText(row.text, Role.Agent) }
                        }
                    }
                    is ChatRow.Bubble -> {
                        val gap = if (row.firstInGroup) 10.dp else 2.dp
                        if (row.message.role == Role.User) {
                            UserMessage(row, state.attachments[row.message.id].orEmpty(), gap, maxBubble, fileOf, voice, onOpenPhoto)
                        } else {
                            Column {
                                AgentLine(tint, showAvatar = row.lastInGroup, topGap = gap, maxBubble = maxBubble) {
                                    Bubble(Role.Agent, tail = row.lastInGroup, speech = "${agent.name}: ${row.message.text}") { BubbleText(row.message.text, Role.Agent) }
                                }
                                DevOnly { ReplyStats(row.message, Modifier.padding(start = AvatarGutter + 6.dp, top = 3.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A user message: photos, then voice notes, then the text bubble. Media-only messages have no text bubble. */
@Composable
private fun UserMessage(
    row: ChatRow.Bubble,
    media: List<Attachment>,
    gap: Dp,
    maxBubble: Dp,
    fileOf: (String) -> File,
    voice: VoicePlayer,
    onOpenPhoto: (String) -> Unit,
) {
    val text = row.message.text
    val photos = media.filter { it.kind == AttachmentKind.Image }
    val notes = media.filter { it.kind == AttachmentKind.Audio }
    Column(
        Modifier.fillMaxWidth().padding(top = gap),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (photos.isNotEmpty()) PhotoGroup(photos, fileOf, maxBubble) { onOpenPhoto(it.file) }
        notes.forEachIndexed { i, a ->
            val tail = row.lastInGroup && text.isEmpty() && i == notes.lastIndex
            VoicePill(a, fileOf(a.file), voice, bubbleShape(mine = true, tail = tail))
        }
        if (text.isNotEmpty()) {
            Box(Modifier.widthIn(max = maxBubble)) {
                Bubble(Role.User, tail = row.lastInGroup, speech = "You: $text") { BubbleText(text, Role.User) }
            }
        }
    }
}

@Composable
private fun AgentLine(
    tint: Color,
    showAvatar: Boolean,
    topGap: Dp,
    maxBubble: Dp,
    content: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = topGap), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(AvatarSize), contentAlignment = Alignment.BottomCenter) {
            if (showAvatar) Blob(tint, AvatarSize)
        }
        Spacer(Modifier.width(AvatarGutter - AvatarSize))
        Box(Modifier.widthIn(max = maxBubble - AvatarGutter)) { content() }
    }
}

@Composable
private fun Bubble(role: Role, tail: Boolean, speech: String? = null, content: @Composable () -> Unit) {
    val colors = LocalChatColors.current
    val mine = role == Role.User
    val shape = bubbleShape(mine, tail)
    Surface(
        color = if (mine) colors.userBubble else colors.agentBubble,
        contentColor = if (mine) colors.onUserBubble else colors.onAgentBubble,
        shape = shape,
        shadowElevation = if (mine) 0.dp else 0.5.dp,
        modifier = if (speech != null) Modifier.semantics(mergeDescendants = true) { contentDescription = speech } else Modifier,
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) { content() }
    }
}

private fun bubbleShape(mine: Boolean, tail: Boolean): RoundedCornerShape {
    val small = if (tail) BubbleTail else BubbleRadius
    return RoundedCornerShape(
        topStart = BubbleRadius, topEnd = BubbleRadius,
        bottomStart = if (mine) BubbleRadius else small,
        bottomEnd = if (mine) small else BubbleRadius,
    )
}

@Composable
private fun BubbleText(text: String, role: Role) {
    SelectionContainer {
        Text(if (role == Role.Agent) formatReply(text) else AnnotatedString(text), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Light markdown: **bold** and "* " / "- " bullets, which Gemini Nano likes to use. */
internal fun formatReply(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, rawLine ->
        if (i > 0) append('\n')
        val line = Regex("^(\\s*)[*-] ").replace(rawLine) { "${it.groupValues[1]}• " }
            .let { Regex("^#{1,6} ").replace(it, "") }
        var rest = line
        while (true) {
            val start = rest.indexOf("**")
            val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
            if (start < 0 || end < 0) { append(rest); break }
            append(rest.substring(0, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(start + 2, end)) }
            rest = rest.substring(end + 2)
        }
    }
}

@Composable
private fun EmptyChat(agent: Agent, tint: Color) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Blob(tint, 112.dp)
        Spacer(Modifier.height(16.dp))
        Text("Say hi to ${agent.name}", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Everything you say stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProblemCard(message: String, canRetry: Boolean, onRetry: () -> Unit, onDismiss: (() -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
            if (canRetry) TextButton(onClick = onRetry) { Text("Try again") }
            if (onDismiss != null) IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) { Ico(Icons.Close, "Dismiss", size = 18.dp) }
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    drafts: List<DraftAttachment>,
    fileOf: (String) -> File,
    onRemoveDraft: (DraftAttachment) -> Unit,
    /** The attach button, or null when the model takes text only. */
    attach: (@Composable () -> Unit)?,
) {
    val colors = LocalChatColors.current
    Row(
        Modifier.fillMaxWidth().padding(start = if (attach != null) 4.dp else 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        attach?.invoke()
        Surface(
            color = colors.agentBubble,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.weight(1f),
        ) {
            Column {
            if (drafts.isNotEmpty()) DraftStrip(drafts, fileOf, onRemoveDraft)
            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                            }
                            inner()
                        }
                    },
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onAgentBubble),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                )
            }
            }
        }
        Spacer(Modifier.width(6.dp))
        val canSend = value.isNotBlank() || drafts.isNotEmpty()
        FilledIconButton(
            onClick = if (busy) onStop else onSend,
            enabled = busy || canSend,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colors.userBubble,
                contentColor = colors.onUserBubble,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            if (busy) Ico(Icons.Stop, "Stop", size = 18.dp) else Ico(Icons.Send, "Send", size = 22.dp)
        }
    }
}

