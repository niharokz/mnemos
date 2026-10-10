//go:build unix

package vault

import "syscall"

// writable asks the kernel whether this process may write to abs (W_OK).
func writable(abs string) bool { return syscall.Access(abs, 2) == nil }
