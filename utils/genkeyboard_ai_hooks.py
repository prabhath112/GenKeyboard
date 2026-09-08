"""
One-shot patcher that wires the GenKeyboard AI panel into the FlorisBoard core.
Idempotent: re-running on an already patched tree is a no-op.
Kept as a script so the upstream-touching edits are documented in one place.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
K = ROOT / "app/src/main/kotlin/dev/patrickgold/florisboard"


def patch(path: pathlib.Path, old: str, new: str, *, marker: str | None = None) -> None:
    text = path.read_text(encoding="utf-8")
    if (marker or new) in text:
        print(f"  skip  {path.relative_to(ROOT)}")
        return
    assert old in text, f"anchor not found in {path}: {old[:60]!r}"
    path.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")
    print(f"  patch {path.relative_to(ROOT)}")


# 1. New UI mode
patch(K / "ime/ImeUiMode.kt", "    CLIPBOARD(2);", "    CLIPBOARD(2),\n    AI(3);")

# 2. Key code
keycode = (K / "ime/text/key/KeyCode.kt").read_text(encoding="utf-8")
assert "-214" not in keycode or "IME_UI_MODE_AI" in keycode, "-214 already used by another key code"
patch(
    K / "ime/text/key/KeyCode.kt",
    "    const val IME_UI_MODE_CLIPBOARD =       -213\n",
    "    const val IME_UI_MODE_CLIPBOARD =       -213\n    const val IME_UI_MODE_AI =              -214\n",
)

# 3. TextKeyData: internal key list + predefined constant
patch(
    K / "ime/text/keyboard/TextKeyData.kt",
    "                IME_UI_MODE_CLIPBOARD,\n                SYSTEM_INPUT_METHOD_PICKER,",
    "                IME_UI_MODE_CLIPBOARD,\n                IME_UI_MODE_AI,\n                SYSTEM_INPUT_METHOD_PICKER,",
)
patch(
    K / "ime/text/keyboard/TextKeyData.kt",
    '            label = "ime_ui_mode_clipboard",\n        )\n',
    '            label = "ime_ui_mode_clipboard",\n        )\n'
    "        /** Predefined key data for [KeyCode.IME_UI_MODE_AI] */\n"
    "        val IME_UI_MODE_AI = TextKeyData(\n"
    "            type = KeyType.SYSTEM_GUI,\n"
    "            code = KeyCode.IME_UI_MODE_AI,\n"
    '            label = "ime_ui_mode_ai",\n'
    "        )\n",
)

# 4. Dispatch
patch(
    K / "ime/keyboard/KeyboardManager.kt",
    "            KeyCode.IME_UI_MODE_CLIPBOARD -> activeState.imeUiMode = ImeUiMode.CLIPBOARD\n",
    "            KeyCode.IME_UI_MODE_CLIPBOARD -> activeState.imeUiMode = ImeUiMode.CLIPBOARD\n"
    "            KeyCode.IME_UI_MODE_AI -> activeState.imeUiMode = ImeUiMode.AI\n",
)

# 5. Window switch
patch(
    K / "ime/window/ImeWindow.kt",
    "                ImeUiMode.CLIPBOARD -> ProvideActualLayoutDirection { ClipboardInputLayout() }\n",
    "                ImeUiMode.CLIPBOARD -> ProvideActualLayoutDirection { ClipboardInputLayout() }\n"
    "                ImeUiMode.AI -> ProvideActualLayoutDirection { AiInputLayout() }\n",
)
patch(
    K / "ime/window/ImeWindow.kt",
    "import dev.patrickgold.florisboard.ime.ImeUiMode\n",
    "import com.genkeyboard.ai.ui.AiInputLayout\nimport dev.patrickgold.florisboard.ime.ImeUiMode\n",
)

# 6. Quick action labels
patch(
    K / "ime/smartbar/quickaction/QuickAction.kt",
    "            KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media\n",
    "            KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media\n"
    "            KeyCode.IME_UI_MODE_AI -> R.string.quick_action__ime_ui_mode_ai\n",
)
patch(
    K / "ime/smartbar/quickaction/QuickAction.kt",
    "            KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media__tooltip\n",
    "            KeyCode.IME_UI_MODE_MEDIA -> R.string.quick_action__ime_ui_mode_media__tooltip\n"
    "            KeyCode.IME_UI_MODE_AI -> R.string.quick_action__ime_ui_mode_ai__tooltip\n",
)

# 7. Icon
patch(
    K / "ime/keyboard/ComputingEvaluator.kt",
    "        KeyCode.IME_UI_MODE_CLIPBOARD -> {\n            Icons.AutoMirrored.Outlined.Assignment\n        }\n",
    "        KeyCode.IME_UI_MODE_CLIPBOARD -> {\n            Icons.AutoMirrored.Outlined.Assignment\n        }\n"
    "        KeyCode.IME_UI_MODE_AI -> {\n            Icons.Default.AutoAwesome\n        }\n",
)
ce = K / "ime/keyboard/ComputingEvaluator.kt"
if "import androidx.compose.material.icons.filled.AutoAwesome\n" not in ce.read_text(encoding="utf-8"):
    text = ce.read_text(encoding="utf-8")
    m = re.search(r"^import androidx\.compose\.material\.icons\.filled\.[A-Za-z]+\n", text, re.M)
    assert m, "no filled icon import to anchor on"
    text = text[: m.start()] + "import androidx.compose.material.icons.filled.AutoAwesome\n" + text[m.start():]
    ce.write_text(text, encoding="utf-8", newline="\n")
    print("  patch app/.../ComputingEvaluator.kt (import)")

# 8. No key popup for the panel switch key
patch(
    K / "ime/popup/PopupUiController.kt",
    "    KeyCode.IME_UI_MODE_CLIPBOARD,\n",
    "    KeyCode.IME_UI_MODE_CLIPBOARD,\n    KeyCode.IME_UI_MODE_AI,\n",
)

# 9. Default arrangement: AI is the sticky action, voice input moves into the dynamic row
patch(
    K / "ime/smartbar/quickaction/QuickActionArrangement.kt",
    "            stickyAction = QuickAction.InsertKey(TextKeyData.VOICE_INPUT),\n"
    "            dynamicActions = listOf(\n",
    "            stickyAction = QuickAction.InsertKey(TextKeyData.IME_UI_MODE_AI),\n"
    "            dynamicActions = listOf(\n"
    "                QuickAction.InsertKey(TextKeyData.VOICE_INPUT),\n",
)

# 10. Preferences
patch(
    K / "app/AppPrefs.kt",
    "    val clipboard = Clipboard()\n    inner class Clipboard {\n",
    "    val ai = Ai()\n"
    "    inner class Ai {\n"
    "        val enabled = boolean(\n"
    '            key = "ai__enabled",\n'
    "            default = true,\n"
    "        )\n"
    "        val backendUrl = string(\n"
    '            key = "ai__backend_url",\n'
    "            default = BuildConfig.AI_BACKEND_URL,\n"
    "        )\n"
    "        /** Random UUID, generated on first AI request. Acts as the quota key on the backend. */\n"
    "        val deviceId = string(\n"
    '            key = "ai__device_id",\n'
    '            default = "",\n'
    "        )\n"
    "    }\n\n"
    "    val clipboard = Clipboard()\n    inner class Clipboard {\n",
)
patch(
    K / "app/AppPrefs.kt",
    "import dev.patrickgold.florisboard.app.settings.theme.ColorPreferenceSerializer\n",
    "import dev.patrickgold.florisboard.BuildConfig\n"
    "import dev.patrickgold.florisboard.app.settings.theme.ColorPreferenceSerializer\n",
)

# 11. Manager registration + Context accessor
patch(
    K / "FlorisApplication.kt",
    "    val cacheManager = lazy { CacheManager(this) }\n",
    "    val aiManager = lazy { AiManager(this) }\n    val cacheManager = lazy { CacheManager(this) }\n",
)
patch(
    K / "FlorisApplication.kt",
    "fun Context.cacheManager() = this.florisApplication().cacheManager\n",
    "fun Context.aiManager() = this.florisApplication().aiManager\n\n"
    "fun Context.cacheManager() = this.florisApplication().cacheManager\n",
)
fa = K / "FlorisApplication.kt"
if "import com.genkeyboard.ai.AiManager\n" not in fa.read_text(encoding="utf-8"):
    text = fa.read_text(encoding="utf-8")
    m = re.search(r"^import ", text, re.M)
    text = text[: m.start()] + "import com.genkeyboard.ai.AiManager\n" + text[m.start():]
    fa.write_text(text, encoding="utf-8", newline="\n")
    print("  patch app/.../FlorisApplication.kt (import)")

# 12. Build config: backend URL per build type
patch(
    ROOT / "app/build.gradle.kts",
    '        buildConfigField("String", "FLADDONS_API_VERSION", "\\"v~draft2\\"")\n',
    '        buildConfigField("String", "FLADDONS_API_VERSION", "\\"v~draft2\\"")\n'
    '        buildConfigField("String", "AI_BACKEND_URL", "\\"https://genkeyboard-api.workers.dev\\"")\n',
)
patch(
    ROOT / "app/build.gradle.kts",
    '            applicationIdSuffix = ".debug"\n',
    '            applicationIdSuffix = ".debug"\n'
    "            // wrangler dev on the host machine, as seen from the Android emulator\n"
    '            buildConfigField("String", "AI_BACKEND_URL", "\\"http://10.0.2.2:8787\\"")\n',
)

# 13. Network permission
patch(
    ROOT / "app/src/main/AndroidManifest.xml",
    '    <uses-permission android:name="android.permission.VIBRATE"/>\n',
    '    <uses-permission android:name="android.permission.VIBRATE"/>\n\n'
    "    <!-- GenKeyboard: needed only for the AI writing tools; text is sent solely on explicit user action -->\n"
    '    <uses-permission android:name="android.permission.INTERNET"/>\n',
)

print("done")
