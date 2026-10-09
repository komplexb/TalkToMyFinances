package com.talktomyfinances.android.ui.recipeList

import com.talktomyfinances.android.MainViewModel
import com.talktomyfinances.android.data.repository.AuthRepository
import com.talktomyfinances.android.data.repository.DatabaseRepository
import com.talktomyfinances.android.ui.recipeList.filter.FilterOptions
import com.talktomyfinances.android.ui.recipeList.filter.SortByFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RecipeListViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val databaseRepository: DatabaseRepository
) : MainViewModel() {
    private val _filterOptions = MutableStateFlow(FilterOptions())
    val filterOptions: StateFlow<FilterOptions>
        get() = _filterOptions.asStateFlow()

    private val _tags = MutableStateFlow(listOf<String>())
    val tags: StateFlow<List<String>>
        get() = _tags.asStateFlow()

    private val _recipes = MutableStateFlow<List<RecipeListItem>>(listOf())
    val recipes: StateFlow<List<RecipeListItem>>
        get() = _recipes.asStateFlow()

    init {
        launchCatching {
            _recipes.value = databaseRepository.getAllRecipes()
            _tags.value = databaseRepository.getPopularTags()
        }
    }

    fun updateRecipeTitle(recipeName: String) {
        _filterOptions.value = _filterOptions.value.copy(recipeTitle = recipeName)
    }

    fun updateSearchQuery(query: String) {
        _filterOptions.value = _filterOptions.value.copy(searchQuery = query)
    }

    fun updateFilterByMine() {
        val currentValue = _filterOptions.value.filterByMine
        _filterOptions.value = _filterOptions.value.copy(filterByMine = !currentValue)
    }

    fun updateRating(rating: Int) {
        _filterOptions.value = _filterOptions.value.copy(rating = rating)
    }

    fun removeTag(tag: String) {
        _filterOptions.value = _filterOptions.value.copy(
            selectedTags = _filterOptions.value.selectedTags.filter { it != tag }
        )
    }

    fun addTag(tag: String) {
        _filterOptions.value = _filterOptions.value.copy(
            selectedTags = _filterOptions.value.selectedTags + tag
        )
    }

    fun updateSortBy(sortByFilter: SortByFilter) {
        _filterOptions.value = _filterOptions.value.copy(sortBy = sortByFilter)
    }

    fun resetFilters() {
        _filterOptions.value = FilterOptions()

        launchCatching {
            _recipes.value = databaseRepository.getAllRecipes()
        }
    }

    fun applyFilters() {
        launchCatching {
            val filter = _filterOptions.value
            val userId = authRepository.currentUser?.uid.orEmpty()

            _recipes.value = databaseRepository.getFilteredRecipes(filter, userId)
        }
    }
}