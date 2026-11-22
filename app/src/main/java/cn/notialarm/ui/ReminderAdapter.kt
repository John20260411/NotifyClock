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
    private val pendingEnter = mutableSetOf<Int>()

    fun submit(list: List<Reminder>) {
        val old = items.toList()
        val oldIds = old.map { it.id }.toSet()
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
        pendingEnter.clear()
        // 只有“刚新增一条”才播入场动画；切待处理/归档页不重播，避免整页闪。
        if (list.size == old.size + 1) {
            list.firstOrNull { it.id !in oldIds }?.let { pendingEnter.add(it.id) }
        }
        diff.dispatchUpdatesTo(this)
    }

    fun removeAt(position: Int): Reminder? {
        if (position !in items.indices) return null
        val removed = items.removeAt(position)
        notifyItemRemoved(position)
        return removed
    }

    fun itemAt(position: Int): Reminder? = items.getOrNull(position)

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

        if (pendingEnter.remove(r.id)) {
            animateEnter(holder.itemView, position)
        } else {
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
        }
    }

    private fun animateEnter(view: View, position: Int) {
        val density = view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 10f * density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((position % 6) * 35L)
            .setDuration(240L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
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
                append("\n来自 ")
                append(r.source)
                if (r.archived) append(" · 已归档")
            }
            val accentColor = if (r.archived) {
                itemView.context.getColor(R.color.app_outline)
            } else {
                itemView.context.getColor(R.color.brand_primary)
            }
            accent.setBackgroundColor(accentColor)
            if (r.archived) {
                btnEdit.text = "恢复"
                btnConfirm.text = "删除"
            } else {
                btnEdit.text = "修改"
                btnConfirm.text = "彻底关闭"
            }
        }
    }
}
