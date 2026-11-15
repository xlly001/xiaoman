package dev.xiaoman.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.xiaoman.app.AppGraph
import dev.xiaoman.app.R
import dev.xiaoman.app.session.AgentSession
import java.io.ByteArrayOutputStream

/** Height the merged composer + navigation dock occupies, including margins. */
internal val DockSpace = 116.dp

private data class Attachment(val name: String, val bytes: ByteArray?)

private const val MAX_FILE_BYTES = 20_000
private const val MAX_IMAGE_EDGE = 1280

/**
 * One floating dock shared by every tab: a compact composer on top and the navigation row below.
 * The area around the card stays transparent so page content remains visible behind it.
 */
@Composable
fun ChatDock(
    graph: AppGraph,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSent: () -> Unit,
    keyboardOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    val session = graph.session
    val busy by session.busy.collectAsStateWithLifecycle()
    val question by session.question.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val attachments = remember { mutableStateListOf<Attachment>() }
    var addMenu by remember { mutableStateOf(false) }

    fun send(text: String, images: List<ByteArray>) {
        when (session.submit(text, images)) {
            AgentSession.Submit.Busy -> Toast.makeText(context, R.string.session_busy, Toast.LENGTH_SHORT).show()
            AgentSession.Submit.Empty -> Unit
            else -> {
                attachments.clear()
                onDraftChange("")
                focus.clearFocus()
                keyboard?.hide()
                onSent()
            }
        }
    }

    fun imageBytes(): List<ByteArray> = attachments.mapNotNull { it.bytes }

    val voice = rememberVoiceInput { recognized ->
        val message = if (draft.isBlank()) recognized else draft.trimEnd() + " " + recognized
        if (settings.voiceAutoSend) {
            send(message, imageBytes())
        } else {
            onDraftChange(message)
        }
    }
    LaunchedEffect(voice.errorRes) {
        voice.errorRes?.let {
            Toast.makeText(context, context.getString(it), Toast.LENGTH_SHORT).show()
            voice.clearError()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bytes = loadImage(context, uri)
        if (bytes == null) {
            Toast.makeText(context, R.string.attach_image_failed, Toast.LENGTH_SHORT).show()
        } else {
            attachments += Attachment(context.getString(R.string.attach_image_name), bytes)
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            attachments += Attachment(context.getString(R.string.attach_photo_name), bitmap.toPng())
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val loaded = readTextFile(context, uri)
        if (loaded == null) {
            Toast.makeText(context, R.string.attach_file_failed, Toast.LENGTH_SHORT).show()
        } else {
            val (name, text) = loaded
            val prefix = if (draft.isBlank()) "" else draft.trimEnd() + "\n\n"
            onDraftChange(prefix + "[" + name + "]\n" + text)
        }
    }

    val canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && (!busy || question != null)

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEachIndexed { index, attachment ->
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    attachment.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(onClick = { attachments.removeAt(index) }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.attach_remove),
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { addMenu = true }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.attach_add),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_camera)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            onClick = { addMenu = false; camera.launch(null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_gallery)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                addMenu = false
                                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_file)) },
                            leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                            onClick = {
                                addMenu = false
                                files.launch(arrayOf("text/*", "application/json", "application/xml"))
                            },
                        )
                    }
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f).heightIn(min = 38.dp, max = 96.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) send(draft, imageBytes()) }),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) {
                                Text(
                                    stringResource(
                                        when {
                                            voice.listening -> R.string.chat_listening
                                            question != null -> R.string.chat_answer_hint
                                            else -> R.string.chat_input_hint
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (busy && question == null) {
                    IconButton(onClick = session::stop, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    if (canSend) {
                        IconButton(
                            onClick = { send(draft, imageBytes()) },
                            modifier = Modifier.size(38.dp),
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = stringResource(R.string.chat_send),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (voice.listening) voice.stop() else voice.start() },
                        enabled = !busy || question != null,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            if (voice.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = stringResource(if (voice.listening) R.string.chat_stop_listening else R.string.chat_mic),
                            tint = if (voice.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }
            if (!keyboardOpen) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tab.entries.filter { it != Tab.SETTINGS }.forEach { item ->
                        val active = item == selected
                        val background by animateColorAsState(
                            if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            label = "dock-nav-background",
                        )
                        val tint by animateColorAsState(
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "dock-nav-tint",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(background)
                                .selectable(selected = active, onClick = { onSelect(item) }, role = Role.Tab)
                                .padding(vertical = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(item.label),
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun loadImage(context: Context, uri: Uri): ByteArray? = runCatching {
    val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) ?: return null
    bitmap.toPng()
}.getOrNull()

private fun Bitmap.toPng(): ByteArray {
    val scale = (MAX_IMAGE_EDGE.toFloat() / maxOf(width, height, 1)).coerceAtMost(1f)
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        this
    }
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

/** Text files are inlined into the message; images go through the [loadImage] path instead. */
private fun readTextFile(context: Context, uri: Uri): Pair<String, String>? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        ?: "file.txt"
    val input = context.contentResolver.openInputStream(uri) ?: return null
    val buffer = ByteArrayOutputStream()
    input.use { stream ->
        val chunk = ByteArray(8192)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > MAX_FILE_BYTES) return null
        }
    }
    val bytes = buffer.toByteArray()
    if (bytes.any { it == 0.toByte() }) return null
    name to bytes.toString(Charsets.UTF_8)
}.getOrNull()
