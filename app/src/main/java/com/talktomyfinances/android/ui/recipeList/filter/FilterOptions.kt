package com.talktomyfinances.android.ui.recipeList.filter

import com.talktomyfinances.android.ui.recipeList.filter.SortByFilter.DEFAULT

data class FilterOptions(
    val recipeTitle: String = "",
    val searchQuery: String = "",
    val filterByMine: Boolean = false,
    val rating: Int = 0,
    val selectedTags: List<String> = listOf(),
    val sortBy: SortByFilter = DEFAULT
)