package com.stone.sleepguard

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.roleStore by preferencesDataStore("identity")

enum class Role(val key: String, val display: String) {
    STONE("STONE", "Stone"),
    FANNY("FANNY", "Fanny");

    val partner: Role get() = if (this == STONE) FANNY else STONE
}

data class RoleState(val loaded: Boolean = false, val role: Role? = null)

fun roleName(role: Role?) = role?.display ?: "对方"
fun partnerName(role: Role?) = role?.partner?.display ?: "对方"

class RolePreferences(context: Context) {
    private val store = context.roleStore
    private val key = stringPreferencesKey("role")
    val flow = store.data.map { prefs ->
        RoleState(loaded = true, role = Role.values().firstOrNull { it.key == prefs[key] })
    }

    /** 只在尚未选择时写入；选择后不可更改。 */
    suspend fun choose(role: Role) {
        store.edit { prefs -> if (prefs[key] == null) prefs[key] = role.key }
    }
}
