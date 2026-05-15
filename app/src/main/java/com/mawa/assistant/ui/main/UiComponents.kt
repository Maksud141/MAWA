package com.mawa.assistant.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mawa.assistant.R

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_MAWA = 1
    }

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        if (!message.isUser && messages.isNotEmpty()) {
            val last = messages.last()
            if (!last.isUser && last.text == message.text) return
        }
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun lastMawaText(): String? {
        return messages.lastOrNull { !it.isUser }?.text
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) TYPE_USER else TYPE_MAWA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_USER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_myra, parent, false)
            MawaViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> holder.textView.text = msg.text
            is MawaViewHolder -> holder.textView.text = msg.text
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.chatUserText)
    }

    class MawaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.chatMawaText)
    }
}
