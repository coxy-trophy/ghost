package com.coxy.ghost.ui

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * The orb and the greeting, as the first item of the transcript rather than an empty state.
 *
 * It was an empty state until v0.14.1, and on a real device that meant it was never seen: GHOST
 * always has history, so the signature element of the whole screen only existed on a fresh install.
 * As a header it is where the conversation starts and you scroll past it into what was said.
 *
 * [hero] is inflated once by the activity and handed in, not inflated here. MainActivity holds
 * `orb` for the lifetime of the screen and drives its state from a dozen places; re-inflating on
 * recycle would leave every one of those references pointing at a view that is no longer on screen.
 * Since there is exactly one item, holding one view costs nothing and keeps those references true.
 */
class ChatHeroAdapter(private val hero: View) : RecyclerView.Adapter<ChatHeroAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // The same instance comes back after the header scrolls off, and RecyclerView will not
        // accept a view that still belongs to someone else.
        (hero.parent as? ViewGroup)?.removeView(hero)
        return Holder(hero).apply { setIsRecyclable(false) }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = Unit

    override fun getItemCount(): Int = 1
}
