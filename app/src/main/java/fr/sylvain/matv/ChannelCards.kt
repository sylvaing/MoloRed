package fr.sylvain.matv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

/**
 * Channel "tile" (logo, number, name, current programme + progress) shared by the
 * home grid and the in-player channel strip.
 */
class ChannelCardAdapter(
    private val scope: CoroutineScope,
    private val fixedWidthPx: Int? = null,
    private val onClick: (Int) -> Unit,
    private val onFocus: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<ChannelCardAdapter.VH>() {

    /** serviceId of the channel currently playing: its number is highlighted. */
    var playingId: String? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val number: TextView = v.findViewById(R.id.number)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        fixedWidthPx?.let { v.layoutParams = v.layoutParams.apply { width = it } }
        val vh = VH(v)
        v.setOnClickListener { vh.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
            view.elevation = if (has) 12f else 0f
            if (has) vh.bindingAdapterPosition.takeIf { it >= 0 }?.let { onFocus?.invoke(it) }
        }
        return vh
    }

    override fun getItemCount() = Repo.channels.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = Repo.channels[position]
        h.number.text = ch.number.toString()
        h.number.setTextColor(
            h.itemView.context.getColor(if (ch.serviceId == playingId) R.color.accent else R.color.text_dim)
        )
        h.name.text = ch.name
        val now = Repo.nowNext(ch).first
        h.now.text = now?.title ?: ""
        if (now != null) {
            val pct = (System.currentTimeMillis() - now.start) * 100 / (now.end - now.start).coerceAtLeast(1)
            h.progress.progress = pct.toInt().coerceIn(0, 100)
            h.progress.visibility = View.VISIBLE
        } else {
            h.progress.visibility = View.INVISIBLE
        }
        Logos.load(scope, ch.logo, h.logo)
    }
}
