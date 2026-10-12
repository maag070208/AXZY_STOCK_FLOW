package dev.axzy.stock.mobile.core.network

/**
 * What a screen knows about data it asks the API for. The three fields are independent on purpose: a reload keeps
 * the last [value] on screen while [isLoading] is true, and a failed reload keeps it next to its [failure].
 */
data class Loaded<T>(
    val value: T? = null,
    val isLoading: Boolean = false,
    val failure: ApiFailure? = null,
)
