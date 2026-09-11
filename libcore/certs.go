package libcore

import (
	"crypto/x509"
	"log"
	"sync/atomic"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

var externalRootPEM atomic.Pointer[string]

func updateRootCACerts(pem []byte) {
	x509.SystemCertPool()
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		log.Println("failed to append certificates from pem")
		return
	}
	systemRoots = roots
	value := string(pem)
	externalRootPEM.Store(&value)
	log.Println("external ca.pem was loaded")
}

//go:linkname initSystemRoots crypto/x509.initSystemRoots
func initSystemRoots()
