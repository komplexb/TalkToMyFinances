package com.talktomyfinances.android.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.talktomyfinances.android.domain.AuditReceipt
import com.talktomyfinances.android.domain.ChatMessage
import com.talktomyfinances.android.domain.ChatRepository
import com.talktomyfinances.android.domain.MessageSender
import com.talktomyfinances.android.domain.VisualCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import javax.inject.Inject

class FirestoreChatRepository @Inject constructor(
    private val firestore: FirebaseFirestore
) : ChatRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun getMessages(userId: String): Flow<List<ChatMessage>> {
        return firestore.collection("users")
            .document(userId)
            .collection("chat_history")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    try {
                        val id = doc.getString("id") ?: doc.id
                        val senderStr = doc.getString("sender") ?: MessageSender.USER.name
                        val sender = try {
                            MessageSender.valueOf(senderStr)
                        } catch (_: Exception) {
                            MessageSender.USER
                        }
                        val text = doc.getString("text") ?: ""
                        val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                        val receiptJson = doc.getString("auditReceiptJson")
                        val receipt = if (!receiptJson.isNullOrBlank()) {
                            try {
                                json.decodeFromString<AuditReceipt>(receiptJson)
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                        val visualCardJson = doc.getString("visualCardJson") ?: doc.getString("visualCardPayloadJson")
                        val visualCard = if (!visualCardJson.isNullOrBlank()) {
                            try {
                                json.decodeFromString<VisualCard>(visualCardJson)
                            } catch (_: Exception) {
                                null
                            }
                        } else null

                        ChatMessage(
                            id = id,
                            sender = sender,
                            text = text,
                            auditReceipt = receipt,
                            visualCard = visualCard,
                            timestamp = timestamp
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            }
    }

    override suspend fun saveMessage(userId: String, message: ChatMessage) {
        val data = mutableMapOf<String, Any>(
            "id" to message.id,
            "sender" to message.sender.name,
            "text" to message.text,
            "timestamp" to message.timestamp
        )
        if (message.auditReceipt != null) {
            data["auditReceiptJson"] = json.encodeToString(AuditReceipt.serializer(), message.auditReceipt)
        }
        if (message.visualCard != null) {
            val cardJson = json.encodeToString(VisualCard.serializer(), message.visualCard)
            data["visualCardJson"] = cardJson
            data["visualCardPayloadJson"] = cardJson
        }

        firestore.collection("users")
            .document(userId)
            .collection("chat_history")
            .document(message.id)
            .set(data)
            .await()
    }
}
