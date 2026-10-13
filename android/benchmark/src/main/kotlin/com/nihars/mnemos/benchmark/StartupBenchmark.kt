package com.nihars.mnemos.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec §8: cold start → sign-in screen interactive ≤ 350 ms; open note ≤ 80 ms; no janky frames. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStart() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Demo user")), 5_000)
    }

    @Test fun scrollTree() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            startActivityAndWait()
            openDemo()
        },
    ) { scrollTree() }

    @Test fun openNote() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            startActivityAndWait()
            openDemo()
        },
    ) {
        openLongNote()
        device.pressBack()
    }

    /** Typing latency: frame timing while typing into the long note. */
    @Test fun typing() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = 5,
        setupBlock = {
            startActivityAndWait()
            openDemo()
            openLongNote()
        },
    ) {
        repeat(40) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_A) }
        repeat(40) { device.pressKeyCode(android.view.KeyEvent.KEYCODE_DEL) }
    }
}
