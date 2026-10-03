package io.github.romanvht.byedpi.utility

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Checks sites through a local SOCKS proxy, or directly when `proxyIp` is null. */
class SiteCheckUtils(
    private val proxyIp: String?,
    private val proxyPort: Int
) {

    suspend fun checkSitesAsync(
        sites: List<String>,
        requestsCount: Int,
        requestTimeout: Long,
        concurrentRequests: Int = 20,
        fullLog: Boolean,
        onSiteChecked: (suspend (String, Int, Int) -> Unit)? = null
    ): List<Pair<String, Int>> {
        val semaphore = Semaphore(concurrentRequests)
        return withContext(Dispatchers.IO) {
            sites.map { site ->
                async {
                    semaphore.withPermit {
                        val successCount = checkSiteAccess(site, requestsCount, requestTimeout)
                        coroutineContext.ensureActive()
                        if (fullLog) {
                            onSiteChecked?.invoke(site, successCount, requestsCount)
                        }
                        site to successCount
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun checkSiteAccess(
        site: String,
        requestsCount: Int,
        timeout: Long
    ): Int = withContext(Dispatchers.IO) {
        var responseCount = 0

        val formattedUrl = if (site.startsWith("http://") || site.startsWith("https://")) site
        else "https://$site"

        val url = try {
            URL(formattedUrl)
        } catch (_: Exception) {
            Log.e("SiteChecker", "Invalid URL: $formattedUrl")
            return@withContext 0
        }

        val proxy = if (proxyIp == null) Proxy.NO_PROXY else Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyIp, proxyPort))

        repeat(requestsCount) { attempt ->
            coroutineContext.ensureActive()
            Log.i("SiteChecker", "Attempt ${attempt + 1}/$requestsCount for $site")

            val active = AtomicReference<HttpURLConnection?>()
            // Blocking socket calls (DNS above all, which ignores timeouts and disconnect()) can hang for
            // minutes when the network drops. Run them outside this coroutine so cancelling or timing out
            // returns at once instead of waiting for the stuck thread; that thread just finishes on its own.
            val request = detachedScope.async { request(site, url, proxy, timeout, active) }
            val ok = try {
                withTimeoutOrNull(timeout * 1000 * 3) { request.await() } ?: run {
                    Log.w("SiteChecker", "Gave up on $site after ${timeout * 3}s")
                    false
                }
            } finally {
                request.cancel()
                active.get()?.disconnect()
            }
            if (ok) responseCount++
        }
        responseCount
    }

    private fun request(
        site: String,
        url: URL,
        proxy: Proxy,
        timeout: Long,
        active: AtomicReference<HttpURLConnection?>,
    ): Boolean {
        try {
            val connection = url.openConnection(proxy) as HttpURLConnection
            active.set(connection)
            connection.connectTimeout = (timeout * 1000).toInt()
            connection.readTimeout = (timeout * 1000).toInt()
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Connection", "close")

            val responseCode = connection.responseCode
            val declaredLength = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                connection.contentLengthLong
            } else {
                connection.contentLength.toLong()
            }

            var actualLength = 0L
            try {
                val inputStream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                if (inputStream != null) {
                    val buffer = ByteArray(8192)
                    val limit = if (declaredLength > 0) declaredLength else 1024L * 1024
                    while (actualLength < limit) {
                        val remaining = limit - actualLength
                        val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                        val bytesRead = inputStream.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        actualLength += bytesRead
                    }
                }
            } catch (_: IOException) {
                // Stream reading failed
            }

            return if (declaredLength <= 0L || actualLength >= declaredLength) {
                Log.i("SiteChecker", "Response for $site: $responseCode, Declared: $declaredLength, Actual: $actualLength")
                true
            } else {
                Log.w("SiteChecker", "Block detected for $site, Declared: $declaredLength, Actual: $actualLength")
                false
            }
        } catch (e: Exception) {
            Log.e("SiteChecker", "Error accessing $site: ${e.message}")
            return false
        } finally {
            active.get()?.disconnect()
        }
    }

    private companion object {
        val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
