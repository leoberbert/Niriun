package com.niriun.app.obs

import java.security.MessageDigest
import java.util.Base64

enum class Tally { OFF, PREVIEW, PROGRAM }

data class SceneItem(val name: String, val enabled: Boolean, val isScene: Boolean, val isGroup: Boolean)

/** obs-websocket v5 authentication: `base64(sha256(base64(sha256(password + salt)) + challenge))`. */
object ObsAuth {
    fun response(password: String, salt: String, challenge: String): String {
        val secret = Base64.getEncoder().encodeToString(sha256(password + salt))
        return Base64.getEncoder().encodeToString(sha256(secret + challenge))
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
}

/** Decides whether a source is on air (Program) or about to be (Preview) from the OBS scene graph. */
object TallyLogic {
    private const val MAX_DEPTH = 8

    /**
     * @param items returns the items of a scene (or group when its second argument is true), or null if unknown.
     */
    fun compute(
        program: String?,
        preview: String?,
        studioMode: Boolean,
        source: String,
        items: (name: String, group: Boolean) -> List<SceneItem>?,
    ): Tally = when {
        source.isBlank() -> Tally.OFF
        program != null && contains(program, false, source, items, mutableSetOf(), 0) -> Tally.PROGRAM
        studioMode && preview != null && contains(preview, false, source, items, mutableSetOf(), 0) -> Tally.PREVIEW
        else -> Tally.OFF
    }

    /** A hidden item hides everything below it; nested scenes and groups are searched recursively. */
    private fun contains(
        scene: String,
        group: Boolean,
        source: String,
        items: (String, Boolean) -> List<SceneItem>?,
        visited: MutableSet<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_DEPTH || !visited.add((if (group) "g:" else "s:") + scene)) return false
        for (item in items(scene, group).orEmpty()) {
            if (!item.enabled) continue
            if (item.name == source && !item.isScene && !item.isGroup) return true
            if ((item.isScene || item.isGroup) && contains(item.name, item.isGroup, source, items, visited, depth + 1)) return true
        }
        return false
    }
}
