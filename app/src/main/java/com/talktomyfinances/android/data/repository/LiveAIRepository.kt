package com.talktomyfinances.android.data.repository

import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.talktomyfinances.android.data.datasource.LiveAIRemoteDataSource
import com.talktomyfinances.android.data.model.Recipe
import javax.inject.Inject

class LiveAIRepository @Inject constructor(
    private val liveAiRemoteDataSource: LiveAIRemoteDataSource
) {
    @OptIn(PublicPreviewAPI::class)
    suspend fun setupLiveSession(recipe: Recipe): LiveSession? {
        return liveAiRemoteDataSource.setupLiveSession(recipe)
    }
}