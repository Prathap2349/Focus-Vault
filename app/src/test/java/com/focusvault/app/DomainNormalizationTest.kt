package com.focusvault.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainNormalizationTest {

    private fun normalize(raw: String): String {
        return raw.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .removePrefix("www.")
            .removePrefix("*.")
            .trim('.')
    }

    private fun matchesBlockedDomain(queryDomain: String, blockedDomain: String): Boolean {
        val q = normalize(queryDomain)
        val b = normalize(blockedDomain)
        return q == b || q.endsWith(".$b")
    }

    @Test
    fun testProtocolAndWwwStripping() {
        assertEquals("youtube.com", normalize("https://www.youtube.com/watch?v=12345"))
        assertEquals("youtube.com", normalize("http://youtube.com/feed/subscriptions"))
        assertEquals("facebook.com", normalize("https://www.facebook.com:443/login"))
        assertEquals("m.facebook.com", normalize("https://m.facebook.com:443/login"))
    }

    @Test
    fun testWildcardAndPortStripping() {
        assertEquals("instagram.com", normalize("*.instagram.com"))
        assertEquals("reddit.com", normalize("reddit.com:8080"))
        assertEquals("twitter.com", normalize("https://*.twitter.com:443/home"))
    }

    @Test
    fun testSubdomainMatching() {
        val blocked = "youtube.com"
        assertTrue(matchesBlockedDomain("youtube.com", blocked))
        assertTrue(matchesBlockedDomain("www.youtube.com", blocked))
        assertTrue(matchesBlockedDomain("m.youtube.com", blocked))
        assertTrue(matchesBlockedDomain("music.youtube.com", blocked))
        assertTrue(matchesBlockedDomain("i.v.youtube.com", blocked))
    }

    @Test
    fun testDisjointDomainNoFalsePositive() {
        val blocked = "tube.com"
        // youtube.com should NOT match tube.com because it's not a subdomain
        val q = normalize("youtube.com")
        val b = normalize(blocked)
        val matches = q == b || q.endsWith(".$b")
        org.junit.Assert.assertFalse(matches)
    }
}
