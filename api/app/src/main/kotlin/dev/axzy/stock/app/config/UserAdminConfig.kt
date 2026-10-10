package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.identity.JdbcUserAdmin
import dev.axzy.stock.adapters.web.identity.RoleController
import dev.axzy.stock.adapters.web.identity.UserController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.identity.CreateRole
import dev.axzy.stock.application.identity.CreateUser
import dev.axzy.stock.application.identity.GetRole
import dev.axzy.stock.application.identity.GetUser
import dev.axzy.stock.application.identity.ListPermissions
import dev.axzy.stock.application.identity.ListRoles
import dev.axzy.stock.application.identity.ListUsers
import dev.axzy.stock.application.identity.ResetUserPassword
import dev.axzy.stock.application.identity.RevokeUserSessions
import dev.axzy.stock.application.identity.RoleAdminStore
import dev.axzy.stock.application.identity.SetRolePermissions
import dev.axzy.stock.application.identity.UpdateRole
import dev.axzy.stock.application.identity.UpdateUser
import dev.axzy.stock.application.identity.UserAdminStore
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.identity.RefreshTokens
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.domain.identity.PasswordPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * User and role administration (the rest of M07).
 *
 * The hasher and the password policy are the same ones the sign-in uses: a
 * password is validated the same whether it is created at startup or through this route, and having two
 * policies would mean having only one that is real.
 */
@Configuration(proxyBeanMethods = false)
class UserAdminConfig {

    @Bean
    fun userAdminStore(jdbc: JdbcClient): JdbcUserAdmin = JdbcUserAdmin(jdbc)

    @Bean
    fun listUsers(store: JdbcUserAdmin, access: AccessDecider) = ListUsers(store as UserAdminStore, access)

    @Bean
    fun getUser(store: JdbcUserAdmin, access: AccessDecider) = GetUser(store as UserAdminStore, access)

    @Bean
    fun listRoles(store: JdbcUserAdmin, access: AccessDecider) = ListRoles(store as UserAdminStore, access)

    @Bean
    fun listPermissions(store: JdbcUserAdmin, access: AccessDecider) = ListPermissions(store as UserAdminStore, access)

    @Bean
    fun createUser(
        store: JdbcUserAdmin,
        hasher: PasswordHasher,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = CreateUser(store as UserAdminStore, hasher, PasswordPolicy, audit, auditEvents, access, tx)

    @Bean
    fun revokeUserSessions(
        accounts: UserAccounts,
        refreshTokens: RefreshTokens,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = RevokeUserSessions(accounts, refreshTokens, audit, auditEvents, access, tx)

    @Bean
    fun updateUser(
        store: JdbcUserAdmin,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = UpdateUser(store as UserAdminStore, audit, auditEvents, access, tx)

    @Bean
    fun resetUserPassword(
        store: JdbcUserAdmin,
        hasher: PasswordHasher,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = ResetUserPassword(store as UserAdminStore, hasher, PasswordPolicy, audit, auditEvents, access, tx, clock)

    @Bean
    fun userController(
        listUsers: ListUsers,
        getUser: GetUser,
        createUser: CreateUser,
        updateUser: UpdateUser,
        resetUserPassword: ResetUserPassword,
        revokeUserSessions: RevokeUserSessions,
    ) = UserController(listUsers, getUser, createUser, updateUser, resetUserPassword, revokeUserSessions)

    @Bean
    fun getRole(store: JdbcUserAdmin, access: AccessDecider) = GetRole(store as RoleAdminStore, access)

    @Bean
    fun createRole(
        store: JdbcUserAdmin,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = CreateRole(store as RoleAdminStore, audit, auditEvents, access, tx)

    @Bean
    fun updateRole(
        store: JdbcUserAdmin,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = UpdateRole(store as RoleAdminStore, audit, auditEvents, access, tx)

    @Bean
    fun setRolePermissions(
        store: JdbcUserAdmin,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = SetRolePermissions(store as RoleAdminStore, audit, auditEvents, access, tx)

    @Bean
    fun roleController(
        listRoles: ListRoles,
        listPermissions: ListPermissions,
        getRole: GetRole,
        createRole: CreateRole,
        updateRole: UpdateRole,
        setRolePermissions: SetRolePermissions,
    ) = RoleController(listRoles, listPermissions, getRole, createRole, updateRole, setRolePermissions)
}
