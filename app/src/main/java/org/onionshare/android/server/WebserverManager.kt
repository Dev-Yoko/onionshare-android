package org.onionshare.android.server

import android.net.TrafficStats
import android.util.Base64
import android.util.Base64.NO_PADDING
import android.util.Base64.URL_SAFE
import com.mitchellbosecke.pebble.loader.ClasspathLoader
import io.ktor.application.Application
import io.ktor.application.ApplicationStarted
import io.ktor.application.ApplicationStopped
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.StatusPages
import io.ktor.http.ContentDisposition.Companion.Attachment
import io.ktor.http.ContentDisposition.Parameters.FileName
import io.ktor.http.HttpHeaders.ContentDisposition
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.pebble.Pebble
import io.ktor.pebble.PebbleContent
import io.ktor.response.header
import io.ktor.response.respond
import io.ktor.response.respondFile
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.onionshare.android.server.WebserverManager.State.SHOULD_STOP
import org.onionshare.android.server.WebserverManager.State.STARTED
import org.onionshare.android.server.WebserverManager.State.STOPPED
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.RejectedExecutionException
import javax.inject.Inject
import javax.inject.Singleton

private val LOG = LoggerFactory.getLogger(WebserverManager::class.java)
internal const val PORT: Int = 17638

@Singleton
class WebserverManager @Inject constructor() {

    enum class State { STARTED, SHOULD_STOP, STOPPED }

    private val secureRandom = SecureRandom()
    private var server: ApplicationEngine? = null
    private val state = MutableStateFlow(STOPPED)

    fun start(sendPage: SendPage): StateFlow<State> {
        val staticPath = getStaticPath()
        val staticPathMap = mapOf("static_url_path" to staticPath)
        TrafficStats.setThreadStatsTag(0x42)
        server = embeddedServer(Netty, PORT, watchPaths = emptyList()) {
            install(CallLogging)
            install(Pebble) {
                loader(ClasspathLoader().apply { prefix = "assets/templates" })
            }
            installStatusPages(staticPathMap)
            addListener()
            routing {
                defaultRoutes(staticPath)
                sendRoutes(sendPage, staticPathMap)
            }
        }.also { it.start() }
        return state
    }

    fun stop() {
        LOG.info("Stopping...")
        try {
            server?.stop(500, 1_000)
        } catch (e: RejectedExecutionException) {
            LOG.warn("Error while stopping webserver", e)
        } finally {
            server = null
        }
        LOG.info("Stopped")
    }

    private fun getStaticPath(): String {
        val staticSuffixBytes = ByteArray(16).apply { secureRandom.nextBytes(this) }
        val staticSuffix =
            Base64.encodeToString(staticSuffixBytes, NO_PADDING or URL_SAFE).trimEnd()
        return "/static_$staticSuffix"
    }

    private fun Application.addListener() {
        environment.monitor.subscribe(ApplicationStarted) {
            state.value = STARTED
        }
        environment.monitor.subscribe(ApplicationStopped) {
            state.value = STOPPED
        }
    }

    private fun Application.installStatusPages(staticPathMap: Map<String, String>) {
        install(StatusPages) {
            status(HttpStatusCode.NotFound) {
                call.respond(PebbleContent("404.html", staticPathMap))
            }
            status(HttpStatusCode.MethodNotAllowed) {
                call.respond(PebbleContent("405.html", staticPathMap))
            }
            status(HttpStatusCode.InternalServerError) {
                call.respond(PebbleContent("500.html", staticPathMap))
            }
        }
    }

    private fun Route.defaultRoutes(staticPath: String) {
        static("$staticPath/css") {
            resources("assets/static/css")
        }
        static("$staticPath/img") {
            resources("assets/static/img")
        }
        static("$staticPath/js") {
            resources("assets/static/js")
        }
    }

    private fun Route.sendRoutes(sendPage: SendPage, staticPathMap: Map<String, String>) {
        get("/") {
            val model = sendPage.model + staticPathMap
            call.respond(PebbleContent("send.html", model))
        }
        get("/download") {
            call.response.header(
                ContentDisposition,
                Attachment.withParameter(FileName, sendPage.fileName).toString()
            )
            call.respondFile(sendPage.zipFile)
            LOG.info("Download complete. Emitting SHOULD_STOP state...")
            state.value = SHOULD_STOP
        }
    }
}
