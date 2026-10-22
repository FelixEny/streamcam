package com.streamcam.common.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamReducerTest {

    private fun start(): StreamStatus =
        StreamReducer.reduce(StreamStatus(), StreamEvent.StartRequested)

    @Test
    fun `idle to starting on start request`() {
        val status = start()
        assertEquals(StreamState.STARTING, status.state)
        assertEquals(null, status.errorReason)
    }

    @Test
    fun `starting to running on server ready`() {
        val status = StreamReducer.reduce(start(), StreamEvent.ServerReady)
        assertEquals(StreamState.RUNNING, status.state)
    }

    @Test
    fun `running to live on first client playing`() {
        val running = StreamReducer.reduce(start(), StreamEvent.ServerReady)
        val live = StreamReducer.reduce(running, StreamEvent.ClientPlaying(1))
        assertEquals(StreamState.LIVE, live.state)
    }

    @Test
    fun `starting to live if client plays before ready event`() {
        val live = StreamReducer.reduce(start(), StreamEvent.ClientPlaying(1))
        assertEquals(StreamState.LIVE, live.state)
    }

    @Test
    fun `live stays live with multiple clients`() {
        var status = StreamReducer.reduce(start(), StreamEvent.ClientPlaying(1))
        status = StreamReducer.reduce(status, StreamEvent.ClientPlaying(2))
        assertEquals(StreamState.LIVE, status.state)
        status = StreamReducer.reduce(status, StreamEvent.ClientGone(1))
        assertEquals(StreamState.LIVE, status.state)
    }

    @Test
    fun `live to running when last client leaves`() {
        var status = StreamReducer.reduce(start(), StreamEvent.ClientPlaying(1))
        status = StreamReducer.reduce(status, StreamEvent.ClientGone(0))
        assertEquals(StreamState.RUNNING, status.state)
    }

    @Test
    fun `running ignores client gone`() {
        val running = StreamReducer.reduce(start(), StreamEvent.ServerReady)
        val status = StreamReducer.reduce(running, StreamEvent.ClientGone(0))
        assertEquals(StreamState.RUNNING, status.state)
    }

    @Test
    fun `failed from each active state goes to error with reason`() {
        val starting = start()
        val running = StreamReducer.reduce(starting, StreamEvent.ServerReady)
        val live = StreamReducer.reduce(running, StreamEvent.ClientPlaying(1))
        for (status in listOf(starting, running, live)) {
            val failed = StreamReducer.reduce(status, StreamEvent.Failed("boom"))
            assertEquals(StreamState.ERROR, failed.state)
            assertEquals("boom", failed.errorReason)
        }
    }

    @Test
    fun `failed ignored when idle`() {
        val status = StreamReducer.reduce(StreamStatus(), StreamEvent.Failed("boom"))
        assertEquals(StreamState.IDLE, status.state)
        assertEquals(null, status.errorReason)
    }

    @Test
    fun `stop request puts active states into stopping`() {
        val starting = start()
        val running = StreamReducer.reduce(starting, StreamEvent.ServerReady)
        val live = StreamReducer.reduce(running, StreamEvent.ClientPlaying(1))
        for (status in listOf(starting, running, live)) {
            assertEquals(
                StreamState.STOPPING,
                StreamReducer.reduce(status, StreamEvent.StopRequested).state,
            )
        }
    }

    @Test
    fun `stopped always returns to idle and clears error`() {
        val error = StreamReducer.reduce(start(), StreamEvent.Failed("boom"))
        val idle = StreamReducer.reduce(error, StreamEvent.Stopped)
        assertEquals(StreamState.IDLE, idle.state)
        assertEquals(null, idle.errorReason)

        val stopping = StreamReducer.reduce(
            StreamReducer.reduce(start(), StreamEvent.ServerReady),
            StreamEvent.StopRequested,
        )
        assertEquals(StreamState.IDLE, StreamReducer.reduce(stopping, StreamEvent.Stopped).state)
    }

    @Test
    fun `error can restart`() {
        val error = StreamReducer.reduce(start(), StreamEvent.Failed("boom"))
        val restarted = StreamReducer.reduce(error, StreamEvent.StartRequested)
        assertEquals(StreamState.STARTING, restarted.state)
        assertEquals(null, restarted.errorReason)
    }

    @Test
    fun `start request ignored while already active`() {
        val running = StreamReducer.reduce(start(), StreamEvent.ServerReady)
        assertEquals(StreamState.RUNNING, StreamReducer.reduce(running, StreamEvent.StartRequested).state)
        assertEquals(StreamState.STARTING, StreamReducer.reduce(start(), StreamEvent.StartRequested).state)
    }

    @Test
    fun `client playing ignored while stopping`() {
        val stopping = StreamReducer.reduce(
            StreamReducer.reduce(start(), StreamEvent.ServerReady),
            StreamEvent.StopRequested,
        )
        assertEquals(
            StreamState.STOPPING,
            StreamReducer.reduce(stopping, StreamEvent.ClientPlaying(1)).state,
        )
    }

    @Test
    fun `server ready ignored when idle`() {
        val status = StreamReducer.reduce(StreamStatus(), StreamEvent.ServerReady)
        assertEquals(StreamState.IDLE, status.state)
    }
}
