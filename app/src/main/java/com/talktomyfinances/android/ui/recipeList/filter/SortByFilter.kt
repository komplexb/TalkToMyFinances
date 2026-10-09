package com.talktomyfinances.android.ui.recipeList.filter

import com.talktomyfinances.android.R

enum class SortByFilter(val title: Int) {
    DEFAULT(R.string.filter_sort_by_default),
    RATING(R.string.filter_sort_by_rating),
    ALPHABETICAL(R.string.filter_sort_by_alphabetical),
    POPULARITY(R.string.filter_sort_by_popularity)
}