package io.github.leogallego.ansiblejane.assistant.engine

import io.github.leogallego.ansiblejane.model.User

/**
 * Coarse AAP RBAC role used by [ToolRouter] to hide write/destructive tools
 * from auditor accounts (see #120 Tier 1).
 */
enum class AapRole {
    ADMIN,
    AUDITOR,
    OPERATOR
}

fun User.toAapRole(): AapRole = when {
    isSuperuser -> AapRole.ADMIN
    isSystemAuditor -> AapRole.AUDITOR
    else -> AapRole.OPERATOR
}
