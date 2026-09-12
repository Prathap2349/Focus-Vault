package com.focusvault.app.service

/**
 * Pure, testable domain normalization and matching engine for Focus Vault website blocking.
 *
 * Matching rules:
 * - Domain must match exactly, or as a sub-domain (query == blocked || query.endsWith(".$blocked")).
 * - Strict domain boundaries: 'notyoutube.com' and 'youtube.com.example.com' must NOT match 'youtube.com'.
 * - Safe URL normalization: strips http(s)://, paths, query strings, fragments, ports, www., and wildcards.
 * - Strict validation: Corrupted or single-label entries (e.g. "com", "") are rejected and never match all domains.
 */
object DomainMatcher {

    private val DOMAIN_REGEX = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

    /**
     * Validates whether a domain string is structurally valid (has >= 2 labels, valid chars, etc.)
     */
    fun isValidDomain(domain: String): Boolean {
        val raw = domain.trim().lowercase()
        if (raw.startsWith(".") || raw.endsWith(".") || raw.contains("..")) return false
        if (raw.length < 3 || !raw.contains('.')) return false
        val labels = raw.split('.')
        if (labels.size < 2) return false
        // TLD cannot be purely numeric and must be at least 2 chars
        val tld = labels.last()
        if (tld.length < 2 || tld.all { it.isDigit() }) return false
        return raw.matches(DOMAIN_REGEX)
    }

    /**
     * Normalizes a user-input domain or URL into a canonical domain string.
     * Examples:
     * - "https://www.youtube.com/watch?v=123" -> "youtube.com"
     * - "http://youtube.com:8080/feed" -> "youtube.com"
     * - "*.instagram.com" -> "instagram.com"
     * - "m.facebook.com" -> "m.facebook.com"
     */
    fun normalizeBlockedDomain(raw: String): String {
        var d = raw.trim().lowercase()
        if (d.startsWith("http://")) d = d.removePrefix("http://")
        if (d.startsWith("https://")) d = d.removePrefix("https://")
        if (d.contains("@")) d = d.substringAfterLast("@")
        if (d.contains("/")) d = d.substringBefore("/")
        if (d.contains("?")) d = d.substringBefore("?")
        if (d.contains("#")) d = d.substringBefore("#")
        if (d.contains(":")) d = d.substringBefore(":")
        if (d.startsWith("*.")) d = d.removePrefix("*.")
        if (d.startsWith("www.")) d = d.removePrefix("www.")
        return d.trim('.')
    }

    /**
     * Normalizes a query domain extracted from a DNS packet.
     * Lowercases, strips leading www., and trims trailing dots.
     */
    fun normalizeQueryDomain(query: String): String {
        var d = query.trim().lowercase().trim('.')
        if (d.startsWith("www.")) d = d.removePrefix("www.")
        return d
    }

    /**
     * Evaluates whether [queryDomain] matches any entry in [blockedDomains].
     *
     * Domain boundary guarantee:
     * - If blocked is "youtube.com":
     *   - "youtube.com" -> MATCH
     *   - "www.youtube.com" -> MATCH (normalized to youtube.com)
     *   - "m.youtube.com" -> MATCH (ends with .youtube.com)
     *   - "music.youtube.com" -> MATCH (ends with .youtube.com)
     *   - "notyoutube.com" -> NO MATCH
     *   - "youtube.com.example.com" -> NO MATCH
     *   - "google.com" -> NO MATCH
     */
    fun isDomainBlocked(queryDomain: String, blockedDomains: Set<String>): Boolean {
        if (blockedDomains.isEmpty()) return false
        val q = normalizeQueryDomain(queryDomain)
        if (q.isEmpty()) return false

        for (rawBlocked in blockedDomains) {
            val b = normalizeBlockedDomain(rawBlocked)
            // Safety check: must be a valid domain with at least 2 labels (e.g. "youtube.com")
            // Prevents broad TLD matching if "com" or invalid entry is passed
            if (!isValidDomain(b)) continue
            if (q == b || q.endsWith(".$b")) {
                return true
            }
        }
        return false
    }
}
