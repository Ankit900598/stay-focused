package com.phonecodex.app.ui.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenLifecycleObserverTest {

    @Test
    fun onStopCancelsBackgroundSensitiveWork() {
        var stopCalls = 0
        val observer = HomeScreenLifecycleObserver(onResume = {}, onStop = { stopCalls++ })

        observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_STOP)

        assertEquals(1, stopCalls)
    }

    @Test
    fun resumeDoesNotTriggerBackgroundCancellation() {
        var resumeCalls = 0
        var stopCalls = 0
        val observer = HomeScreenLifecycleObserver(
            onResume = { resumeCalls++ },
            onStop = { stopCalls++ }
        )

        observer.onStateChanged(TestLifecycleOwner(), Lifecycle.Event.ON_RESUME)

        assertEquals(1, resumeCalls)
        assertEquals(0, stopCalls)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle by lazy { LifecycleRegistry(this) }
    }
}
