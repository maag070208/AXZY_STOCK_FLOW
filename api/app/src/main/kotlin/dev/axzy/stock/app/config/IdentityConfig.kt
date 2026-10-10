package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.security.Argon2idPasswordHasher
import dev.axzy.stock.adapters.security.JwtAccessTokens
import dev.axzy.stock.adapters.security.OpaqueRefreshTokenCodec
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AccessDenialLog
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.identity.AccessCatalogStore
import dev.axzy.stock.application.identity.BootstrapIdentity
import dev.axzy.stock.application.identity.ChangePassword
import dev.axzy.stock.application.identity.Login
import dev.axzy.stock.application.identity.LoginAttempts
import dev.axzy.stock.application.identity.Logout
import dev.axzy.stock.application.identity.RefreshSession
import dev.axzy.stock.application.identity.RefreshTokenCodec
import dev.axzy.stock.application.identity.RefreshTokens
import dev.axzy.stock.application.identity.ResolveActor
import dev.axzy.stock.application.identity.SessionIssuer
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.domain.access.AccessEngine
import dev.axzy.stock.domain.access.StockPolicies
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.domain.identity.SessionPolicy
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Identidad y acceso: la criptografía, el motor de acceso y los casos de uso de sesión. */
@Configuration(proxyBeanMethods = false)
class IdentityConfig {

    private val log = LoggerFactory.getLogger(IdentityConfig::class.java)

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun auditEvents(clock: Clock) = AuditEvents(clock)

    @Bean
    fun sessionPolicy(): SessionPolicy = SessionPolicy.DEFAULT

    @Bean
    fun passwordHasher(): PasswordHasher = Argon2idPasswordHasher()

    @Bean
    fun refreshTokenCodec(): RefreshTokenCodec = OpaqueRefreshTokenCodec()

    @Bean
    fun accessTokens(properties: StockProperties, clock: Clock) =
        JwtAccessTokens(properties.jwt.secret, properties.jwt.issuer, properties.jwt.audience, clock)

    @Bean
    fun bootstrapIdentity(store: AccessCatalogStore, hasher: PasswordHasher, properties: StockProperties) =
        BootstrapIdentity(store, hasher, properties.admin.username, properties.admin.bootstrapPassword)

    /**
     * El motor de acceso se crea DESPUÉS de sincronizar el catálogo: necesita saber qué versión de
     * la matriz y de las políticas está vigente para que cada decisión auditada sea reproducible.
     * Si la sincronización falla, la API no arranca.
     */
    @Bean
    fun accessEngine(
        bootstrap: BootstrapIdentity,
        tx: TransactionRunner,
        properties: StockProperties,
        // No se usa: pedirla obliga a que el esquema se compruebe antes de escribir en la base.
        @Suppress("UNUSED_PARAMETER") schema: SchemaVersion,
    ): AccessEngine {
        val result = tx.inTransaction { bootstrap.execute().asRight() }.getOrNull()!!
        log.info(
            "Access catalog synchronized: {} permissions, {} roles and {} new cells; matrix v{}, policies v{}{}",
            result.permissionsSynced, result.rolesCreated, result.grantsCreated,
            result.versions.matrix, result.versions.policies,
            if (result.adminCreated) "; the first administrator '${properties.admin.username}' was created" else "",
        )
        return AccessEngine(
            policies = StockPolicies.all,
            matrixVersion = result.versions.matrix,
            policiesVersion = result.versions.policies,
        )
    }

    @Bean
    fun accessDecider(engine: AccessEngine, denials: AccessDenialLog, auditEvents: AuditEvents, clock: Clock, properties: StockProperties) =
        AccessDecider(engine, denials, auditEvents, clock, properties.businessZone)

    @Bean
    fun sessionIssuer(refreshTokens: RefreshTokens, codec: RefreshTokenCodec, accessTokens: JwtAccessTokens, policy: SessionPolicy) =
        SessionIssuer(refreshTokens, codec, accessTokens, policy)

    @Bean
    fun login(
        users: UserAccounts,
        attempts: LoginAttempts,
        hasher: PasswordHasher,
        sessions: SessionIssuer,
        codec: RefreshTokenCodec,
        tx: TransactionRunner,
        clock: Clock,
    ) = Login(users, attempts, hasher, sessions, codec, tx, clock)

    @Bean
    fun refreshSession(
        users: UserAccounts,
        refreshTokens: RefreshTokens,
        codec: RefreshTokenCodec,
        sessions: SessionIssuer,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        tx: TransactionRunner,
        clock: Clock,
        policy: SessionPolicy,
    ) = RefreshSession(users, refreshTokens, codec, sessions, audit, auditEvents, tx, clock, policy)

    @Bean
    fun logout(refreshTokens: RefreshTokens, codec: RefreshTokenCodec) = Logout(refreshTokens, codec)

    @Bean
    fun changePassword(
        users: UserAccounts,
        refreshTokens: RefreshTokens,
        hasher: PasswordHasher,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        tx: TransactionRunner,
        clock: Clock,
    ) = ChangePassword(users, refreshTokens, hasher, audit, auditEvents, tx, clock)

    @Bean
    fun resolveActor(users: UserAccounts) = ResolveActor(users)
}
