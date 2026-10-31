package com.phonecodex.app.domain.diagnostics

internal fun diagnosticErrorKind(error: Throwable): String =
    error.javaClass.simpleName.ifBlank { "Error" }

internal fun diagnosticTextMetadata(value: String): String =
    "textPresent=${value.isNotBlank()} textChars=${value.length}"
