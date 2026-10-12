package com.talktomyfinances.android.domain

import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessages(userId: String): Flow<List<ChatMessage>>
    suspend fun saveMessage(userId: String, message: ChatMessage)
}
