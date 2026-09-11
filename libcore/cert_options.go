package libcore

import "github.com/sagernet/sing-box/option"

// Official Android uses its own certificate store. Preserve the existing
// external ca.pem replacement, rather than adding those roots to that store.
func applyExternalCertificateOptions(options *option.Options) {
	pem := externalRootPEM.Load()
	if pem == nil {
		return
	}
	var certificates option.CertificateOptions
	if options.Certificate != nil {
		certificates = *options.Certificate
		if certificates.Store != "" && certificates.Store != "system" {
			return
		}
	}
	certificates.Store = "none"
	certificates.Certificate = append([]string{*pem}, certificates.Certificate...)
	options.Certificate = &certificates
}
