package libcore

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/sagernet/sing-box/common/srs"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

func TestIsDomainRuleSet(t *testing.T) {
	for _, tt := range []struct {
		name  string
		rules string
		want  bool
	}{
		{"domains", `[{"domain":["fixture.invalid"]},{"type":"logical","mode":"or","rules":[{"domain_suffix":".example.invalid"},{"domain_keyword":"fixture"}]}]`, true},
		{"destination CIDR", `[{"ip_cidr":"192.0.2.0/24"}]`, false},
		{"mixed branches", `[{"domain":"fixture.invalid"},{"ip_cidr":"192.0.2.0/24"}]`, false},
		{"nested mixed", `[{"type":"logical","mode":"and","rules":[{"domain":"fixture.invalid"},{"ip_cidr":"192.0.2.0/24"}]}]`, false},
		{"non-domain condition", `[{"domain":"fixture.invalid","source_port":1234}]`, false},
	} {
		t.Run(tt.name, func(t *testing.T) {
			source := []byte(`{"version":3,"rules":` + tt.rules + `}`)
			plain, err := json.UnmarshalExtended[option.PlainRuleSetCompat](source)
			if err != nil {
				t.Fatal(err)
			}
			dir := t.TempDir()
			sourcePath, binaryPath := filepath.Join(dir, "rules.json"), filepath.Join(dir, "rules.srs")
			if err = os.WriteFile(sourcePath, source, 0o600); err != nil {
				t.Fatal(err)
			}
			f, err := os.Create(binaryPath)
			if err != nil {
				t.Fatal(err)
			}
			writeErr := srs.Write(f, plain.Options, plain.Version)
			closeErr := f.Close()
			if writeErr != nil || closeErr != nil {
				t.Fatalf("write SRS: %v, close: %v", writeErr, closeErr)
			}
			for _, input := range []struct{ path, format string }{{sourcePath, "source"}, {binaryPath, "binary"}} {
				if got := IsDomainRuleSet(input.path, input.format); got != tt.want {
					t.Errorf("%s classification = %t; want %t", input.format, got, tt.want)
				}
			}
		})
	}
	if IsDomainRuleSet(filepath.Join(t.TempDir(), "missing.json"), "source") {
		t.Fatal("missing cache must not be projected to DNS")
	}
}
