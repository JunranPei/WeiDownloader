package cn.weibo.carousel

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.color.MaterialColors

internal class CarouselMediaAdapter(
    private val onSelectionChanged: (CarouselMediaItem, Boolean) -> Unit,
    private val onItemClick: (CarouselMediaItem) -> Unit,
) : RecyclerView.Adapter<CarouselMediaAdapter.MediaViewHolder>() {

    private var items: List<CarouselMediaItem> = emptyList()
    private var cardWidthPx: Int = 0
    private var cardSpacingPx: Int = 0
    var thumbnailBinder: ((android.widget.ImageView, String, String) -> Unit)? = null

    fun setCardGeometry(widthPx: Int, spacingPx: Int) {
        if (widthPx <= 0) return
        if (widthPx == cardWidthPx && spacingPx == cardSpacingPx) return
        cardWidthPx = widthPx
        cardSpacingPx = spacingPx.coerceAtLeast(0)
        notifyDataSetChanged()
    }

    fun submitItems(value: List<CarouselMediaItem>) {
        items = value.toList()
        notifyDataSetChanged()
    }

    fun updateItems(value: List<CarouselMediaItem>) {
        items = value.toList()
        notifyDataSetChanged()
    }

    fun updateSelection(id: String, selected: Boolean) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0 || items[index].selected == selected) return
        items = items.toMutableList().also { it[index] = it[index].copy(selected = selected) }
        notifyItemChanged(index)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(cn.weibo.carousel.R.layout.wc_item_media, parent, false)
        applyCardSize(view)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        applyCardSize(holder.itemView)
        holder.bind(items[position])
    }

    private fun applyCardSize(view: View) {
        if (cardWidthPx <= 0) return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.width = cardWidthPx
        params.height = dp(view.context, 280f)
        params.marginEnd = cardSpacingPx
        view.layoutParams = params
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val context: Context = itemView.context
        private val root = itemView.findViewById<MaskableFrameLayout>(cn.weibo.carousel.R.id.carouselCard)
        private val thumbnail = itemView.findViewById<android.widget.ImageView>(cn.weibo.carousel.R.id.carouselThumbnail)
        private val title = itemView.findViewById<TextView>(cn.weibo.carousel.R.id.carouselTitle)
        private val subtitle = itemView.findViewById<TextView>(cn.weibo.carousel.R.id.carouselSubtitle)
        private val selection = itemView.findViewById<ImageButton>(cn.weibo.carousel.R.id.carouselSelection)
        private var boundItem: CarouselMediaItem? = null

        init {
            root.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(28f).toFloat())
                .build()
            // Keep the affordance tied to the card's visible width. The
            // reusable view calls setVisibleWidth() while it interpolates its
            // fixed keylines, so the circle cannot protrude from a narrow card.
            root.setOnMaskChangedListener { mask ->
                if (root.width == 0) selection.visibility = View.INVISIBLE
            }
            itemView.setOnClickListener {
                val item = boundItem ?: return@setOnClickListener
                onItemClick(item)
            }
            selection.setOnClickListener {
                val item = boundItem ?: return@setOnClickListener
                if (!item.selectable) return@setOnClickListener
                onSelectionChanged(item, !item.selected)
            }
        }

        fun bind(item: CarouselMediaItem) {
            boundItem = item
            title.text = item.title
            subtitle.text = item.subtitle
            selection.isEnabled = item.selectable
            selection.contentDescription = context.getString(
                if (item.selected) cn.weibo.carousel.R.string.wc_deselect_media
                else cn.weibo.carousel.R.string.wc_select_media,
            )
            renderSelection(item)
            setVisibleWidth(root.width)
            if (thumbnailBinder != null) {
                thumbnailBinder?.invoke(thumbnail, item.imageUrl, item.fallbackImageUrl)
            } else {
                thumbnail.setImageDrawable(null)
                thumbnail.alpha = 0f
            }
        }

        private fun renderSelection(item: CarouselMediaItem) {
            val primary = MaterialColors.getColor(selection, R.attr.colorPrimary)
            val onPrimary = MaterialColors.getColor(selection, R.attr.colorOnPrimary)
            val outlineVariant = MaterialColors.getColor(selection, R.attr.colorOutlineVariant)
            val surfaceContainerLow = MaterialColors.getColor(selection, R.attr.colorSurfaceContainerLow)
            val surfaceContainer = MaterialColors.getColor(selection, R.attr.colorSurfaceContainerHigh)
            val onSurfaceVariant = MaterialColors.getColor(selection, R.attr.colorOnSurfaceVariant)
            val background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                when {
                    item.selected -> setColor(primary)
                    item.downloaded -> setColor(surfaceContainer)
                    else -> setColor(surfaceContainerLow)
                }
            }
            if (!item.downloaded && !item.selected) background.setStroke(dp(1f), outlineVariant)
            selection.background = background
            selection.setImageDrawable(
                if (item.selected || item.downloaded) {
                    ContextCompat.getDrawable(context, cn.weibo.carousel.R.drawable.wc_check)?.mutate()?.also {
                        it.setTint(if (item.selected) onPrimary else onSurfaceVariant)
                    }
                } else null,
            )
            selection.alpha = 1f
        }

        fun setVisibleWidth(widthPx: Int) {
            val hideBelow = dp(56f)
            selection.visibility = if (widthPx >= hideBelow) View.VISIBLE else View.INVISIBLE
            selection.alpha = if (widthPx >= hideBelow) 1f else 0f
        }

        private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
