//go:build !unix

package vault

import "os"

// writable is a best-effort check on systems without access(2): the owner write bit.
func writable(abs string) bool {
	info, err := os.Stat(abs)
	return err == nil && info.Mode().Perm()&0o200 != 0
}
