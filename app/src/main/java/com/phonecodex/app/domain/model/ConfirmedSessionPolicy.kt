package com.phonecodex.app.domain.model

data class ConfirmedSessionPolicy(
    val appRules: List<AppRule>,
    val contentRules: List<ContentRule>
)
