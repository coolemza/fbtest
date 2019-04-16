import com.google.gson.GsonBuilder
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.gson.GsonConverter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.error
import kotlinx.serialization.Serializable
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.time.Instant

const val key = "links"
const val port = 8080
const val testPort = 8081

enum class Endpoint {
    visited_links,
    visited_domains
}

val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()!!
val pool = JedisPool(JedisPoolConfig(), "localhost")

@Serializable
open class Status(@Suppress("unused") val status: String = "ok")

@Serializable
data class Domains(val domains: List<String>): Status()

@Serializable
data class Links(val links: List<String>)

fun Application.module() {
    install(CallLogging)
    install(DefaultHeaders)
    install(ConditionalHeaders)
    install(Compression)
    install(PartialContent)
    install(AutoHeadResponse)
    install(StatusPages) {
        exception<Throwable> { cause ->
            environment.log.error(cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    install(ContentNegotiation) {
        register(ContentType.Application.Json, GsonConverter(gson))
    }

    routing {
        post("/${Endpoint.visited_links.name}") {
            try {
                val domains = call.receive<Links>().links.associateBy({ it }) {
                    Regex("^(?:https?://)?(?:[^@\\n]+@)?(?:www\\.)?([^:/\\n?]+)").find(it)?.groupValues?.get(1)
                }

                val validDomains = domains.entries.associateBy({ it.key }) {
                    it.value?.let { domain ->
                        Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9][a-z0-9-]{0,61}[a-z0-9]\$")
                            .find(domain)?.groupValues?.get(0)
                    }
                }

                val timeStamp = Instant.now().epochSecond.toDouble()

                validDomains.values.filterNotNull().associateBy({ it }) { timeStamp }.let { values ->
                    pool.resource.use { it.zadd(key, values) }
                }

                call.respond(
                    validDomains.filter { it.value == null }.takeIf { it.isNotEmpty() }?.let {
                        Status("domain extract failed for : ${it.keys.joinToString { s -> s }}")
                    } ?: Status()
                )
            } catch (e: Exception) {
                call.respond(Status(e.toString()))
            }
        }

        get ("/${Endpoint.visited_domains.name}") {
            try {
                val (from, to) = call.request.queryParameters.let {
                    Pair(it["from"]!!.toDouble(), it["to"]!!.toDouble())
                }
                val res = pool.resource.use { it.zrangeByScore(key, from, to) }

                call.respond(Domains(res.toList()))
            } catch (e: Exception) {
                call.respond(Status(e.toString()))
            }
        }
    }
}

fun main() {
    embeddedServer(factory = Netty, port = port, module = Application::module).start(true)
}