package com.talktomyfinances.android.data.repository

import com.google.firebase.auth.FirebaseUser
import com.talktomyfinances.android.data.datasource.AuthRemoteDataSource
import com.talktomyfinances.android.data.model.User
import javax.inject.Inject

interface AuthRepository {
    val currentUser: FirebaseUser?
    val currentUserId: String? get() = currentUser?.uid
    suspend fun createAnonymousAccount(): User
}

class FirebaseAuthRepository @Inject constructor(
    private val authRemoteDataSource: AuthRemoteDataSource
) : AuthRepository {
    override val currentUser: FirebaseUser? get() = authRemoteDataSource.currentUser
    override val currentUserId: String? get() = currentUser?.uid

    override suspend fun createAnonymousAccount(): User {
        return authRemoteDataSource.createAnonymousAccount()
    }
}