package com.xera.ptzcam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Patrol / scan mode: visits the given presets in order, waits [dwellMs] at each, and repeats.
 * Camera-independent – the caller supplies how to reach a preset ([goTo] returns true once
 * the camera is there, false if the preset could not be reached).
 * Stops when [shouldAbort] returns true (manual input) or when [stop] is called.
 * [onFinished] is only called when a patrol ends by itself (not after [stop], whose caller
 * already knows), and never for a run that has been replaced by a newer one.
 */
class PatrolRunner(
    private val scope: CoroutineScope,
    private val goTo: suspend (String) -> Boolean,
    private val shouldAbort: () -> Boolean,
    private val onStep: (String) -> Unit,
    private val onFinished: (reason: String) -> Unit,
) {
    private var job: Job? = null

    val isRunning get() = job?.isActive == true

    fun start(presets: List<String>, dwellMs: Long, loop: Boolean) {
        stop()
        if (presets.isEmpty()) {
            onFinished("No presets selected for patrol")
            return
        }
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            var reason: String? = null
            try {
                outer@ do {
                    for (name in presets) {
                        if (!isActive) break@outer
                        onStep(name)
                        if (!goTo(name)) { reason = "Patrol stopped: could not reach $name"; break@outer }
                        var waited = 0L
                        while (waited < dwellMs) {
                            if (shouldAbort()) { reason = "Patrol stopped (manual control)"; break@outer }
                            delay(100)
                            waited += 100
                        }
                    }
                } while (loop && isActive)
            } finally {
                if (job === me) {
                    job = null
                    onFinished(reason ?: "Patrol finished")
                }
            }
        }
        job = run
        run.start()
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
