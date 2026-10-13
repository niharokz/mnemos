package com.nihars.mnemos.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE = "com.nihars.mnemos"

/** Signs in as the demo user (no PIN, no storage permission needed) and waits for the tree. */
fun MacrobenchmarkScope.openDemo() {
    device.wait(Until.hasObject(By.text("Demo user")), 5_000)
    device.findObject(By.text("Demo user"))?.click()
    device.wait(Until.hasObject(By.text("Notes")), 5_000)
}

/** Opens the long markdown sample from the demo vault. */
fun MacrobenchmarkScope.openLongNote() {
    device.findObject(By.text("Markdown tour"))?.click()
    device.wait(Until.hasObject(By.desc("Edit")), 5_000)
}

fun MacrobenchmarkScope.scrollTree() {
    val list = device.findObject(By.scrollable(true)) ?: return
    list.setGestureMargin(device.displayWidth / 5)
    repeat(3) { list.fling(Direction.DOWN) }
    repeat(3) { list.fling(Direction.UP) }
}
