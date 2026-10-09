package com.talktomyfinances.android.ui.groceryList

import com.talktomyfinances.android.data.schema.StoreSchema

sealed interface StoreFinderUiState {
    object Idle : StoreFinderUiState
    object Loading : StoreFinderUiState
    data class Success(val stores: List<StoreSchema>) : StoreFinderUiState
    data class Error(val message: String) : StoreFinderUiState
}