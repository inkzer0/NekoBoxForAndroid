package libcore

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/srs"
	"github.com/sagernet/sing-box/option"
)

func TestAppManagedLocalRuleSets(t *testing.T) {
	root := t.TempDir()
	source := filepath.Join(root, "1.source")
	binary := filepath.Join(root, "2.binary")
	old := []byte(`{"version":3,"rules":[{"domain":["old.invalid"]}]}`)
	if err := os.WriteFile(source, old, 0600); err != nil {
		t.Fatal(err)
	}
	var plain option.PlainRuleSetCompat
	if err := json.Unmarshal(old, &plain); err != nil {
		t.Fatal(err)
	}
	var data bytes.Buffer
	if err := srs.Write(&data, plain.Options, 3); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(binary, data.Bytes(), 0600); err != nil {
		t.Fatal(err)
	}
	previous := externalAssetsPath
	externalAssetsPath = os.Getenv("NEKO_GEO_ASSETS")
	defer func() { externalAssetsPath = previous }()
	config, _ := json.Marshal(map[string]any{
		"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}},
		"route": map[string]any{
			"rule_set": []any{
				map[string]any{"type": "local", "tag": "first", "format": "source", "path": source},
				map[string]any{"type": "local", "tag": "second", "format": "binary", "path": binary},
				map[string]any{"type": "local", "tag": "geoip:us", "format": "binary", "path": "geoip:us"},
				map[string]any{"type": "local", "tag": "geosite:github", "format": "binary", "path": "geosite:github"},
			},
			"rules": []any{
				map[string]any{"rule_set": []string{"first", "second"}, "action": "route", "outbound": "direct"},
				map[string]any{"rule_set": []string{"geoip:us", "geosite:github"}, "action": "route", "outbound": "direct"},
				map[string]any{"domain": "ordinary.invalid", "action": "route", "outbound": "direct"},
			},
		},
	})
	b := testBox(t, string(config))
	first, _ := b.Router().RuleSet("first")
	second, _ := b.Router().RuleSet("second")
	if !first.Match(&adapter.InboundContext{Domain: "old.invalid"}) || !second.Match(&adapter.InboundContext{Domain: "old.invalid"}) {
		t.Fatal("source/binary content missing")
	}
	for _, domain := range []string{"new.invalid", "newer.invalid"} {
		candidate := filepath.Join(root, "candidate")
		content, _ := json.Marshal(map[string]any{"version": 3, "rules": []any{map[string]any{"domain": domain}}})
		if err := os.WriteFile(candidate, content, 0600); err != nil {
			t.Fatal(err)
		}
		// Validate through the same existing Java-bound constructor used by the App, without Start.
		validation, _ := json.Marshal(map[string]any{"route": map[string]any{"rule_set": []any{map[string]any{"type": "local", "tag": "check", "format": "source", "path": candidate}}}})
		v, err := NewSingBoxInstance(string(validation), nil)
		if err != nil {
			t.Fatal(err)
		}
		v.Close()
		// Windows rename cannot replace existing files; this host test uses the platform replacement helper.
		if err = replaceRuleSetFixture(candidate, source); err != nil {
			t.Fatal(err)
		}
		deadline := time.Now().Add(5 * time.Second)
		for !first.Match(&adapter.InboundContext{Domain: domain}) && time.Now().Before(deadline) {
			time.Sleep(10 * time.Millisecond)
		}
		if !first.Match(&adapter.InboundContext{Domain: domain}) {
			t.Fatal("atomic update did not reload", domain)
		}
	}
	invalid := filepath.Join(root, "invalid")
	os.WriteFile(invalid, []byte(`{"version":3,"rules":[{"invalid_field":true}]}`), 0600)
	bad, _ := json.Marshal(map[string]any{"route": map[string]any{"rule_set": []any{map[string]any{"type": "local", "tag": "check", "format": "source", "path": invalid}}}})
	if v, err := NewSingBoxInstance(string(bad), nil); err == nil {
		v.Close()
		t.Fatal("invalid source accepted")
	}
}

func TestActualAppRemoteRuleSetConfigs(t *testing.T) {
	root := os.Getenv("NEKO_REMOTE_CONFIG_FIXTURES")
	if root == "" {
		t.Skip("actual App remote config fixtures required")
	}
	previous := externalAssetsPath
	externalAssetsPath = os.Getenv("NEKO_GEO_ASSETS")
	defer func() { externalAssetsPath = previous }()
	t.Setenv("NEKO_CONFIG_FIXTURES", root)
	TestActualAppGeneratedConfigs(t)
}
