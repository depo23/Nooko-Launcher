package dev.nooko.launcher

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where background work runs. The network (TMDB, Watch Next, Wallhaven, Bing) has a few threads of its own, so a slow service never holds
 * up the single thread the rest of the app shares (MainActivity.io), and each thread has a name that shows in a crash report.
 */
internal object Work {
    private val count = AtomicInteger()

    val net: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "atv-net-${count.incrementAndGet()}").apply { isDaemon = true }
    }
}
