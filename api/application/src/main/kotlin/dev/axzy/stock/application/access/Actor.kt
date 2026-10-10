package dev.axzy.stock.application.access

import dev.axzy.stock.domain.access.AccessUser

/**
 * Who executes a use case. It arrives as an ARGUMENT, never from a global context: a use
 * case that reads the user from a thread variable cannot be tested or called from a job.
 *
 * It is rebuilt from the database on every request. The token only says who it is; what it can do is read
 * at that moment, so disabling an account or removing a permission takes effect immediately.
 */
data class Actor(
    val user: AccessUser,
    val displayName: String,
    val mustChangePassword: Boolean = false,
) {
    val id: Long get() = user.id
    val username: String get() = user.username
    val roleKeys: List<String> get() = user.roles.map { it.key.value }
}
