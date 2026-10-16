package com.talktomyfinances.android.data.injection

import com.talktomyfinances.android.data.datasource.FirebaseOnboardingAI
import com.talktomyfinances.android.data.repository.FirestoreProfileStore
import com.talktomyfinances.android.data.repository.RoutingInsightsRepository
import com.talktomyfinances.android.domain.GoalClassifier
import com.talktomyfinances.android.domain.GoalResolver
import com.talktomyfinances.android.domain.InsightsRepository
import com.talktomyfinances.android.domain.ProfileStore
import com.talktomyfinances.android.domain.ReadabilityGuard
import com.talktomyfinances.android.domain.TextRewriter
import com.talktomyfinances.android.data.repository.CloudRunAuditorRepository
import com.talktomyfinances.android.domain.AuditorRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DomainBindingsModule {
    @Binds abstract fun insights(impl: RoutingInsightsRepository): InsightsRepository
    @Binds abstract fun profileStore(impl: FirestoreProfileStore): ProfileStore
    @Binds abstract fun classifier(impl: FirebaseOnboardingAI): GoalClassifier
    @Binds abstract fun rewriter(impl: FirebaseOnboardingAI): TextRewriter
    @Binds abstract fun auditor(impl: CloudRunAuditorRepository): AuditorRepository
    @Binds abstract fun chat(impl: com.talktomyfinances.android.data.repository.FirestoreChatRepository): com.talktomyfinances.android.domain.ChatRepository
    @Binds abstract fun liveVoice(impl: com.talktomyfinances.android.data.repository.LiveVoiceRepositoryImpl): com.talktomyfinances.android.domain.LiveVoiceManager
    @Binds abstract fun auth(impl: com.talktomyfinances.android.data.repository.FirebaseAuthRepository): com.talktomyfinances.android.data.repository.AuthRepository
    @Binds abstract fun aiChat(impl: com.talktomyfinances.android.data.datasource.AIRemoteDataSource): com.talktomyfinances.android.data.datasource.AIChatDataSource

    companion object {
        @Provides fun goalResolver(classifier: GoalClassifier) = GoalResolver(classifier)
        @Provides fun readabilityGuard(rewriter: TextRewriter) = ReadabilityGuard(rewriter)
    }
}

