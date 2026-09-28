package io.github.romanvht.byedpi.adapters

import android.annotation.SuppressLint
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.strategy.Decision
import io.github.romanvht.byedpi.strategy.DecisionKind

class DecisionAdapter : RecyclerView.Adapter<DecisionAdapter.ViewHolder>() {

    private val decisions = mutableListOf<Decision>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val accent: View = view.findViewById(R.id.accentView)
        val kind: TextView = view.findViewById(R.id.kindTextView)
        val time: TextView = view.findViewById(R.id.timeTextView)
        val title: TextView = view.findViewById(R.id.titleTextView)
        val detail: TextView = view.findViewById(R.id.detailTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_decision, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = decisions[position]
        val context = holder.itemView.context
        val color = ContextCompat.getColor(context, colorOf(item.kind))
        holder.accent.setBackgroundColor(color)
        holder.kind.setTextColor(color)
        holder.kind.setText(labelOf(item.kind))
        holder.time.text = DateFormat.format("HH:mm:ss", item.time)
        holder.title.text = item.title
        holder.detail.text = item.detail
        holder.detail.visibility = if (item.detail.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun getItemCount() = decisions.size

    @SuppressLint("NotifyDataSetChanged")
    fun submit(items: List<Decision>) {
        decisions.clear()
        decisions.addAll(items)
        notifyDataSetChanged()
    }

    private fun colorOf(kind: DecisionKind) = when (kind) {
        DecisionKind.Run -> R.color.kind_run
        DecisionKind.Order -> R.color.kind_order
        DecisionKind.Skip -> R.color.kind_skip
        DecisionKind.Seed -> R.color.kind_seed
        DecisionKind.Try -> R.color.kind_try
        DecisionKind.Result -> R.color.kind_result
        DecisionKind.Stop -> R.color.kind_stop
        DecisionKind.Network -> R.color.kind_network
        DecisionKind.Switch -> R.color.kind_switch
    }

    private fun labelOf(kind: DecisionKind) = when (kind) {
        DecisionKind.Run -> R.string.kind_run
        DecisionKind.Order -> R.string.kind_order
        DecisionKind.Skip -> R.string.kind_skip
        DecisionKind.Seed -> R.string.kind_seed
        DecisionKind.Try -> R.string.kind_try
        DecisionKind.Result -> R.string.kind_result
        DecisionKind.Stop -> R.string.kind_stop
        DecisionKind.Network -> R.string.kind_network
        DecisionKind.Switch -> R.string.kind_switch
    }
}
