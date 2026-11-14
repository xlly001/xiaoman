package dev.xiaoman.llm

import dev.xiaoman.api.LlmClient
import dev.xiaoman.api.ModelConfig
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Builds an [LlmClient] for a configured model. */
fun interface LlmClientFactory {
    fun create(config: ModelConfig): LlmClient
}

object LlmClients {
    /**
     * One connection pool for all clients; per-model timeouts are applied on top of it.
     * Phone networks silently drop pooled connections: HTTP/2 pings and a short idle time make a
     * dead connection fail fast (and get retried) instead of hanging until the read timeout.
     */
    val sharedHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()
    }

    /** Creates [OpenAiCompatibleClient]s that share [http]. */
    fun factory(http: OkHttpClient = sharedHttp, maxRetries: Int = 2): LlmClientFactory =
        LlmClientFactory { OpenAiCompatibleClient(it, http, maxRetries) }

    val default: LlmClientFactory by lazy { factory() }
}
