package io.github.leogallego.ansiblejane.assistant.engine

import io.github.leogallego.ansiblejane.model.AapInstance
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

/**
 * Local tool names that mutate AAP state but do not end with [io.github.leogallego.ansiblejane.assistant.tools.Tool.WRITE_SUFFIXES].
 * Must stay in sync with `isDestructive = true` on the corresponding `*LocalTool` classes.
 */
val DESTRUCTIVE_LOCAL_TOOL_NAMES = setOf(
    "launch_job",
    "launch_workflow",
    "approve_workflow",
    "deny_workflow",
    "toggle_schedule",
)

fun User.toAapRole(): AapRole = when {
    isSuperuser -> AapRole.ADMIN
    isSystemAuditor -> AapRole.AUDITOR
    else -> AapRole.OPERATOR
}

/**
 * Maps persisted instance flags to a routing role.
 * Fail-closed: until `/api/v2/me/` role flags are fetched, treat as [AapRole.AUDITOR].
 */
fun AapInstance.toAapRole(): AapRole = when {
    !userRoleFetched -> AapRole.AUDITOR
    isSuperuser -> AapRole.ADMIN
    isSystemAuditor -> AapRole.AUDITOR
    else -> AapRole.OPERATOR
}
