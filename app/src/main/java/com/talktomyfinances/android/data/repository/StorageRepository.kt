package com.talktomyfinances.android.data.repository

import android.graphics.Bitmap
import com.talktomyfinances.android.data.datasource.StorageRemoteDataSource
import javax.inject.Inject

class StorageRepository @Inject constructor(
    private val storageRemoteDataSource: StorageRemoteDataSource
) {
    suspend fun addImage(image: Bitmap): String {
        return storageRemoteDataSource.addImage(image)
    }
}