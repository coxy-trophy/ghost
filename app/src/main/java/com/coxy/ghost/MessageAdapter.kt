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

        // An assistant row with nothing in it yet is her thinking, not an empty message. The row
        // shows a pulsing mark instead of blank space until the first token lands, and swaps to the
        // text the moment it does.
        if (holder is AssistantMessageViewHolder) {
            val dot = holder.itemView.findViewById<View>(R.id.msg_thinking)
            val thinking = message.content.isEmpty()
            dot.visibility = if (thinking) View.VISIBLE else View.GONE
            holder.itemView.findViewById<TextView>(R.id.msg_content).visibility =
                if (thinking) View.GONE else View.VISIBLE
            if (thinking) startPulse(dot) else dot.clearAnimation()
        }

        if (holder is AssistantMessageViewHolder) {
            // Hidden while there is nothing to copy, so an empty row does not offer an action that
            // would put an empty string on the clipboard.
            holder.itemView.findViewById<View>(R.id.msg_copy).apply {
                visibility = if (message.content.isEmpty()) View.GONE else View.VISIBLE
                setOnClickListener { copy(it, com.coxy.ghost.action.ActionRunner.forDisplay(message.content)) }
            }
        }

        if (holder is UserMessageViewHolder || holder is AssistantMessageViewHolder) {
            val textView = holder.itemView.findViewById<TextView>(R.id.msg_content)
            // Rendered, not printed. Models emphasise with asterisks whether or not anything asked
            // them to, and the bubbles were showing the stars.
            // The quote colour comes from the palette rather than from Markup, so a quoted line
            // dims correctly in both light and dark instead of against one hardcoded ground.
            textView.text = com.coxy.ghost.ui.Markup.render(
                if (holder is AssistantMessageViewHolder) com.coxy.ghost.action.ActionRunner.forDisplay(message.content) else message.content,
                androidx.core.content.ContextCompat.getColor(
                    textView.context, com.coxy.ghost.R.color.on_surface_variant
                )
            )
        }
    }

    /**
     * Put her words on the clipboard.
     *
     * The raw text she wrote, not what is on screen: the rendered version has had its asterisks
     * and its numbering taken out, and markdown is almost certainly what the place it is being
     * pasted into wants back.
     */
    private fun copy(view: View, text: String) {
        val cm = view.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("GHOST", text))
        // Android 13 and up shows its own clipboard confirmation, so a toast here would be the
        // second one telling you the same thing.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(
                view.context, R.string.ghost_copied, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * The waiting mark, breathing.
     *
     * A view animation rather than a ValueAnimator or a coroutine: it is owned by the View, so it
     * stops on its own when the row is recycled or detached and there is nothing to cancel. The
     * previous animation is cleared first, because a recycled row can arrive already pulsing and
     * two overlapping alpha animations on one view produce a flicker rather than a fade.
     */
    private fun startPulse(view: View) {
        if (view.animation != null && !view.animation.hasEnded()) return
        view.clearAnimation()
        view.startAnimation(
            android.view.animation.AlphaAnimation(1f, 0.25f).apply {
                duration = 620L
                repeatMode = android.view.animation.Animation.REVERSE
                repeatCount = android.view.animation.Animation.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
        )
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