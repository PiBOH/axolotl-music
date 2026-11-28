package com.music.innertube

import com.music.innertube.models.response.NextResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TestJson {
    @Test
    fun testParse() {
        // The response is a captured live payload and is not part of the
        // repository; without it the check has nothing to parse.
        val fixture = File("../ytmusic_next_response.json")
        assumeTrue("ytmusic_next_response.json is not present", fixture.isFile)
        val json = Json { ignoreUnknownKeys = true }
        val data = fixture.readText()
        val response = json.decodeFromString<NextResponse>(data)
        
        val mq = response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(0)?.tabRenderer?.content?.musicQueueRenderer
        println("MusicQueueRenderer is null: ${mq == null}")
        println("subHeaderChipCloud is null: ${mq?.subHeaderChipCloud == null}")
        println("Chips count: ${mq?.subHeaderChipCloud?.chipCloudRenderer?.chips?.size}")
        assertNotNull(mq?.subHeaderChipCloud)
    }
}
