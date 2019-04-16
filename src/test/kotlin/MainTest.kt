import io.ktor.application.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MainTest {
    @Suppress("unused")
    val server = embeddedServer(factory = Netty, port = testPort, module = Application::module).apply { start(false) }
    private val client = HttpClient(Apache)
    private val path = "http://localhost:$testPort/"

    private suspend fun putLinks(data: String) = client.post<String>("$path/${Endpoint.visited_links}") {
        body = TextContent(data, ContentType.Application.Json)
    }

    @Test
    fun `add links test`() = runBlocking {
        val timeStamp = Instant.now().epochSecond.toDouble()

        val resPut = putLinks("{\n" +
                    "  \"links\": [\n" +
                    "    \"https://ya.ru\",\n" +
                    "    \"https://ya.ru?q=123\",\n" +
                    "    \"funbox.ru\",\n" +
                    "    \"https://stackoverflow.com/questions/11828270/how-to-exit-the-vim-editor\"\n" +
                    "  ]\n" +
                    "}")

        assert(Json.parse(Status.serializer(), resPut).status == "ok")

        val resGet = client.get<String>("$path/${Endpoint.visited_domains}?from=$timeStamp&to=${timeStamp + 60}")
        val domains = Json.parse(Domains.serializer(), resGet)
        assert(domains.domains.contains("funbox.ru"))
    }

    @Test
    fun `wrong domain test`() = runBlocking {
        val resPut = putLinks("{\n" +
                "  \"links\": [\n" +
                "    \"garrrrrbage\",\n" +
                "    \"https://ya.ru?q=123\",\n" +
                "    \"funbox.ru\",\n" +
                "    \"https://stackoverflow.com/questions/11828270/how-to-exit-the-vim-editor\"\n" +
                "  ]\n" +
                "}")

        Json.parse(Status.serializer(), resPut).let {
            assert(it.status.contains("failed"))
            assert(it.status.contains("garrrrrbage"))
        }
    }

    @Test
    fun `bad request test`() = runBlocking {
        val resGet = client.get<String>("$path/${Endpoint.visited_domains}?from=oioi&to=5656")
        val status = Json.parse(Status.serializer(), resGet)
        assert(status.status.contains("NumberFormatException"))
    }
}