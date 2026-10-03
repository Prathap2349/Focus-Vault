package com.focusvault.app.manager

import org.junit.Test
import org.junit.Assert.*

class SecurityManagerMigrationTest {
    @Test
    fun testV4HashFormatParsing() {
        val pin = "1234"
        val salt = SecurityManager.generateSalt()
        val iters = 100_000
        val hash = SecurityManager.hashWithSalt(pin, salt, iters)
        
        val v4String = "v4:$iters:$salt:$hash"
        
        val parts = v4String.split(":")
        assertEquals(4, parts.size)
        assertEquals("v4", parts[0])
        assertEquals(iters.toString(), parts[1])
        assertEquals(salt, parts[2])
        assertEquals(hash, parts[3])
        
        // Verify same hash generates
        val testHash = SecurityManager.hashWithSalt(pin, parts[2], parts[1].toInt())
        assertEquals(parts[3], testHash)
    }

    @Test
    fun testV3HashFormatParsing() {
        val pin = "1234"
        val salt = SecurityManager.generateSalt()
        val iters = 10_000
        val hash = SecurityManager.hashWithSalt(pin, salt, iters)
        
        // Verify same hash generates
        val testHash = SecurityManager.hashWithSalt(pin, salt, iters)
        assertEquals(hash, testHash)
    }
}
