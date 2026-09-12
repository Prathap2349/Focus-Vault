package com.focusvault.app.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Asynchronous domain existence and syntax validation pipeline.
 *
 * Pipeline stages:
 * 1. Raw Input Normalization: strips http(s)://, paths, ports, query strings, fragments, www.
 * 2. Syntax Validation: verifies label count, valid characters, TLD structure via DomainMatcher.
 * 3. Network Connectivity Check: verifies active internet connection before issuing DNS probes.
 * 4. DNS Resolution Check: validates that the domain actually resolves on public DNS via
 *    InetAddress.getAllByName() with a strict timeout (~2500ms).
 *
 * Nonexistent / NXDOMAIN hosts are rejected with "Website not found. Enter an existing domain."
 * Network errors or timeouts are reported as network verification errors, never as nonexistent.
 * Unverified domains are NEVER committed to Room DB, fast cache, or block lists.
 */
object DomainVerifier {

    sealed class VerificationResult {
        data class Valid(val normalizedDomain: String) : VerificationResult()
        object InvalidSyntax : VerificationResult()
        object DomainNotFound : VerificationResult()
        object NetworkUnavailable : VerificationResult()
    }

    /**
     * Optional custom resolver hook for testing or mocking DNS resolution in JVM unit tests.
     */
    var customResolver: (suspend (String) -> Boolean)? = null

    suspend fun verifyDomain(
        rawInput: String,
        isNetworkConnected: Boolean,
        timeoutMs: Long = 2500L
    ): VerificationResult = withContext(Dispatchers.IO) {
        val normalized = DomainMatcher.normalizeBlockedDomain(rawInput)
        if (!DomainMatcher.isValidDomain(normalized)) {
            return@withContext VerificationResult.InvalidSyntax
        }

        if (!isNetworkConnected) {
            return@withContext VerificationResult.NetworkUnavailable
        }

        val testHook = customResolver
        if (testHook != null) {
            return@withContext try {
                if (testHook(normalized)) {
                    VerificationResult.Valid(normalized)
                } else {
                    VerificationResult.DomainNotFound
                }
            } catch (e: UnknownHostException) {
                VerificationResult.DomainNotFound
            } catch (e: Exception) {
                VerificationResult.NetworkUnavailable
            }
        }

        try {
            val resolved = withTimeoutOrNull(timeoutMs) {
                try {
                    val addresses = InetAddress.getAllByName(normalized)
                    addresses != null && addresses.isNotEmpty()
                } catch (e: UnknownHostException) {
                    false
                } catch (e: SocketTimeoutException) {
                    null
                } catch (e: IOException) {
                    null
                } catch (e: Exception) {
                    null
                }
            }

            when (resolved) {
                true -> VerificationResult.Valid(normalized)
                false -> VerificationResult.DomainNotFound
                null -> VerificationResult.NetworkUnavailable
            }
        } catch (e: UnknownHostException) {
            VerificationResult.DomainNotFound
        } catch (e: Exception) {
            VerificationResult.NetworkUnavailable
        }
    }
}
