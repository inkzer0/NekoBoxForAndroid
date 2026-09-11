package libcore

import (
	"fmt"
	"strings"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

// Preserve the App's geoip:/geosite: local paths at the libcore boundary.
// The official loader receives ordinary inline rules, never a Core hook.
func loadGeoRuleSets(options *option.Options) error {
	if options.Route == nil {
		return nil
	}
	for index := range options.Route.RuleSet {
		ruleSet := &options.Route.RuleSet[index]
		if ruleSet.Type != C.RuleSetTypeLocal {
			continue
		}
		path := ruleSet.LocalOptions.Path
		var rules []option.HeadlessRule
		var err error
		switch {
		case strings.HasPrefix(path, "geoip:"):
			rules, err = geoIPHeadlessRules(strings.TrimPrefix(path, "geoip:"))
		case strings.HasPrefix(path, "geosite:"):
			rules, err = geoSiteHeadlessRules(strings.TrimPrefix(path, "geosite:"))
		default:
			continue
		}
		if err != nil {
			return fmt.Errorf("%s: %w", ruleSet.Tag, err)
		}
		*ruleSet = option.RuleSet{Type: C.RuleSetTypeInline, Tag: ruleSet.Tag, InlineOptions: option.PlainRuleSet{Rules: rules}}
	}
	return nil
}
