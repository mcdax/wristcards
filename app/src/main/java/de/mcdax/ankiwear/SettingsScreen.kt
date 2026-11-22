package de.mcdax.ankiwear

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.RadioButtonDefaults
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.SwitchButtonDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.composable
import anki.decks.DeckTreeNode
import kotlinx.coroutines.launch

/**
 * Alle Einstellungsseiten als Navigationsziele unter „settings…“.
 * MainActivity hängt sie mit einer Zeile in ihren SwipeDismissableNavHost.
 */
fun NavGraphBuilder.settingsGraph(nav: NavHostController, repo: AnkiRepository, decks: () -> List<DeckTreeNode>) {
    composable("settings") { SettingsScreen(repo, onNavigate = { nav.navigate(it) }) }
    composable("settings/choice/{key}") { entry ->
        val choice = choices.firstOrNull { it.key == entry.arguments?.getString("key") } ?: return@composable
        ChoiceScreen(choice, onDone = { nav.popBackStack() })
    }
    composable("settings/account") { AccountScreen(repo, onDone = { nav.popBackStack() }) }
    composable("settings/decks/hidden") { DeckPickScreen(decks = decks()) }
}

/** Einstellung mit festen Auswahlwerten; der Wert steht als zweite Zeile unter dem Titel. */
private class Choice<T>(
    val key: String,
    @StringRes val title: Int,
    val options: List<Pair<T, Int>>,
    val get: (AppSettings) -> T,
    val set: (AppSettings, T) -> AppSettings,
) {
    fun label(s: AppSettings) = options.first { it.first == get(s) }.second
}

private val fontSizeChoice = Choice(
    "font", R.string.set_font_size,
    listOf(FontSize.SMALL to R.string.opt_small, FontSize.NORMAL to R.string.opt_normal, FontSize.LARGE to R.string.opt_large),
    { it.fontSize }, { s, v -> s.copy(fontSize = v) },
)
private val extraChoice = Choice(
    "extra", R.string.set_extra,
    listOf(ExtraMode.SHOW to R.string.opt_show, ExtraMode.COLLAPSED to R.string.opt_collapsed, ExtraMode.HIDE to R.string.opt_hide),
    { it.extraMode }, { s, v -> s.copy(extraMode = v) },
)
private val audioChoice = Choice(
    "audio", R.string.set_audio,
    listOf(AudioMode.AUTO to R.string.opt_auto, AudioMode.ON_TAP to R.string.opt_on_tap),
    { it.audioMode }, { s, v -> s.copy(audioMode = v) },
)
private val audioOutputChoice = Choice(
    "audio_out", R.string.set_audio_output,
    listOf(AudioOutput.NEVER to R.string.opt_never, AudioOutput.BLUETOOTH_ONLY to R.string.opt_bt_only, AudioOutput.ALWAYS to R.string.opt_always),
    { it.audioOutput }, { s, v -> s.copy(audioOutput = v) },
)
private val buttonsChoice = Choice(
    "buttons", R.string.set_buttons,
    listOf(false to R.string.opt_four_buttons, true to R.string.opt_two_buttons),
    { it.twoButtons }, { s, v -> s.copy(twoButtons = v) },
)
private val mediaChoice = Choice(
    "media", R.string.set_media,
    listOf(MediaMode.ALL to R.string.opt_all, MediaMode.NONE to R.string.opt_none),
    { it.mediaMode }, { s, v -> s.copy(mediaMode = v) },
)
private val choices: List<Choice<*>> =
    listOf(fontSizeChoice, extraChoice, audioChoice, audioOutputChoice, buttonsChoice, mediaChoice)

@Composable
private fun SettingsScreen(repo: AnkiRepository, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val s by store.flow.collectAsState()
    // Bewusst ohne remember: beim Zurückkehren von der Anmeldeseite frisch lesen.
    val account = repo.account

    SettingsList {
        item { Header(R.string.settings) }

        item { Group(R.string.group_study) }
        item { ChoiceRow(fontSizeChoice, s, onNavigate) }
        item { ChoiceRow(extraChoice, s, onNavigate) }
        item { ChoiceRow(audioOutputChoice, s, onNavigate) }
        if (s.audioOutput != AudioOutput.NEVER) item { ChoiceRow(audioChoice, s, onNavigate) }
        item { Toggle(R.string.set_vibrate_rate, s.vibrateOnRate) { v -> store.update { it.copy(vibrateOnRate = v) } } }
        item { Toggle(R.string.set_vibrate_done, s.vibrateOnDeckDone) { v -> store.update { it.copy(vibrateOnDeckDone = v) } } }
        item {
            Toggle(R.string.set_swipe, s.swipeToRate, R.string.set_swipe_hint) { v -> store.update { it.copy(swipeToRate = v) } }
        }
        if (s.swipeToRate) item {
            Toggle(R.string.set_swipe_feedback, s.swipeFeedback, R.string.set_swipe_feedback_hint) { v -> store.update { it.copy(swipeFeedback = v) } }
        }
        item { ChoiceRow(buttonsChoice, s, onNavigate) }
        item { Toggle(R.string.set_intervals, s.showIntervals) { v -> store.update { it.copy(showIntervals = v) } } }
        item { Toggle(R.string.set_keep_screen_on, s.keepScreenOn) { v -> store.update { it.copy(keepScreenOn = v) } } }

        item { Group(R.string.group_sync) }
        item { Toggle(R.string.set_sync_open, s.syncOnOpen) { v -> store.update { it.copy(syncOnOpen = v) } } }
        item { Toggle(R.string.set_sync_exit, s.syncOnDeckExit) { v -> store.update { it.copy(syncOnDeckExit = v) } } }
        if (!s.syncOnOpen && !s.syncOnDeckExit) item { Note(R.string.set_sync_manual_hint) }
        item { Toggle(R.string.set_wifi_only, s.wifiOnly, R.string.set_wifi_only_hint) { v -> store.update { it.copy(wifiOnly = v) } } }
        item { ChoiceRow(mediaChoice, s, onNavigate) }
        item {
            val label = when (account) {
                is Account.SignedIn -> account.user
                Account.BuiltIn -> stringResource(R.string.account_built_in)
                Account.SignedOut -> stringResource(R.string.account_signed_out)
            }
            NavRow(R.string.set_account, label) { onNavigate("settings/account") }
        }

        item { Group(R.string.group_decks) }
        item { Toggle(R.string.set_hide_empty, s.hideEmptyDecks) { v -> store.update { it.copy(hideEmptyDecks = v) } } }
        item {
            // Neuer Standard: einzeln umgeklappte Decks folgen wieder dem Standard.
            Toggle(R.string.set_collapse, s.collapseSubdecks) { v -> store.update { it.copy(collapseSubdecks = v, toggledDecks = emptySet()) } }
        }
        if (s.hiddenDecks.isNotEmpty()) {
            item {
                NavRow(R.string.set_hidden_decks, stringResource(R.string.deck_count, s.hiddenDecks.size)) { onNavigate("settings/decks/hidden") }
            }
        }
        item { Note(R.string.set_long_press_hint) }
    }
}

@Composable
private fun ChoiceScreen(choice: Choice<*>, onDone: () -> Unit) {
    @Suppress("UNCHECKED_CAST")
    val ch = choice as Choice<Any?>
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val s by store.flow.collectAsState()
    SettingsList {
        item { Header(ch.title) }
        items(ch.options) { (value, label) ->
            RadioButton(
                selected = ch.get(s) == value,
                onSelect = {
                    store.update { ch.set(it, value) }
                    onDone()
                },
                colors = radioColors(),
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(label)) },
            )
        }
    }
}

/** Ausgeblendete Decks ein-/ausschalten. */
@Composable
private fun DeckPickScreen(decks: List<DeckTreeNode>) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val s by store.flow.collectAsState()
    SettingsList {
        item { Header(R.string.set_hidden_decks) }
        item { Note(R.string.hidden_decks_hint) }
        items(decks, key = { it.deckId }) { deck ->
            SwitchButton(
                checked = deck.deckId in s.hiddenDecks,
                onCheckedChange = {
                    store.update { it.copy(hiddenDecks = it.hiddenDecks.toggle(deck.deckId)) }
                },
                colors = switchColors(),
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp * (deck.level - 1)),
                label = { Text(deck.name, maxLines = 2) },
            )
        }
    }
}

@Composable
private fun AccountScreen(repo: AnkiRepository, onDone: () -> Unit) {
    val c = LocalAnkiColors.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(repo.account) }
    val signedIn = account as? Account.SignedIn
    // Vorbelegung: gespeicherter Server bzw. die eingebauten Werte; das Passwort nie.
    var server by remember { mutableStateOf(signedIn?.endpoint ?: BuildConfig.SYNC_ENDPOINT) }
    var user by remember { mutableStateOf(signedIn?.user ?: BuildConfig.SYNC_USER) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SettingsList {
        item { Header(R.string.set_account) }
        item {
            val status = when (val a = account) {
                is Account.SignedIn -> stringResource(R.string.account_signed_in_as, a.user, a.endpoint.ifEmpty { "AnkiWeb" })
                Account.BuiltIn -> stringResource(R.string.account_built_in)
                Account.SignedOut -> stringResource(R.string.account_signed_out)
            }
            Text(status, color = c.textDim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item { Field(R.string.field_server, server, KeyboardType.Uri) { server = it } }
        item { Field(R.string.field_user, user, KeyboardType.Email) { user = it } }
        item { Field(R.string.field_password, password, KeyboardType.Password, password = true) { password = it } }
        error?.let { msg -> item { Text(msg, color = c.again, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 4) } }
        item {
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = repo.signIn(server, user.trim(), password)) {
                            SyncResult.Ok -> onDone()
                            is SyncResult.Failed -> error = r.message
                        }
                        password = ""
                        account = repo.account
                        busy = false
                    }
                },
                enabled = !busy && user.isNotBlank() && password.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.background),
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (busy) R.string.signing_in else R.string.sign_in)) },
            )
        }
        if (account != Account.SignedOut) {
            item {
                Button(
                    onClick = {
                        scope.launch {
                            repo.signOut()
                            account = repo.account
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = c.surface, contentColor = c.again),
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.sign_out)) },
                )
            }
        }
    }
}

// --- Bausteine ---

/** Gemeinsamer Rahmen: ScalingLazyColumn mit Krone, Farben aus dem App-Theme. */
@Composable
private fun SettingsList(content: ScalingLazyListScope.() -> Unit) {
    val c = LocalAnkiColors.current
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ScalingLazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .rotaryScrollable(RotaryScrollableDefaults.behavior(scrollableState = state), focus),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        autoCentering = null,
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 28.dp, bottom = 40.dp),
        content = content,
    )
}

@Composable
private fun Header(@StringRes title: Int) {
    ListHeader { Text(stringResource(title), color = LocalAnkiColors.current.text) }
}

@Composable
private fun Group(@StringRes title: Int) {
    val c = LocalAnkiColors.current
    Text(
        stringResource(title),
        color = c.accent,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp),
    )
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(
        stringResource(text),
        color = LocalAnkiColors.current.textDim,
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}

@Composable
private fun Toggle(@StringRes label: Int, checked: Boolean, @StringRes hint: Int? = null, onChange: (Boolean) -> Unit) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        colors = switchColors(),
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = hint?.let { { Text(stringResource(it)) } },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun ChoiceRow(choice: Choice<*>, s: AppSettings, onNavigate: (String) -> Unit) {
    NavRow(choice.title, stringResource(choice.label(s))) { onNavigate("settings/choice/${choice.key}") }
}

@Composable
private fun NavRow(@StringRes label: Int, value: String, onClick: () -> Unit) {
    val c = LocalAnkiColors.current
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = c.surface, contentColor = c.text, secondaryContentColor = c.accent),
        modifier = Modifier.fillMaxWidth(),
        secondaryLabel = { Text(value, maxLines = 1) },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    type: KeyboardType,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    val c = LocalAnkiColors.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        Text(stringResource(label), color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        // Tippen öffnet die Tastatur der Uhr.
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(c.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun switchColors() = LocalAnkiColors.current.let { c ->
    SwitchButtonDefaults.switchButtonColors(
        checkedContainerColor = c.surface,
        checkedContentColor = c.text,
        checkedSecondaryContentColor = c.textDim,
        checkedThumbColor = c.background,
        checkedTrackColor = c.accent,
        checkedTrackBorderColor = c.accent,
        uncheckedContainerColor = c.surface,
        uncheckedContentColor = c.text,
        uncheckedSecondaryContentColor = c.textDim,
        uncheckedThumbColor = c.textDim,
        uncheckedTrackColor = c.subSurface,
        uncheckedTrackBorderColor = c.textDim,
    )
}

@Composable
private fun radioColors() = LocalAnkiColors.current.let { c ->
    RadioButtonDefaults.radioButtonColors(
        selectedContainerColor = c.surface,
        selectedContentColor = c.text,
        selectedControlColor = c.accent,
        unselectedContainerColor = c.surface,
        unselectedContentColor = c.text,
        unselectedControlColor = c.textDim,
    )
}
