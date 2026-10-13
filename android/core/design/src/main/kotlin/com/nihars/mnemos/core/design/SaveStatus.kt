package com.nihars.mnemos.core.design

/** The save-status states shown by the dot in the top bar. */
enum class SaveStatus(val label: String) {
    SAVED("Saved"),
    EDITED("Edited"),
    SAVING("Saving"),
    CONFLICT("Conflict"),
    READ_ONLY("Read-only"),
}
