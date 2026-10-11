"""Проверяет исходный расчёт Android-отступов отдельно от проекта, без Gradle."""
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
bridge = (root / 'android/src/main/java/com/brentvatne/exoplayer/ReactExoplayerView.java').read_text()
assert re.search(r'protected void setIsInPictureInPicture\(boolean isInPictureInPicture\) \{\s*exoPlayerView.setIsInPictureInPicture\(isInPictureInPicture\);', bridge), 'PiP должен скрывать header до раннего выхода fullscreen'

source = (root / 'android/src/main/java/com/brentvatne/exoplayer/ExoPlayerView.kt').read_text()
method = source[source.index('    private fun updateHeaderInsets('):source.index('    /** Пустой текст скрывает')]
method += source[source.index('    private fun updateNoticeInsets('):source.index('    fun setPlayer(')]
layouts = re.findall(r'    override fun onLayout\(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int\) \{.*?\n    \}', source, re.S)
assert len(layouts) == 1, 'ExoPlayerView должен содержать один override onLayout'
method += layouts[0]
method += source[source.index('    fun setVideoTitle('):source.index('    /** Добавляет только ту часть')]
method += source[source.index('    fun setControllerVisibilityListener('):source.index('    override fun addOnLayoutChangeListener(')]
binding = re.search(r'        targetPlayerView.setControllerVisibilityListener\(PlayerView.ControllerVisibilityListener \{ visibility ->.*?\n        header.visibility = .*?\n', source, re.S)
assert binding, 'Каждый новый PlayerView должен синхронизировать заголовок с controls'
method += '    /** Подключает исходный listener нового PlayerView. */\n    fun bindController(targetPlayerView: PlayerView) {\n' + binding.group() + '    }\n'
program = '''import kotlin.math.roundToInt
class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int = 0) { companion object { val NONE = Insets(0, 0, 0) } }
class WindowInsetsCompat(val safe: Insets) {
    /** Возвращает тестовые системные отступы. */
    fun getInsets(types: Int) = safe
    object Type {
        /** Обозначает системные панели. */
        fun systemBars() = 1
        /** Обозначает вырез экрана. */
        fun displayCutout() = 2
    }
}
class LayoutParams {
    var leftMargin = 0; var topMargin = 0; var rightMargin = 0; var bottomMargin = 0
    /** Сохраняет отступы тестовой строки. */
    fun setMargins(left: Int, top: Int, right: Int, bottom: Int) {
        leftMargin = left; topMargin = top; rightMargin = right; bottomMargin = bottom
    }
}
class Header {
    var layoutParams: Any = LayoutParams(); var visibility = View.VISIBLE; var alpha = 1f
    val animator = Animator(this)
    /** Возвращает управляемую тестом анимацию. */
    fun animate() = animator
}
class Animator(private val header: Header) {
    var duration = 0L
    private var targetAlpha = 1f
    private var endAction: (() -> Unit)? = null
    /** Отменяет старое завершение, как ViewPropertyAnimator. */
    fun cancel() { endAction = null }
    /** Сохраняет целевую прозрачность. */
    fun alpha(value: Float): Animator { targetAlpha = value; return this }
    /** Сохраняет длительность fade. */
    fun setDuration(value: Long): Animator { duration = value; return this }
    /** Откладывает завершение до проверки промежуточного состояния. */
    fun withEndAction(action: () -> Unit): Animator { endAction = action; return this }
    /** Завершает только неотменённую анимацию. */
    fun finish() { val action = endAction; endAction = null; if (action != null) { header.alpha = targetAlpha; action() } }
}
class View { companion object { const val GONE = 8; const val VISIBLE = 0 } }
class TextView { var text: String? = null; var visibility = View.GONE }
class Metrics { var density = 1f }
class Resources { val displayMetrics = Metrics() }
class Root { var width = 1080; var height = 1920 }
open class FrameLayout {
    var layoutCalls = 0
    /** Учитывает вызов родительского layout. */
    open fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) { layoutCalls++ }
}
object ViewCompat {
    /** Возвращает текущие отступы тестового окна. */
    fun getRootWindowInsets(view: Check) = view.insets
}
class PlayerView {
    fun interface ControllerVisibilityListener {
        /** Передаёт новую видимость controls. */
        fun onVisibilityChanged(visibility: Int)
    }
    var resizeMode = 0
    var isControllerFullyVisible = false
    private var listener: ControllerVisibilityListener? = null
    /** Подключает настоящий callback проверяемого view. */
    fun setControllerVisibilityListener(value: ControllerVisibilityListener?) { listener = value }
    /** Имитирует тап и автоматическое скрытие controls без собственного таймера заголовка. */
    fun changeVisibility(value: Int) { isControllerFullyVisible = value == View.VISIBLE; listener?.onVisibilityChanged(value) }
}
class Check : FrameLayout() {
    val titleView = TextView(); val episodeTitleView = TextView(); val ageRatingView = TextView()
    private var videoTitle: String? = null
    private var playbackTitle: String? = null
    private var controllerVisibilityListener: PlayerView.ControllerVisibilityListener? = null
    private var isInPictureInPicture = false
    val playerView = PlayerView()
    var pendingResizeMode: Int? = null
    var insets: WindowInsetsCompat? = null
    val resources = Resources(); val rootView = Root(); val header = Header(); val noticeView = Header()
    var height = 1920; var width = 1080; var x = 0; var y = 0
    /** Моделирует координаты view внутри окна. */
    fun getLocationInWindow(location: IntArray) { location[0] = x; location[1] = y }
''' + method + '''
    /** Проверяет отступы предупреждения над controls. */
    fun checkNotice(insets: Insets?, left: Int, right: Int, bottom: Int) {
        updateNoticeInsets(insets?.let { WindowInsetsCompat(it) })
        val p = noticeView.layoutParams as LayoutParams
        check(listOf(p.leftMargin, p.rightMargin, p.bottomMargin) == listOf(left, right, bottom))
    }
    /** Проверяет результат исходного расчёта отступов. */
    fun check(insets: Insets?, left: Int, top: Int, right: Int) {
        updateHeaderInsets(insets?.let { WindowInsetsCompat(it) })
        val p = header.layoutParams as LayoutParams
        check(listOf(p.leftMargin, p.topMargin, p.rightMargin) == listOf(left, top, right))
    }
}
/** Проверяет edge-to-edge и безопасные родительские отступы. */
fun main() {
    val view = Check()
    // Портрет edge-to-edge, затем SafeAreaView: отступ status bar не удваивается.
    view.check(Insets(0, 24, 0), 16, 40, 16)
    view.y = 24
    view.check(Insets(0, 24, 0), 16, 16, 16)
    // Ландшафт: вырез слева, navigation bar справа, затем уже безопасный родитель.
    view.y = 0; view.width = 1920; view.rootView.width = 1920
    view.check(Insets(40, 0, 24), 56, 16, 40)
    view.x = 40; view.width = 1856
    view.check(Insets(40, 0, 24), 16, 16, 16)
    // Частично покрытый inset, скрытые bars/fullscreen, плотность 2.
    view.x = 0; view.width = 1920; view.y = 10
    view.check(Insets(0, 24, 0), 16, 30, 16)
    view.check(null, 16, 16, 16)
    view.resources.displayMetrics.density = 2f; view.y = 0
    view.check(Insets(0, 48, 0), 32, 80, 32)
    view.resources.displayMetrics.density = 1f
    view.rootView.width = 1080; view.width = 1080; view.rootView.height = 1920; view.height = 1920
    view.checkNotice(Insets(0, 24, 0, 48), 20, 20, 148)
    view.height = 1872
    view.checkNotice(Insets(0, 24, 0, 48), 20, 20, 100)
    view.rootView.width = 1920; view.width = 1920; view.rootView.height = 1080; view.height = 1080
    view.checkNotice(Insets(40, 0, 24), 60, 44, 100)
    view.checkNotice(null, 20, 20, 100)
    view.resources.displayMetrics.density = 2f
    view.checkNotice(Insets(80, 0, 48, 96), 120, 88, 296)
    // Исходный onLayout обновляет отступы при каждом проходе, resizeMode только при changed.
    val layout = Check()
    layout.insets = WindowInsetsCompat(Insets(0, 24, 0, 48))
    layout.pendingResizeMode = 3
    layout.onLayout(true, 0, 0, 1080, 1920)
    check(layout.playerView.resizeMode == 3)
    check((layout.header.layoutParams as LayoutParams).topMargin == 40)
    check((layout.noticeView.layoutParams as LayoutParams).bottomMargin == 148)
    layout.insets = WindowInsetsCompat(Insets(0, 30, 0, 60))
    layout.pendingResizeMode = 4
    layout.onLayout(false, 0, 0, 1080, 1920)
    check(layout.playerView.resizeMode == 3)
    check((layout.header.layoutParams as LayoutParams).topMargin == 46)
    check((layout.noticeView.layoutParams as LayoutParams).bottomMargin == 160)
    layout.pendingResizeMode = null
    layout.onLayout(true, 0, 0, 1080, 1920)
    check(layout.playerView.resizeMode == 3)
    check(layout.layoutCalls == 3)
    // Тап, timeout и пересоздание surface используют одну видимость и сохраняют внешний callback.
    val controls = Check()
    val events = mutableListOf<Int>()
    controls.setControllerVisibilityListener { events.add(it) }
    controls.bindController(controls.playerView)
    check(controls.header.visibility == View.GONE)
    controls.playerView.changeVisibility(View.VISIBLE)
    check(controls.header.visibility == View.VISIBLE && events.last() == View.VISIBLE)
    controls.playerView.changeVisibility(View.GONE)
    check(controls.header.visibility == View.VISIBLE && events.last() == View.GONE)
    check(controls.header.animator.duration == 150L)
    controls.header.animator.finish()
    check(controls.header.visibility == View.GONE && controls.header.alpha == 1f)
    // Быстрый повторный тап отменяет старое скрытие.
    controls.playerView.changeVisibility(View.VISIBLE)
    controls.playerView.changeVisibility(View.GONE)
    controls.playerView.changeVisibility(View.VISIBLE)
    controls.header.animator.finish()
    check(controls.header.visibility == View.VISIBLE && controls.header.alpha == 1f)
    controls.playerView.changeVisibility(View.GONE)
    controls.header.animator.finish()
    controls.setPlaybackHeader("Новая серия", "Серия 2", "16+")
    check(controls.header.visibility == View.GONE)
    val replacement = PlayerView()
    replacement.isControllerFullyVisible = true
    controls.bindController(replacement)
    check(controls.header.visibility == View.VISIBLE)
    val eventCount = events.size
    controls.setControllerVisibilityListener(null)
    replacement.changeVisibility(View.GONE)
    controls.header.animator.finish()
    check(controls.header.visibility == View.GONE && events.size == eventCount)
    // Пересоздание PlayerView отменяет fade старого заголовка.
    replacement.changeVisibility(View.VISIBLE)
    replacement.changeVisibility(View.GONE)
    val next = PlayerView()
    next.isControllerFullyVisible = true
    controls.bindController(next)
    controls.header.animator.finish()
    check(controls.header.visibility == View.VISIBLE && controls.header.alpha == 1f)
    // PiP отменяет fade, игнорирует показ controls и пересоздание surface внутри мини-окна.
    controls.bindController(controls.playerView)
    controls.playerView.changeVisibility(View.VISIBLE)
    controls.playerView.changeVisibility(View.GONE)
    controls.setIsInPictureInPicture(true)
    controls.header.animator.finish()
    check(controls.header.visibility == View.GONE)
    controls.playerView.changeVisibility(View.VISIBLE)
    check(controls.header.visibility == View.GONE)
    controls.bindController(controls.playerView)
    check(controls.header.visibility == View.GONE)
    controls.setIsInPictureInPicture(false)
    check(controls.header.visibility == View.VISIBLE && controls.header.alpha == 1f)
    controls.setIsInPictureInPicture(true)
    controls.playerView.changeVisibility(View.GONE)
    controls.setIsInPictureInPicture(false)
    check(controls.header.visibility == View.GONE)
    controls.playerView.changeVisibility(View.VISIBLE)
    check(controls.header.visibility == View.VISIBLE)
    // Очистка header возвращает metadata.title и скрывает старые серию/рейтинг.
    layout.setVideoTitle("Эфир")
    layout.setPlaybackHeader(" Темные начала. Сезон 3 ", " Серия 6. Бездна ", "16+")
    check(layout.titleView.text == "Темные начала. Сезон 3")
    check(layout.episodeTitleView.text == "Серия 6. Бездна")
    check(layout.episodeTitleView.visibility == View.VISIBLE)
    check(layout.ageRatingView.text == "16+" && layout.ageRatingView.visibility == View.VISIBLE)
    layout.setVideoTitle("Новые metadata")
    check(layout.titleView.text == "Темные начала. Сезон 3")
    layout.setPlaybackHeader("Фильм", null, "12+")
    check(layout.episodeTitleView.visibility == View.GONE)
    layout.setPlaybackHeader(null, null, null)
    check(layout.titleView.text == "Новые metadata")
    check(layout.ageRatingView.visibility == View.GONE)
    layout.setVideoTitle(null)
    check(layout.titleView.visibility == View.GONE)
    println("Android header, layout, resize mode and notice inset checks passed")
}
'''
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
compiler = next(p for p in (cache / 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.20').glob('*/*.jar') if not p.name.endswith('-sources.jar'))
jars = [compiler]
for group, module in [('org.jetbrains.kotlin', 'kotlin-stdlib'), ('org.jetbrains.kotlin', 'kotlin-script-runtime'),
                      ('org.jetbrains.kotlin', 'kotlin-reflect'), ('org.jetbrains.intellij.deps', 'trove4j'),
                      ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm'), ('org.jetbrains', 'annotations')]:
    base = cache / group / module
    if group == 'org.jetbrains.kotlin':
        base = base / '2.1.20'
    pattern = '*/*.jar' if group == 'org.jetbrains.kotlin' else '*/*/*.jar'
    candidates = sorted(p for p in base.glob(pattern) if not p.name.endswith(('-sources.jar', '-javadoc.jar')))
    jars.append(candidates[-1])
classpath = ':'.join(str(p) for p in jars)
with tempfile.TemporaryDirectory() as directory:
    script = Path(directory) / 'Check.kt'
    script.write_text(program)
    subprocess.run(['java', '-cp', classpath, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-classpath', classpath, '-d', directory, str(script)], check=True)
    subprocess.run(['java', '-cp', directory + ':' + classpath, 'CheckKt'], check=True)

# Проверяем настоящий Swift-setter: сериал, фильм и очистка без пересоздания AVPlayer.
swift = (root / 'ios/Video/RCTVideo.swift').read_text()
setter = swift[swift.index('    func setPlaybackHeader('):swift.index('    /** Показывает предупреждение без')]
program = '''import Foundation
class View { var isHidden = false }
class Label: View { var text: String?; var superview: View? = View() }
class Check {
    let playbackHeaderView: View = { let header = View(); header.isHidden = true; return header }()
    let playbackTitleView = Label()
    let playbackSubtitleView = Label()
    let playbackAgeView = Label()
''' + setter + '''
}
let view = Check()
view.setPlaybackHeader(["title": " Темные начала. Сезон 3 ", "subtitle": "Серия 6. Бездна", "ageRating": "16+"])
assert(view.playbackHeaderView.isHidden)
assert(view.playbackTitleView.text == "Темные начала. Сезон 3")
assert(view.playbackSubtitleView.text == "Серия 6. Бездна" && !view.playbackSubtitleView.isHidden)
assert(view.playbackAgeView.text == "16+" && view.playbackAgeView.superview?.isHidden == false)
view.setPlaybackHeader(["title": "Цвета времени", "ageRating": "12+"])
assert(view.playbackHeaderView.isHidden && view.playbackSubtitleView.isHidden)
view.setPlaybackHeader(["title": "Фильм", "subtitle": " ", "ageRating": " "])
assert(view.playbackSubtitleView.isHidden && view.playbackAgeView.superview?.isHidden == true)
view.setPlaybackHeader(nil)
assert(view.playbackHeaderView.isHidden && view.playbackTitleView.text == nil)
print("Swift playback header update and cleanup checks passed")
'''
with tempfile.TemporaryDirectory() as directory:
    script = Path(directory) / 'Check.swift'
    script.write_text(program)
    subprocess.run(['swift', '-module-cache-path', str(Path(directory) / 'cache'), str(script)], check=True)
