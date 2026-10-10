package com.brentvatne.exoplayer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import com.brentvatne.common.api.ResizeMode
import com.brentvatne.common.api.SubtitleStyle
import com.brentvatne.common.api.ViewType
import com.brentvatne.react.R
import kotlin.math.roundToInt

@UnstableApi
class ExoPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    FrameLayout(context, attrs, defStyleAttr) {

    /** Жесты привязаны к view, которое переносится в системный fullscreen. */
    var pinchGestureEnabled = false
    var doubleTapGestureEnabled = false
    var onZoomGesture: ((Boolean) -> Unit)? = null
    var onSeekGesture: ((Int) -> Unit)? = null
    private var gestureConsumed = false
    private var tapOnControls = false
    private var pinchScale = 1f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        /** Начинает pinch отдельно от одиночных тапов native controls. */
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchScale = 1f
            gestureConsumed = true
            return true
        }

        /** Накапливает масштаб до завершения жеста. */
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale *= detector.scaleFactor
            return true
        }

        /** Передаёт только корректный завершённый pinch. */
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (pinchGestureEnabled && pinchScale.isFinite() && pinchScale > 0f && pinchScale != 1f) {
                onZoomGesture?.invoke(pinchScale > 1f)
            }
        }
    }).apply {
        // Pinch требует двух пальцев, а не double-tap drag или кнопки стилуса.
        isQuickScaleEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) isStylusScaleEnabled = false
    }
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        /** Разрешает детектору отслеживать второе нажатие. */
        override fun onDown(event: MotionEvent): Boolean = true

        /** Распознаёт двойной тап только в прежних боковых зонах КИНОТВ. */
        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (!doubleTapGestureEnabled || tapOnControls || gestureConsumed) return false
            val density = resources.displayMetrics.density
            val zoneWidth = width / 3f - 50f * density
            if (event.x < 0f || event.x > width || event.y < 100f * density || event.y > height - 80f * density || zoneWidth <= 0f) return false
            val delta = when {
                event.x <= zoneWidth -> -20
                event.x >= width - zoneWidth -> 20
                else -> return false
            }
            gestureConsumed = true
            onSeekGesture?.invoke(delta)
            return true
        }
    })

    /** Сохраняет кнопки и seekbar нативных controls вне зон двойного тапа. */
    private fun touchesControl(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE || x < 0 || y < 0 || x > view.width || y > view.height) return false
        if (view is DefaultTimeBar || (view !== playerView && view.isClickable)) return true
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                if (touchesControl(child, x - child.left + view.scrollX, y - child.top + view.scrollY)) return true
            }
        }
        return false
    }

    /** Отменяет касания дочернего controls только после распознавания жеста. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!pinchGestureEnabled && !doubleTapGestureEnabled) return super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureConsumed = false
            tapOnControls = touchesControl(playerView, event.x - playerView.left, event.y - playerView.top)
        }
        val wasConsumed = gestureConsumed
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) pinchScale = 1f
        if (pinchGestureEnabled) scaleDetector.onTouchEvent(event)
        if (doubleTapGestureEnabled) {
            if (event.pointerCount > 1 || gestureConsumed) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                tapDetector.onTouchEvent(cancel)
                cancel.recycle()
            } else {
                tapDetector.onTouchEvent(event)
            }
        }
        if (gestureConsumed) {
            if (!wasConsumed) {
                parent?.requestDisallowInterceptTouchEvent(true)
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private var localStyle = SubtitleStyle()
    private var pendingResizeMode: Int? = null
    private var currentPlayer: ExoPlayer? = null
    private var currentViewType = ViewType.VIEW_TYPE_SURFACE
    private var currentShutterColor = Color.TRANSPARENT
    private var useController = true
    private var controllerAutoShow = true
    private var controllerHideOnTouch = true
    private var controllerShowTimeoutMs = 5000
    private var showSubtitleButton = false
    private var isPlayerFocusable = true
    private var controllerVisibilityListener: PlayerView.ControllerVisibilityListener? = null
    private var fullscreenButtonClickListener: PlayerView.FullscreenButtonClickListener? = null
    private val playerViewLayoutChangeListeners = mutableListOf<View.OnLayoutChangeListener>()
    private val liveBadge: TextView = TextView(context).apply {
        text = "LIVE"
        setTextColor(Color.WHITE)
        textSize = 12f
        val drawable = GradientDrawable()
        drawable.setColor(Color.RED)
        drawable.cornerRadius = 6f
        background = drawable
        setPadding(12, 4, 12, 4)
        visibility = View.GONE
    }

    /** Однострочное название не перекрывает Live и обрезается по ширине плеера. */
    private val titleView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 18f
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(2f * resources.displayMetrics.density, 0f, 0f, Color.BLACK)
        visibility = View.GONE
    }
    /** Предупреждение остаётся поверх видео при переносе view в fullscreen. */
    private val noticeView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(77, 255, 255, 255))
        alpha = 0.6f
        val padding = (5f * resources.displayMetrics.density).roundToInt()
        setPadding(padding, padding, padding, padding)
        visibility = View.GONE
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(liveBadge, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (8f * resources.displayMetrics.density).roundToInt()
        })
        addView(titleView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private var playerView = createPlayerView(currentViewType)

    init {
        attachPlayerView(playerView)

        // Add live badge with its own layout parameters
        val headerLayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT)
        addView(header, headerLayoutParams)
        addView(noticeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            updateHeaderInsets(insets)
            updateNoticeInsets(insets)
            insets
        }
    }

    /** Показывает metadata.title; пустое название очищает предыдущий материал. */
    fun setVideoTitle(title: String?) {
        titleView.text = title?.trim()
        titleView.visibility = if (titleView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Добавляет только ту часть системных отступов, которую ещё не покрыл родитель. */
    private fun updateHeaderInsets(insets: WindowInsetsCompat?) {
        val safe = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val location = IntArray(2)
        getLocationInWindow(location)
        val margin = (16f * resources.displayMetrics.density).roundToInt()
        val params = header.layoutParams as LayoutParams
        val left = margin + maxOf(0, safe.left - location[0])
        val top = margin + maxOf(0, safe.top - location[1])
        val right = margin + maxOf(0, safe.right - (rootView.width - location[0] - width))
        if (params.leftMargin != left || params.topMargin != top || params.rightMargin != right) {
            params.setMargins(left, top, right, 0)
            header.layoutParams = params
        }
    }

    /** Пустой текст скрывает предупреждение без изменения источника видео. */
    fun setPlaybackNotice(notice: String?) {
        noticeView.text = notice
        noticeView.visibility = if (notice.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Держит предупреждение выше controls и вне системных панелей в обеих ориентациях. */
    private fun updateNoticeInsets(insets: WindowInsetsCompat?) {
        val safe = insets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()) ?: Insets.NONE
        val location = IntArray(2)
        getLocationInWindow(location)
        val density = resources.displayMetrics.density
        val horizontal = (20f * density).roundToInt()
        val params = noticeView.layoutParams as LayoutParams
        val left = horizontal + maxOf(0, safe.left - location[0])
        val right = horizontal + maxOf(0, safe.right - (rootView.width - location[0] - width))
        val bottom = (100f * density).roundToInt() + maxOf(0, safe.bottom - (rootView.height - location[1] - height))
        if (params.leftMargin != left || params.rightMargin != right || params.bottomMargin != bottom) {
            params.setMargins(left, 0, right, bottom)
            noticeView.layoutParams = params
        }
    }

    fun setPlayer(player: ExoPlayer?) {
        currentPlayer?.removeListener(playerListener)
        currentPlayer = player
        playerView.player = player

        if (player != null) {
            player.addListener(playerListener)

            // Apply pending resize mode if we have one
            pendingResizeMode?.let { resizeMode ->
                playerView.resizeMode = resizeMode
            }
        }
    }

    fun getPlayerView(): PlayerView = playerView

    fun setResizeMode(@ResizeMode.Mode resizeMode: Int) {
        val targetResizeMode = when (resizeMode) {
            ResizeMode.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            ResizeMode.RESIZE_MODE_CENTER_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ResizeMode.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ResizeMode.RESIZE_MODE_FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            ResizeMode.RESIZE_MODE_FIXED_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        // Apply the resize mode to PlayerView immediately
        playerView.resizeMode = targetResizeMode

        // Store it for reapplication if needed
        pendingResizeMode = targetResizeMode

        // Force PlayerView to recalculate its layout
        playerView.requestLayout()

        // Also request layout on the parent to ensure proper sizing
        requestLayout()
    }

    fun setSubtitleStyle(style: SubtitleStyle) {
        localStyle = style
        applySubtitleStyle(playerView, style)
    }

    fun setShutterColor(color: Int) {
        currentShutterColor = color
        playerView.setShutterBackgroundColor(color)
    }

    fun updateSurfaceView(viewType: Int) {
        if (currentViewType == viewType) {
            applySecureSurface(playerView, viewType)
            return
        }

        currentViewType = viewType
        recreatePlayerView()
    }

    val isPlaying: Boolean
        get() = currentPlayer?.isPlaying ?: false

    fun invalidateAspectRatio() {
        // PlayerView handles aspect ratio automatically through its internal AspectRatioFrameLayout
        playerView.requestLayout()

        // Reapply the current resize mode to ensure it's properly set
        pendingResizeMode?.let { resizeMode ->
            playerView.resizeMode = resizeMode
        }
    }

    fun setUseController(useController: Boolean) {
        this.useController = useController
        playerView.useController = useController
        if (useController) {
            // Ensure proper touch handling when controls are enabled
            playerView.controllerAutoShow = true
            playerView.controllerHideOnTouch = true
            // Show controls immediately when enabled
            playerView.showController()
        }
    }

    fun showController() {
        playerView.showController()
    }

    fun hideController() {
        playerView.hideController()
    }

    fun setControllerShowTimeoutMs(showTimeoutMs: Int) {
        controllerShowTimeoutMs = showTimeoutMs
        playerView.controllerShowTimeoutMs = showTimeoutMs
    }

    fun setControllerAutoShow(autoShow: Boolean) {
        controllerAutoShow = autoShow
        playerView.controllerAutoShow = autoShow
    }

    fun setControllerHideOnTouch(hideOnTouch: Boolean) {
        controllerHideOnTouch = hideOnTouch
        playerView.controllerHideOnTouch = hideOnTouch
    }

    fun setFullscreenButtonClickListener(listener: PlayerView.FullscreenButtonClickListener?) {
        fullscreenButtonClickListener = listener
        playerView.setFullscreenButtonClickListener(listener)
    }

    fun setShowSubtitleButton(show: Boolean) {
        showSubtitleButton = show
        playerView.setShowSubtitleButton(show)
    }

    fun isControllerVisible(): Boolean = playerView.isControllerFullyVisible

    fun setControllerVisibilityListener(listener: PlayerView.ControllerVisibilityListener?) {
        controllerVisibilityListener = listener
        playerView.setControllerVisibilityListener(listener)
    }

    override fun addOnLayoutChangeListener(listener: View.OnLayoutChangeListener) {
        if (!playerViewLayoutChangeListeners.contains(listener)) {
            playerViewLayoutChangeListeners.add(listener)
        }
        playerView.addOnLayoutChangeListener(listener)
    }

    override fun setFocusable(focusable: Boolean) {
        isPlayerFocusable = focusable
        playerView.isFocusable = focusable
    }

    private fun createPlayerView(viewType: Int): PlayerView {
        val createdPlayerView =
            if (viewType == ViewType.VIEW_TYPE_TEXTURE) {
                LayoutInflater.from(context)
                    .inflate(R.layout.exo_texture_player_view, this, false) as PlayerView
            } else {
                PlayerView(context)
            }

        configurePlayerView(createdPlayerView, viewType)

        return createdPlayerView
    }

    private fun configurePlayerView(targetPlayerView: PlayerView, viewType: Int) {
        targetPlayerView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        targetPlayerView.setShutterBackgroundColor(currentShutterColor)
        targetPlayerView.useController = useController
        targetPlayerView.controllerAutoShow = controllerAutoShow
        targetPlayerView.controllerHideOnTouch = controllerHideOnTouch
        targetPlayerView.controllerShowTimeoutMs = controllerShowTimeoutMs
        targetPlayerView.setShowSubtitleButton(showSubtitleButton)
        targetPlayerView.setUseArtwork(false)
        targetPlayerView.setDefaultArtwork(null)
        targetPlayerView.resizeMode =
            pendingResizeMode ?: androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        targetPlayerView.player = currentPlayer
        targetPlayerView.isFocusable = isPlayerFocusable

        controllerVisibilityListener?.let(targetPlayerView::setControllerVisibilityListener)
        fullscreenButtonClickListener?.let(targetPlayerView::setFullscreenButtonClickListener)
        playerViewLayoutChangeListeners.forEach(targetPlayerView::addOnLayoutChangeListener)

        applySecureSurface(targetPlayerView, viewType)
        applySubtitleStyle(targetPlayerView, localStyle)
    }

    private fun attachPlayerView(targetPlayerView: PlayerView) {
        val playerViewLayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(targetPlayerView, 0, playerViewLayoutParams)
    }

    private fun recreatePlayerView() {
        val previousPlayerView = playerView
        val wasControllerVisible = previousPlayerView.isControllerFullyVisible

        previousPlayerView.player = null
        removeView(previousPlayerView)

        playerView = createPlayerView(currentViewType)
        attachPlayerView(playerView)

        if (wasControllerVisible && useController) {
            playerView.showController()
        }

        requestLayout()
        updateLiveUi()
    }

    private fun applySecureSurface(targetPlayerView: PlayerView, viewType: Int) {
        val surfaceView = targetPlayerView.videoSurfaceView as? SurfaceView ?: return
        surfaceView.setSecure(viewType == ViewType.VIEW_TYPE_SURFACE_SECURE)
    }

    private fun applySubtitleStyle(targetPlayerView: PlayerView, style: SubtitleStyle) {
        targetPlayerView.subtitleView?.let { subtitleView ->
            // Reset to defaults
            subtitleView.setUserDefaultStyle()
            subtitleView.setUserDefaultTextSize()

            // Apply custom styling
            if (style.fontSize > 0) {
                subtitleView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, style.fontSize.toFloat())
            }

            subtitleView.setPadding(
                style.paddingLeft,
                style.paddingTop,
                style.paddingRight,
                style.paddingBottom
            )

            if (style.opacity != 0.0f) {
                subtitleView.alpha = style.opacity
                subtitleView.visibility = android.view.View.VISIBLE
            } else {
                subtitleView.visibility = android.view.View.GONE
            }
        }
    }

    private fun updateLiveUi() {
        val player = playerView.player ?: return
        val isLive = player.isCurrentMediaItemLive
        val seekable = player.isCurrentMediaItemSeekable

        // Show/hide badge
        liveBadge.visibility = if (isLive) View.VISIBLE else View.GONE

        // Disable/enable scrubbing based on seekable
        val timeBar = playerView.findViewById<DefaultTimeBar?>(androidx.media3.ui.R.id.exo_progress)
        timeBar?.isEnabled = !isLive || seekable
    }

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            playerView.post {
                playerView.requestLayout()
                // Reapply resize mode to ensure it's properly set after timeline changes
                pendingResizeMode?.let { resizeMode ->
                    playerView.resizeMode = resizeMode
                }
            }
            updateLiveUi()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED)
            ) {
                updateLiveUi()
            }

            // Handle video size changes which affect aspect ratio
            if (events.contains(Player.EVENT_VIDEO_SIZE_CHANGED)) {
                pendingResizeMode?.let { resizeMode ->
                    playerView.resizeMode = resizeMode
                }
                playerView.requestLayout()
                requestLayout()
            }
        }
    }

    companion object {
        private const val TAG = "ExoPlayerView"
    }

    /**
     * React Native (Yoga) can sometimes defer layout passes that are required by
     * PlayerView for its child views (controller overlay, surface view, subtitle view, …).
     * This helper forces a second measure / layout after RN finishes, ensuring the
     * internal views receive the final size. The same approach is used in the v7
     * implementation (see VideoView.kt) and in React Native core (Toolbar example [link]).
     */
    private val layoutRunnable = Runnable {
        measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        layout(left, top, right, bottom)
    }

    override fun requestLayout() {
        super.requestLayout()
        // Post a second layout pass so the ExoPlayer internal views get correct bounds.
        post(layoutRunnable)
    }

    /** Пересчитывает отступы при ориентации, fullscreen и изменении размеров view. */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateHeaderInsets(ViewCompat.getRootWindowInsets(this))
        updateNoticeInsets(ViewCompat.getRootWindowInsets(this))

        if (changed) {
            pendingResizeMode?.let { resizeMode ->
                playerView.resizeMode = resizeMode
            }
        }
    }
}
