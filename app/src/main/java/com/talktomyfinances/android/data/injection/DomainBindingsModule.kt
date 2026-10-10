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

    companion object {
        @Provides fun goalResolver(classifier: GoalClassifier) = GoalResolver(classifier)
        @Provides fun readabilityGuard(rewriter: TextRewriter) = ReadabilityGuard(rewriter)
    }
}

