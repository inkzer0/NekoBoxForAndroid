package libcore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
)

// Uses actual ConfigBuilder outputs, not hand-written substitutes for the App rule.
func TestActualLegacyGeoRuleConfigs(t *testing.T) {
	root := os.Getenv("NEKO_LEGACY_GEO_FIXTURES")
	if root == "" {
		t.Fatal("NEKO_LEGACY_GEO_FIXTURES is required")
	}
	files, err := filepath.Glob(filepath.Join(root, "*.json"))
	if err != nil || len(files) < 64 {
		t.Fatalf("missing fixture matrix: %d, %v", len(files), err)
	}
	previous := externalAssetsPath
	externalAssetsPath = os.Getenv("NEKO_GEO_ASSETS")
	defer func() { externalAssetsPath = previous }()
	for _, file := range files {
		t.Run(filepath.Base(file), func(t *testing.T) {
			data, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			var config map[string]any
			if err = json.Unmarshal(data, &config); err != nil {
				t.Fatal(err)
			}
			route := config["route"].(map[string]any)
			defined := map[string]bool{}
			for _, item := range route["rule_set"].([]any) {
				defined[item.(map[string]any)["tag"].(string)] = true
			}
			var visit func(any)
			visit = func(value any) {
				switch v := value.(type) {
				case map[string]any:
					for key, child := range v {
						if key == "rule_set" {
							if tags, ok := child.([]any); ok {
								for _, tag := range tags {
									if s, ok := tag.(string); ok && !defined[s] {
										t.Errorf("dangling rule_set %s", s)
									}
								}
							}
						}
						visit(child)
					}
				case []any:
					for _, child := range v {
						visit(child)
					}
				}
			}
			visit(config)
			b, err := NewSingBoxInstance(string(data), nil)
			if err != nil {
				t.Fatalf("official Core creation: %v", err)
			}
			defer b.Close()
			name := filepath.Base(file)
			mixed := strings.HasPrefix(name, "mixed-") || strings.HasPrefix(name, "multiple-")
			if !mixed {
				return
			}
			if !defined["geosite:github"] || !defined["geoip:us"] {
				t.Fatal("mixed conditions were lost")
			}
			for index, item := range route["rules"].([]any) {
				value := item.(map[string]any)
				tags, _ := value["rule_set"].([]any)
				foundSite, foundIP := false, false
				for _, tag := range tags {
					foundSite = foundSite || tag == "geosite:github"
					foundIP = foundIP || tag == "geoip:us"
				}
				if !foundSite || !foundIP {
					continue
				}
				r := b.Router().Rules()[index]
				if starter, ok := r.(interface{ Start() error }); ok {
					if err := starter.Start(); err != nil {
						t.Fatal(err)
					}
				}
				for _, tc := range []struct {
					domain, ip string
					want       bool
				}{
					{"github.com", "10.1.2.3:443", true},
					{"unrelated.invalid", "8.8.8.8:443", true},
					{"github.com", "8.8.8.8:443", true},
					{"unrelated.invalid", "10.1.2.3:443", false},
					{"unrelated.invalid", "[2001:4860:4860::8888]:443", true},
					{"unrelated.invalid", "[fd00::1]:443", false},
				} {
					metadata := adapter.InboundContext{Domain: tc.domain, Destination: M.ParseSocksaddr(tc.ip), Network: "tcp", Protocol: "tls", Source: M.ParseSocksaddr("172.16.0.2:1500"), ProcessInfo: &adapter.ConnectionOwner{UserId: 10001}}
					want := tc.want
					if strings.Contains(name, "invert") {
						want = !want
					}
					if got := r.Match(&metadata); got != want {
						t.Errorf("domain=%s ip=%s got=%v want=%v", tc.domain, tc.ip, got, want)
					}
					if strings.Contains(name, "constrained") && tc.want {
						for _, change := range []func(*adapter.InboundContext){
							func(m *adapter.InboundContext) { m.Network = "udp" },
							func(m *adapter.InboundContext) { m.Protocol = "http" },
							func(m *adapter.InboundContext) { m.Destination.Port = 80 },
							func(m *adapter.InboundContext) { m.Source.Port = 1501 },
							func(m *adapter.InboundContext) { m.Source = M.ParseSocksaddr("192.168.0.1:1500") },
							func(m *adapter.InboundContext) { m.ProcessInfo = &adapter.ConnectionOwner{UserId: 10002} },
						} {
							negative := metadata
							negative.ResetRuleCache()
							change(&negative)
							if r.Match(&negative) {
								t.Error("non-address constraint was bypassed")
							}
						}
					}
				}
				if strings.Contains(name, "remote") {
					m := adapter.InboundContext{Domain: "first.invalid", Destination: M.ParseSocksaddr("10.1.2.3:443"), Network: "tcp"}
					if !r.Match(&m) {
						t.Error("Remote Rule Set alternative lost")
					}
				}
				if strings.HasPrefix(name, "multiple-") {
					if !defined["geosite:google"] || !defined["geoip:cn"] {
						t.Fatal("second values missing")
					}
					for _, destination := range []string{"google.com:443", "114.114.114.114:443"} {
						address := M.ParseSocksaddr(destination)
						m := adapter.InboundContext{Domain: address.Fqdn, Destination: address, Network: "tcp"}
						if !r.Match(&m) {
							t.Errorf("second value did not match: %s", destination)
						}
					}
				}
				return
			}
			t.Fatal("mixed route rule missing")
		})
	}
}
