package libcore

import (
	"os"
	"reflect"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
)

// IsDomainRuleSet reports whether all branches can be evaluated by domain name
// before a DNS response exists. In particular, projecting destination CIDRs into
// DNS would activate legacy address-filter mode in sing-box 1.14.
func IsDomainRuleSet(path, format string) bool {
	var source option.PlainRuleSetCompat
	var err error
	switch format {
	case C.RuleSetFormatSource:
		content, readErr := os.ReadFile(path)
		if readErr != nil {
			return false
		}
		source, err = json.UnmarshalExtended[option.PlainRuleSetCompat](content)
	case C.RuleSetFormatBinary:
		file, openErr := os.Open(path)
		if openErr != nil {
			return false
		}
		defer file.Close()
		source, err = srs.Read(file, false)
	default:
		return false
	}
	if err != nil {
		return false
	}
	set, err := source.Upgrade()
	return err == nil && domainRulesOnly(set.Rules)
}

func domainRulesOnly(rules []option.HeadlessRule) bool {
	if len(rules) == 0 {
		return false
	}
	for _, rule := range rules {
		switch rule.Type {
		case C.RuleTypeDefault:
			fields := rule.DefaultOptions
			domainOnly := option.DefaultHeadlessRule{
				Domain: fields.Domain, DomainSuffix: fields.DomainSuffix,
				DomainKeyword: fields.DomainKeyword, DomainRegex: fields.DomainRegex,
				DomainMatcher: fields.DomainMatcher,
				AdGuardDomain: fields.AdGuardDomain, AdGuardDomainMatcher: fields.AdGuardDomainMatcher,
				Invert: fields.Invert,
			}
			if !domainOnly.IsValid() || !reflect.DeepEqual(fields, domainOnly) {
				return false
			}
		case C.RuleTypeLogical:
			if !domainRulesOnly(rule.LogicalOptions.Rules) {
				return false
			}
		default:
			return false
		}
	}
	return true
}
