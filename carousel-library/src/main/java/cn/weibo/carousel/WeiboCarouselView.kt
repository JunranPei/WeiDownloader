package cn.weibo.carousel

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A deterministic multi-browse carousel.
 *
 * Material's RecyclerView carousel is excellent for a normal scrolling rail,
 * but it continuously re-solves all keylines while a touch is in progress.
 * That is the wrong model for this component: the focused card must shrink out
 * and the next card must grow into the exact same keyline.  This view therefore
 * keeps one explicit slot for each card and interpolates only those slots.
 */
class WeiboCarouselView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private data class Slot(val left: Float, val width: Float)

    private data class TransitionGeometry(
        val startFocus: Int,
        val targetFocus: Int,
        val from: Array<Slot>,
        val bridge: Array<Slot>?,
        val to: Array<Slot>,
        val bridgeProgress: Float,
    )

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val carouselAdapter: CarouselMediaAdapter
    private val holders = mutableListOf<CarouselMediaAdapter.MediaViewHolder>()
    private var items: List<CarouselMediaItem> = emptyList()
    private var currentFocus = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var transitionProgress = 0f
    private var activeTransition: TransitionGeometry? = null
    private var transitionToken = 0
    private var animator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null

    private var gapPx = 0f
    private var edgeInsetPx = 0f
    private var largePx = 0f
    private var mediumPx = 0f
    private var smallPx = 0f
    private var edgeMediumPx = 0f
    private var edgeSmallPx = 0f
    private var twoLargePx = 0f
    private var twoSmallPx = 0f

    var onSelectionChanged: ((CarouselMediaItem, Boolean) -> Unit)? = null
    var onItemClick: ((CarouselMediaItem) -> Unit)? = null
    var onFocusChanged: ((CarouselMediaItem, Int) -> Unit)? = null
    var thumbnailBinder: ((android.widget.ImageView, String, String) -> Unit)?
        get() = carouselAdapter.thumbnailBinder
        set(value) {
            carouselAdapter.thumbnailBinder = value
            rebindCards()
        }

    init {
        carouselAdapter = CarouselMediaAdapter(
            onSelectionChanged = { item, selected ->
                val next = items.map { if (it.id == item.id) it.copy(selected = selected) else it }
                items = next
                carouselAdapter.updateSelection(item.id, selected)
                rebindCard(item.id)
                onSelectionChanged?.invoke(item.copy(selected = selected), selected)
            },
            onItemClick = { item -> onItemClick?.invoke(item) },
        )
        clipChildren = true
        clipToPadding = true
        isClickable = true
        setWillNotDraw(true)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        measureGeometry(width)
        activeTransition = activeTransition?.let {
            buildTransition(it.startFocus, it.targetFocus)
        }
        if (holders.isNotEmpty()) renderCurrentState()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Card children are positioned by applyLayout(). Calling
        // FrameLayout.onLayout() here would apply their temporary XML
        // LayoutParams (width 0) again and erase the keyline geometry.
        if (holders.isNotEmpty()) renderCurrentState()
    }

    private fun measureGeometry(viewportWidth: Int) {
        val width = viewportWidth.coerceAtLeast(1)
        gapPx = (width * 0.035f).coerceIn(dp(10f).toFloat(), dp(18f).toFloat())
        edgeInsetPx = dp(20f).toFloat()

        // The three ordinary keylines fill the width between the two gaps.
        // Keeping these values in pixels makes the animation independent from
        // density and from the number of media files in the post.
        val available = (width - 2f * gapPx).coerceAtLeast(1f)
        smallPx = (available * 0.10f).coerceIn(dp(28f).toFloat(), dp(120f).toFloat())
        mediumPx = (available * 0.35f).coerceIn(
            dp(120f).toFloat(),
            (available - smallPx).coerceAtLeast(1f),
        )
        largePx = (available - mediumPx - smallPx).coerceAtLeast(1f)

        // At the two ends the 20dp screen inset is preserved.  The medium and
        // small cards share the resulting reduction, so neither one jumps.
        val edgeAvailable = (width - edgeInsetPx - 2f * gapPx).coerceAtLeast(1f)
        val pairWidth = (mediumPx + smallPx).coerceAtLeast(1f)
        val edgeScale = ((edgeAvailable - largePx) / pairWidth).coerceIn(0f, 1f)
        edgeMediumPx = mediumPx * edgeScale
        edgeSmallPx = (edgeAvailable - largePx - edgeMediumPx).coerceAtLeast(1f)

        // One- and two-item layouts keep the same 20dp edge inset used by
        // the first and last keylines of a larger collection.
        val twoAvailable = (width - 2f * edgeInsetPx - gapPx).coerceAtLeast(2f)
        twoSmallPx = twoAvailable * 0.30f
        twoLargePx = twoAvailable - twoSmallPx
    }

    fun submitItems(value: List<CarouselMediaItem>) {
        stopTransition()
        items = value.toList()
        currentFocus = 0
        carouselAdapter.submitItems(items)
        rebuildCards()
        notifyFocusChanged()
        post { applyStableLayout(currentFocus) }
    }

    fun updateItems(value: List<CarouselMediaItem>) {
        stopTransition()
        items = value.toList()
        currentFocus = currentFocus.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        carouselAdapter.updateItems(items)
        rebuildCards()
        notifyFocusChanged()
        post { applyStableLayout(currentFocus) }
    }

    fun selectedIds(): Set<String> = items.filter { it.selected }.mapTo(LinkedHashSet()) { it.id }

    /** Updates only the selection-circle indicators without rebuilding media cards. */
    fun updateDownloadProgress(values: Map<String, Int?>) {
        val next = items.map { item -> item.copy(downloadProgress = values[item.id]) }
        if (next == items) return
        items = next
        carouselAdapter.updateItems(items)
        holders.forEachIndexed { index, holder ->
            items.getOrNull(index)?.let(holder::updateDownloadState)
        }
    }

    private fun rebuildCards() {
        holders.clear()
        removeAllViews()
        items.forEachIndexed { index, _ ->
            val holder = carouselAdapter.onCreateViewHolder(this, 0)
            holder.itemView.layoutParams = FrameLayout.LayoutParams(0, measuredCardHeight())
            carouselAdapter.onBindViewHolder(holder, index)
            holders += holder
            addView(holder.itemView)
        }
        if (width > 0) applyStableLayout(currentFocus)
    }

    private fun rebindCards() {
        holders.forEachIndexed { index, holder ->
            if (index < items.size) carouselAdapter.onBindViewHolder(holder, index)
        }
    }

    private fun rebindCard(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index in holders.indices) carouselAdapter.onBindViewHolder(holders[index], index)
    }

    private fun measuredCardHeight(): Int = height.takeIf { it > 0 } ?: dp(280f)

    private fun stableLayout(focus: Int): Array<Slot> {
        val slots = Array(items.size) { Slot(0f, 0f) }
        if (items.isEmpty()) return slots
        val f = focus.coerceIn(0, items.lastIndex)

        fun put(index: Int, left: Float, width: Float) {
            if (index in slots.indices) slots[index] = Slot(left, width.coerceAtLeast(0f))
        }

        when {
            items.size == 1 -> {
                put(0, edgeInsetPx, (width - 2f * edgeInsetPx).coerceAtLeast(1f))
            }
            items.size == 2 && f == 0 -> {
                put(0, edgeInsetPx, twoLargePx)
                put(1, edgeInsetPx + twoLargePx + gapPx, twoSmallPx)
            }
            items.size == 2 -> {
                put(0, edgeInsetPx, twoSmallPx)
                put(1, edgeInsetPx + twoSmallPx + gapPx, twoLargePx)
            }
            f == 0 -> {
                put(0, edgeInsetPx, largePx)
                put(1, edgeInsetPx + largePx + gapPx, edgeMediumPx)
                put(2, edgeInsetPx + largePx + gapPx + edgeMediumPx + gapPx, edgeSmallPx)
            }
            f <= items.lastIndex - 2 -> {
                put(f, 0f, largePx)
                put(f + 1, largePx + gapPx, mediumPx)
                put(f + 2, largePx + gapPx + mediumPx + gapPx, smallPx)
            }
            f == items.lastIndex - 1 -> {
                put(f - 1, 0f, mediumPx)
                put(f, mediumPx + gapPx, largePx)
                put(f + 1, mediumPx + gapPx + largePx + gapPx, smallPx)
            }
            else -> {
                put(f - 2, 0f, edgeSmallPx)
                put(f - 1, edgeSmallPx + gapPx, edgeMediumPx)
                put(f, edgeSmallPx + gapPx + edgeMediumPx + gapPx, largePx)
            }
        }
        return slots
    }

    /** The fixed keyline between two stable states. */
    private fun forwardBridge(oldFocus: Int): Array<Slot> {
        val slots = stableLayout((oldFocus + 1).coerceAtMost(items.lastIndex))
        if (oldFocus !in items.indices) return slots
        slots[oldFocus] = Slot(0f, 0f)
        if (oldFocus + 1 in slots.indices) slots[oldFocus + 1] = Slot(gapPx, largePx)
        if (oldFocus + 2 in slots.indices) {
            slots[oldFocus + 2] = Slot(gapPx + largePx + gapPx, mediumPx)
        }
        if (oldFocus + 3 in slots.indices) {
            slots[oldFocus + 3] = Slot(
                gapPx + largePx + gapPx + mediumPx + gapPx,
                (smallPx - gapPx).coerceAtLeast(0f),
            )
        }
        return slots
    }

    private fun backwardBridge(oldFocus: Int): Array<Slot> {
        val target = (oldFocus - 1).coerceAtLeast(0)
        val slots = stableLayout(target)
        if (target in slots.indices) slots[target] = Slot(0f, (largePx - gapPx).coerceAtLeast(0f))
        if (target + 1 in slots.indices) slots[target + 1] = Slot(largePx, mediumPx)
        if (target + 2 in slots.indices) {
            slots[target + 2] = Slot(largePx + mediumPx + gapPx, smallPx)
        }
        if (target + 3 in slots.indices) slots[target + 3] = Slot(width.toFloat(), 0f)
        return slots
    }

    private fun shouldUseBridge(startFocus: Int, delta: Int): Boolean {
        if (items.size < 4 || abs(delta) != 1) return false
        return if (delta > 0) {
            startFocus <= items.lastIndex - 3
        } else {
            startFocus in 1..(items.lastIndex - 2)
        }
    }

    private fun buildTransition(startFocus: Int, targetFocus: Int): TransitionGeometry {
        val delta = targetFocus - startFocus
        val from = stableLayout(startFocus)
        val to = stableLayout(targetFocus)

        // A hidden slot must retain the edge from which it enters or leaves.
        // Using Slot(0, 0) for every hidden card made a new right-hand card
        // appear to fly from the disappearing card on the left.
        for (index in from.indices) {
            if (from[index].width <= 0f && to[index].width > 0f) {
                from[index] = Slot(if (delta > 0) width.toFloat() else 0f, 0f)
            }
            if (from[index].width > 0f && to[index].width <= 0f) {
                to[index] = Slot(if (delta > 0) 0f else width.toFloat(), 0f)
            }
        }

        val bridge = if (shouldUseBridge(startFocus, delta)) {
            if (delta > 0) forwardBridge(startFocus) else backwardBridge(startFocus)
        } else null
        val bridgeProgress = if (bridge == null) {
            0.5f
        } else {
            // Match the two phase durations to the distance travelled by the
            // primary card. This keeps its velocity continuous when the old
            // card has vanished and the remaining gap begins to close.
            val anchor = if (delta > 0) targetFocus else startFocus
            val totalDistance = abs(to[anchor].left - from[anchor].left)
            val firstDistance = abs(bridge[anchor].left - from[anchor].left)
            if (totalDistance > 0.5f) {
                (firstDistance / totalDistance).coerceIn(0.08f, 0.96f)
            } else {
                0.5f
            }
        }
        return TransitionGeometry(startFocus, targetFocus, from, bridge, to, bridgeProgress)
    }

    private fun updateDrag(pointerX: Float) {
        if (items.size < 2 || width <= 0) return
        val dx = pointerX - downX
        val direction = if (dx < 0f) 1 else -1
        val target = (currentFocus + direction).coerceIn(0, items.lastIndex)
        if (target == currentFocus) {
            activeTransition = null
            transitionProgress = 0f
            applyStableLayout(currentFocus)
            return
        }

        val transition = activeTransition
            ?.takeIf { it.startFocus == currentFocus && it.targetFocus == target }
            ?: buildTransition(currentFocus, target).also { activeTransition = it }
        val travel = (width * 0.50f).coerceAtLeast(dp(140f).toFloat())
        transitionProgress = (abs(dx) / travel).coerceIn(0f, 1f)
        applyTransition(transition, transitionProgress)
    }

    private fun settleDrag(
        commit: Boolean,
        releaseVelocityX: Float = 0f,
        releaseTarget: Int? = null,
    ) {
        val firstTransition = activeTransition
        if (firstTransition == null) {
            dragging = false
            applyStableLayout(currentFocus)
            return
        }

        val startProgress = transitionProgress
        val endProgress = if (commit) 1f else 0f
        val requestedAdjacentTarget = releaseTarget == null || releaseTarget == firstTransition.targetFocus
        if (abs(endProgress - startProgress) < 0.001f && (!commit || requestedAdjacentTarget)) {
            completeTransition(firstTransition, commit)
            return
        }

        if (!commit) {
            animateTransitionPath(
                transitions = listOf(firstTransition),
                startPath = startProgress,
                endPath = 0f,
                releaseVelocityX = releaseVelocityX,
                commit = false,
            )
            return
        }

        // A fling may select a focus several cards away, but every visible
        // frame must still follow the same adjacent-card keyline transition.
        // Interpolating directly between distant stable layouts compressed
        // the entire row and made the entering card jump across the screen.
        val direction = if (firstTransition.targetFocus > currentFocus) 1 else -1
        val requestedTarget = (releaseTarget ?: firstTransition.targetFocus).coerceIn(0, items.lastIndex)
        val finalTarget = if (direction > 0) {
            max(requestedTarget, firstTransition.targetFocus)
        } else {
            min(requestedTarget, firstTransition.targetFocus)
        }
        val transitions = mutableListOf(firstTransition)
        var stepFocus = firstTransition.targetFocus
        while (stepFocus != finalTarget) {
            val nextFocus = stepFocus + direction
            transitions += buildTransition(stepFocus, nextFocus)
            stepFocus = nextFocus
        }

        animateTransitionPath(
            transitions = transitions,
            startPath = startProgress,
            endPath = transitions.size.toFloat(),
            releaseVelocityX = releaseVelocityX,
            commit = true,
        )
    }

    private fun animateTransitionPath(
        transitions: List<TransitionGeometry>,
        startPath: Float,
        endPath: Float,
        releaseVelocityX: Float,
        commit: Boolean,
    ) {
        if (transitions.isEmpty()) return

        val token = ++transitionToken
        val settle = ValueAnimator.ofFloat(startPath, endPath).apply {
            val remaining = abs(endPath - startPath)
            val travel = (width * 0.50f).coerceAtLeast(dp(140f).toFloat())
            val progressPerMillisecond = abs(releaseVelocityX) / travel / 1000f
            val cardCount = transitions.size.coerceAtLeast(1)
            val minimumDuration = 145L + 70L * (cardCount - 1)
            duration = if (progressPerMillisecond > 0.0004f) {
                (remaining / progressPerMillisecond).roundToInt().coerceIn(minimumDuration.toInt(), 520).toLong()
            } else {
                (105L + 165L * remaining + 45L * (cardCount - 1)).roundToInt().coerceAtMost(520).toLong()
            }
            // Preserve the release momentum, then ease gently into the exact
            // keyline. The animation exists only while settling after touch.
            interpolator = PathInterpolator(0.16f, 0.72f, 0.22f, 1f)
            addUpdateListener { valueAnimator ->
                if (token == transitionToken && animator === valueAnimator) {
                    val path = valueAnimator.animatedValue as Float
                    val lastIndex = transitions.lastIndex
                    val segmentIndex = if (path >= transitions.size) {
                        lastIndex
                    } else {
                        path.toInt().coerceIn(0, lastIndex)
                    }
                    val localProgress = if (path >= transitions.size) {
                        1f
                    } else {
                        (path - segmentIndex).coerceIn(0f, 1f)
                    }
                    val transition = transitions[segmentIndex]
                    activeTransition = transition
                    transitionProgress = localProgress
                    applyTransition(transition, localProgress)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    if (animator === animation) animator = null
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (token != transitionToken || animator !== animation) return
                    animator = null
                    completeTransition(if (commit) transitions.last() else transitions.first(), commit)
                }
            })
        }
        animator = settle
        settle.start()
    }

    private fun completeTransition(transition: TransitionGeometry, commit: Boolean) {
        val previousFocus = currentFocus
        currentFocus = if (commit) transition.targetFocus else transition.startFocus
        dragging = false
        activeTransition = null
        transitionProgress = 0f
        applyStableLayout(currentFocus)
        if (currentFocus != previousFocus) notifyFocusChanged()
    }

    private fun notifyFocusChanged() {
        items.getOrNull(currentFocus)?.let { onFocusChanged?.invoke(it, currentFocus) }
    }

    private fun applyTransition(transition: TransitionGeometry, progress: Float) {
        applyLayout(slotsAt(transition, progress))
    }

    private fun slotsAt(transition: TransitionGeometry, progress: Float): Array<Slot> {
        val value = progress.coerceIn(0f, 1f)
        val bridge = transition.bridge
        if (bridge == null) {
            return interpolatedSlots(transition.from, transition.to, value)
        }
        val split = transition.bridgeProgress
        if (value <= split) {
            return interpolatedSlots(transition.from, bridge, value / split.coerceAtLeast(0.001f))
        } else {
            return interpolatedSlots(
                bridge,
                transition.to,
                (value - split) / (1f - split).coerceAtLeast(0.001f),
            )
        }
    }

    private fun renderCurrentState() {
        val transition = activeTransition
        if (transition != null && (dragging || animator != null)) {
            applyTransition(transition, transitionProgress)
        } else {
            applyStableLayout(currentFocus)
        }
    }

    private fun applyStableLayout(focus: Int) {
        if (width <= 0 || holders.isEmpty()) return
        applyLayout(stableLayout(focus))
    }

    private fun applyInterpolated(from: Array<Slot>, to: Array<Slot>, fraction: Float) {
        applyLayout(interpolatedSlots(from, to, fraction))
    }

    private fun interpolatedSlots(from: Array<Slot>, to: Array<Slot>, fraction: Float): Array<Slot> {
        val eased = fraction.coerceIn(0f, 1f)
        val count = min(from.size, to.size)
        return Array(count) { index ->
            Slot(
                from[index].left + (to[index].left - from[index].left) * eased,
                from[index].width + (to[index].width - from[index].width) * eased,
            )
        }
    }

    private fun releaseTarget(dx: Float, velocityX: Float, travel: Float, threshold: Float): Int {
        if (items.isEmpty()) return currentFocus
        val direction = if (dx < 0f) 1 else -1
        val distanceSteps = (abs(dx) / travel).roundToInt().coerceAtLeast(1)
        val velocitySteps = (abs(velocityX) / threshold).roundToInt()
        val skip = max(distanceSteps, velocitySteps).coerceIn(1, 5)
        return (currentFocus + direction * skip).coerceIn(0, items.lastIndex)
    }

    private fun applyLayout(slots: Array<Slot>) {
        val cardHeight = measuredCardHeight()
        holders.forEachIndexed { index, holder ->
            val card = holder.itemView as? MaskableFrameLayout ?: return@forEachIndexed
            val slot = slots.getOrNull(index) ?: Slot(0f, 0f)
            val cardWidth = slot.width.roundToInt().coerceAtLeast(0)
            if (cardWidth <= 0) {
                card.visibility = View.INVISIBLE
                return@forEachIndexed
            }
            card.visibility = View.VISIBLE
            card.translationX = 0f
            card.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(cardHeight, View.MeasureSpec.EXACTLY),
            )
            val left = slot.left.roundToInt()
            card.layout(left, 0, left + cardWidth, cardHeight)

            // Keep one coherent rounded rectangle while the card narrows. The
            // radius is reduced only after it can no longer fit as a circle;
            // this avoids a clipped selection affordance or a corner pop.
            val radius = min(dp(28f).toFloat(), cardWidth / 2f)
            card.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, radius)
                .build()
            card.setMaskRectF(RectF(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat()))
            holder.setVisibleWidth(cardWidth)
        }
    }

    private fun stopTransition() {
        transitionToken++
        animator?.cancel()
        animator = null
        dragging = false
        activeTransition = null
        transitionProgress = 0f
        recycleVelocityTracker()
    }

    private fun beginVelocityTracking(event: MotionEvent) {
        recycleVelocityTracker()
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    private fun trackVelocity(event: MotionEvent) {
        velocityTracker?.addMovement(event)
    }

    private fun releaseVelocity(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000)
        val velocity = tracker.xVelocity
        recycleVelocityTracker()
        return velocity
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (animator != null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginVelocityTracking(event)
                downX = event.x
                downY = event.y
                dragging = false
                activeTransition = null
                transitionProgress = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                trackVelocity(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging) recycleVelocityTracker()
            }
        }
        return dragging
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (animator != null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (velocityTracker == null) beginVelocityTracking(event) else trackVelocity(event)
                downX = event.x
                downY = event.y
                dragging = false
                activeTransition = null
                transitionProgress = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                trackVelocity(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) updateDrag(event.x)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    trackVelocity(event)
                    val velocityX = releaseVelocity()
                    val transition = activeTransition
                    val flingThreshold = dp(420f).toFloat()
                    val velocityCommits = transition != null && if (transition.targetFocus > transition.startFocus) {
                        velocityX <= -flingThreshold
                    } else {
                        velocityX >= flingThreshold
                    }
                    val commit = transitionProgress >= 0.28f || velocityCommits
                    val target = if (commit) {
                        releaseTarget(event.x - downX, velocityX, (width * 0.50f).coerceAtLeast(dp(140f).toFloat()), flingThreshold)
                    } else {
                        null
                    }
                    settleDrag(commit, velocityX, target)
                } else {
                    recycleVelocityTracker()
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val velocityX = releaseVelocity()
                if (dragging) settleDrag(false, velocityX)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
}
