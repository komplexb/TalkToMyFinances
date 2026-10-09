package com.talktomyfinances.android.ui.scanMeal

import android.graphics.Bitmap
import com.talktomyfinances.android.data.schema.MealSchema

data class ScanMealViewState(
    val mealSchema: MealSchema = MealSchema(),
    val image: Bitmap? = null,
    val scanLoading: Boolean = false
)