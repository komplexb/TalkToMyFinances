package com.talktomyfinances.android.data.repository

import com.google.firebase.auth.FirebaseUser
import com.talktomyfinances.android.data.datasource.AuthRemoteDataSource
import com.talktomyfinances.android.data.model.User
import javax.inject.Inject

class AuthRepository @Inject constructor(
    private val authRemoteDataSource: AuthRemoteDataSource
) {
    val currentUser: FirebaseUser? = authRemoteDataSource.currentUser

    suspend fun createAnonymousAccount(): User {
        return authRemoteDataSource.createAnonymousAccount()
    }
}