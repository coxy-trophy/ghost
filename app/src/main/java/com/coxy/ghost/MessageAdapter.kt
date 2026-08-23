package com.coxy.ghost

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Message(
    val id: String,
    val content: String,
    val isUser: Boolean
)

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = layoutInflater.inflate(R.layout.item_message_user, parent, false)
            UserMessageViewHolder(view)
        } else {
            val view = layoutInflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is UserMessageViewHolder || holder is AssistantMessageViewHolder) {
            val textView = holder.itemView.findViewById<TextView>(R.id.msg_content)
            // Rendered, not printed. Models emphasise with asterisks whether or not anything asked
            // them to, and the bubbles were showing the stars.
            textView.text = com.coxy.ghost.ui.Markup.inline(message.content)
        }
    }

    override fun getItemCount(): Int = messages.size

    /**
     * Bubbles rise into place: fade in while sliding up a little.
     *
     * Deliberately hooked to *attach*, not bind. The typed path rewrites the last message on every
     * streamed token, so animating in onBindViewHolder would make GHOST's reply strobe as it types.
     * A view attaches once when it enters the window, which is exactly when the entrance belongs.
     * `lastAnimatedPosition` stops old bubbles re-animating when they scroll back into view.
     */
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || position <= lastAnimatedPosition) return
        lastAnimatedPosition = position

        holder.itemView.apply {
            alpha = 0f
            translationY = 28f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private var lastAnimatedPosition = -1

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}