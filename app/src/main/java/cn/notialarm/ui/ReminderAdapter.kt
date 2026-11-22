package cn.notialarm.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import cn.notialarm.Fmt
import cn.notialarm.R
import cn.notialarm.data.Reminder
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * 提醒列表：三按钮 + 点按看全文 + 长按删除。
 * 入场用 220ms 淡入 + 轻微上移，只对第一次出现的条目播放，避免每次切页都动。
 */
class ReminderAdapter(
    private val onOpenDetail: (Reminder) -> Unit,
    private val onLongPress: (Reminder) -> Unit,
    private val onEdit: (Reminder) -> Unit,
    private val onOpen: (Reminder) -> Unit,
    private val onConfirm: (Reminder) -> Unit
) : RecyclerView.Adapter<ReminderAdapter.Holder>() {

    private val items = mutableListOf<Reminder>()

    fun submit(list: List<Reminder>) {
        val old = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].id == list[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos] == list[newPos]
        })
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    fun removeAt(position: Int): Reminder? {
        if (position !in items.indices) return null
        val removed = items.removeAt(position)
        notifyItemRemoved(position)
        return removed
    }

    fun itemAt(position: Int): Reminder? = items.getOrNull(position)

    fun indexOf(id: Int): Int = items.indexOfFirst { it.id == id }

    /**
     * RecyclerView 被放在 ScrollView 里，wrap_content 只会量到首屏能放下的几条，
     * 数据再多也只显示前两三张卡片。这里按真实卡片高度算出总高度，交给外层 ScrollView 滚动。
     */
    fun contentHeight(parent: ViewGroup, width: Int): Int {
        var total = 0
        for (i in items.indices) {
            val holder = onCreateViewHolder(parent, getItemViewType(i))
            onBindViewHolder(holder, i)
            val lp = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
            holder.itemView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            total += holder.itemView.measuredHeight + lp.topMargin + lp.bottomMargin
        }
        return total
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reminder, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        holder.bind(r)
        holder.card.setOnClickListener { onOpenDetail(r) }
        holder.card.setOnLongClickListener {
            onLongPress(r)
            true
        }
        holder.btnEdit.setOnClickListener { onEdit(r) }
        holder.btnOpen.setOnClickListener { onOpen(r) }
        holder.btnConfirm.setOnClickListener { onConfirm(r) }

        // 新条目直接显示：入场动画会被随后的滚动/重新绑定取消，留下 alpha=0 的“隐形卡片”，
        // 用户就会以为“创建了但列表里没有”。这里优先保证可见性。
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val card: MaterialCardView = itemView.findViewById(R.id.cardRoot)
        val accent: View = itemView.findViewById(R.id.accent)
        val title: TextView = itemView.findViewById(R.id.title)
        val time: TextView = itemView.findViewById(R.id.time)
        val meta: TextView = itemView.findViewById(R.id.meta)
        val btnEdit: MaterialButton = itemView.findViewById(R.id.btnEdit)
        val btnOpen: MaterialButton = itemView.findViewById(R.id.btnOpen)
        val btnConfirm: MaterialButton = itemView.findViewById(R.id.btnConfirm)

        fun bind(r: Reminder) {
            title.text = r.title
            time.text = Fmt.dateTime(r.time)
            meta.text = buildString {
                append(Fmt.offsets(r.offsets))
                append("\n")
                append(itemView.context.getString(R.string.source_fmt, r.source))
                if (r.archived) append(itemView.context.getString(R.string.detail_archived_suffix))
            }
            val accentColor = if (r.archived) {
                itemView.context.getColor(R.color.app_outline)
            } else {
                itemView.context.getColor(R.color.brand_primary)
            }
            accent.setBackgroundColor(accentColor)
            if (r.archived) {
                btnEdit.text = itemView.context.getString(R.string.action_restore)
                btnConfirm.text = itemView.context.getString(R.string.action_delete)
            } else {
                btnEdit.text = itemView.context.getString(R.string.action_edit)
                btnConfirm.text = itemView.context.getString(R.string.action_confirm_off)
            }
        }
    }
}
