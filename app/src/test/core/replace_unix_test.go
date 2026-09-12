//go:build !windows

package libcore

import "os"

func replaceRuleSetFixture(from, to string) error { return os.Rename(from, to) }
