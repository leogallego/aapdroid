package io.github.leogallego.ansiblejane.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: Int,
    val username: String,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val email: String = "",
    @SerialName("is_superuser") val isSuperuser: Boolean = false,
    @SerialName("is_system_auditor") val isSystemAuditor: Boolean = false
)
