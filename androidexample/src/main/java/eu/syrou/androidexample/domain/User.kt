package eu.syrou.androidexample.domain

import kotlinx.serialization.Serializable

@Serializable
data class User(val hash: String, val name: String, val email: String, val isUpgraded: Boolean)