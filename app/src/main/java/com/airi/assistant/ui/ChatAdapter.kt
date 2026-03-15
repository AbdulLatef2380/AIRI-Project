package com.airi.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.airi.assistant.accessibility.BehaviorEngine // ✅ استيراد محرك السلوك

class ChatAdapter(
    private val onSuggestionClick: (String) -> Unit = {} // كولباك لتنفيذ الأمر عند الضغط
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val messages = mutableListOf<ChatModel>()

    override fun getItemViewType(position: Int): Int {
        // 1 للمستخدم، 2 للـ AI
        return if (messages[position].isUser) 1 else 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        // نستخدم التصميم البسيط حالياً كما في ملفك الأصلي
        val layout = if (viewType == 1) android.R.layout.simple_list_item_1 else android.R.layout.simple_list_item_2
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        val prefix = if (message.isUser) "أنت: " else "AIRI: "
        holder.textView.text = "$prefix${message.text}"

        // 🧠 منطق التعلم السلوكي (Reinforcement Learning)
        // إذا كانت الرسالة اقتراحاً (تبدأ بـ 💡) ولم تكن من المستخدم
        if (!message.isUser && message.text.contains("💡")) {
            holder.itemView.setOnClickListener {
                // استخراج النص الصافي للاقتراح لحفظه في الإحصائيات
                val cleanText = message.text
                    .replace("💡 اقتراح ذكي: ", "")
                    .replace("💡 اقتراح: ", "")
                    .replace("💡", "")
                    .trim()

                // 🔥 1. تسجيل الاستخدام في Room (هنا يتعلم AIRI تفضيلاتك)
                BehaviorEngine.recordUsage(cleanText)

                // 2. تنفيذ الإجراء المرتبط بالاقتراح (مثل التلخيص أو التحليل)
                onSuggestionClick(cleanText)
                
                // 3. (اختياري) تعطل الضغط بعد المرة الأولى لمنع التكرار
                holder.itemView.setOnClickListener(null)
            }
        } else {
            // رسالة عادية لا تتفاعل مع الضغط
            holder.itemView.setOnClickListener(null)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatModel) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(newText: String) {
        if (messages.isNotEmpty()) {
            messages.last().text = newText
            notifyItemChanged(messages.size - 1)
        }
    }

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // ملاحظة: simple_list_item_2 يحتوي على text1 و text2، نستخدم text1 للرسالة
        val textView: TextView = itemView.findViewById(android.R.id.text1)
    }
}
