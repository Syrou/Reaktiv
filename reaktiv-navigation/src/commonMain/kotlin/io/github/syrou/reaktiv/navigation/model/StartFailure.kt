package io.github.syrou.reaktiv.navigation.model

import kotlinx.serialization.Serializable

@Serializable
public data class StartFailure(val exceptionType: String, val exceptionMessage: String)
