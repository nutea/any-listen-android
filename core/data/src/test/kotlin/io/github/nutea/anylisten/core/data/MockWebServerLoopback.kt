package io.github.nutea.anylisten.core.data

import okhttp3.mockwebserver.MockWebServer

fun MockWebServer.loopbackUrl(path: String): String =
    url(path).newBuilder().host("127.0.0.1").build().toString()
