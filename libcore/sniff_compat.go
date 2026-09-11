package libcore

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/route/rule"
)

// v1.14 retains the public runtime override flag but no JSON field for it.
// Consume only the explicit App marker; all other custom JSON stays subject
// to the official decoder without legacy-field rewriting.
func sniffCompatibility(config string) (string, []int, error) {
	if !strings.Contains(config, "_neko_sniff_override") {
		return config, nil, nil
	}
	var root map[string]json.RawMessage
	if err := json.Unmarshal([]byte(config), &root); err != nil {
		return "", nil, err
	}
	var route map[string]json.RawMessage
	if err := json.Unmarshal(root["route"], &route); err != nil {
		return "", nil, err
	}
	var rules []map[string]json.RawMessage
	if err := json.Unmarshal(route["rules"], &rules); err != nil {
		return "", nil, err
	}
	var indices []int
	for index, item := range rules {
		marker, exists := item["_neko_sniff_override"]
		if !exists {
			continue
		}
		var enabled bool
		if err := json.Unmarshal(marker, &enabled); err != nil {
			return "", nil, err
		}
		var action string
		if err := json.Unmarshal(item["action"], &action); err != nil {
			return "", nil, err
		}
		if action != "sniff" {
			return "", nil, fmt.Errorf("route rule %d: sniff override requires a sniff action", index)
		}
		delete(item, "_neko_sniff_override")
		if enabled {
			indices = append(indices, index)
		}
	}
	var err error
	route["rules"], err = json.Marshal(rules)
	if err != nil {
		return "", nil, err
	}
	root["route"], err = json.Marshal(route)
	if err != nil {
		return "", nil, err
	}
	data, err := json.Marshal(root)
	return string(data), indices, err
}

func applySniffCompatibility(router adapter.Router, indices []int) error {
	rules := router.Rules()
	for _, index := range indices {
		if index >= len(rules) {
			return fmt.Errorf("sniff rule %d missing", index)
		}
		action, ok := rules[index].Action().(*rule.RuleActionSniff)
		if !ok {
			return fmt.Errorf("route rule %d is not a sniff action", index)
		}
		action.OverrideDestination = true
	}
	return nil
}
