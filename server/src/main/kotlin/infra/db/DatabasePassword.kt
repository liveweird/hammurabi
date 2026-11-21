package ch.nokillswit.infra.db

import io.ktor.server.application.*

/**
 * The Postgres passwords that ship in the repository, compared trimmed and ignoring case: `hammurabi` (the `postgres.password`
 * default in application.yaml and the password of the compose demo database, `docker-compose.yaml`) and the k8s
 * template placeholder `CHANGE-ME` (`k8s/secret.yaml`) with its spelling variants (the bootstrap check's burned set).
 */
private val SHIPPED_DATABASE_PASSWORDS = setOf("hammurabi", "changeme", "change-me")

/**
 * The fail-closed check for `postgres.password` (`POSTGRES_PASSWORD`), mirroring the JWT-secret, mail and data-encryption-key
 * checks (`.claude/docs/security.md`): a blank, shipped default or template-placeholder password is tolerated with a warning in
 * development (the local-dev default and the compose demo database use `hammurabi`) and **refuses to start** in production. It
 * runs at the head of [configureFlyway], the first module to open a connection, so no connection is ever made with a
 * password that is public; the message names the variable and never the value.
 */
internal fun Application.requirePrivateDatabasePassword() {
    val password = environment.config.property("postgres.password").getString()
    if (password.isNotBlank() && password.trim().lowercase() !in SHIPPED_DATABASE_PASSWORDS) return
    val message =
        "Postgres password (POSTGRES_PASSWORD) is unset or a publicly known value (the shipped default or the template " +
            "placeholder) - set a private password on the database and the app (openssl rand -hex 16)."
    if (developmentMode) log.warn("$message (permitted in development only)")
    else error(message)
}
