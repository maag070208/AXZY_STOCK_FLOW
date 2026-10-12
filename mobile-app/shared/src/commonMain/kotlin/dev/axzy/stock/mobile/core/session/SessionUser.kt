package dev.axzy.stock.mobile.core.session

/**
 * Who is signed in, as the API reports it. The app does not decide by [permissions] yet: the API checks every
 * request, and a screen shows its refusal. When a screen hides an option by permission, that will be a courtesy,
 * not the guard.
 */
data class SessionUser(
    val id: Long,
    val username: String,
    val name: String,
    val permissions: Set<String>,
)
