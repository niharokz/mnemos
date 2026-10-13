package com.nihars.mnemos.core.vaultfs

/** Vault errors with messages that are safe to show to the user as they are. */
sealed class VaultException(message: String) : Exception(message) {
    class NotFound(path: String) : VaultException("“$path” no longer exists. It may have been moved or deleted by another app.")
    class AlreadyExists(path: String) : VaultException("“$path” already exists. Pick another name.")
    class PathRejected(path: String, why: String) : VaultException("Can't use “$path”: $why")
    class PermissionDenied(path: String) : VaultException(
        "Android refused to write “$path”. Check that Mnemos still has All files access and the folder is writable.",
    )
    class IoFailure(path: String, cause: Throwable) : VaultException("Couldn't access “$path” (${cause.javaClass.simpleName}). Try again.") {
        init {
            initCause(cause)
        }
    }
}
