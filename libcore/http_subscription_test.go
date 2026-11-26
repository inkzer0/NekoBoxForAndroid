package libcore

import (
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestSubscriptionRequireSocks5DoesNotDialOrigin(t *testing.T) {
	var requests atomic.Int32
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		requests.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer origin.Close()

	proxy, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()
	proxyPort := int32(proxy.Addr().(*net.TCPAddr).Port)
	go func() {
		// A listening proxy that closes the session during SOCKS negotiation.
		for {
			conn, acceptErr := proxy.Accept()
			if acceptErr != nil {
				return
			}
			_ = conn.Close()
		}
	}()

	for _, port := range []int32{0, proxyPort} {
		for _, h3 := range []bool{false, true} {
			client := NewHttpClient().(*httpClient)
			client.h1h2Client.Timeout = 2 * time.Second
			client.RequireSocks5(port)
			if h3 {
				client.TryH3Direct()
			}
			request := client.NewRequest()
			if err := request.SetURL(origin.URL); err != nil {
				t.Fatal(err)
			}
			_, err := request.Execute()
			client.Close()
			if !errors.Is(err, errFailConnectSocks5) {
				t.Fatalf("strict mode with port=%d and H3 configured=%t produced %v, want SOCKS failure", port, h3, err)
			}
		}
	}
	if count := requests.Load(); count != 0 {
		t.Fatalf("origin received %d direct calls", count)
	}

	for _, h3 := range []bool{false, true} {
		client := NewHttpClient().(*httpClient)
		client.h1h2Client.Timeout = 2 * time.Second
		client.TrySocks5(proxyPort)
		if h3 {
			client.TryH3Direct()
		}
		request := client.NewRequest()
		if err := request.SetURL(origin.URL); err != nil {
			t.Fatal(err)
		}
		response, err := request.Execute()
		if err != nil {
			t.Fatalf("unrestricted refresh with H3 configured=%t should still fall back: %v", h3, err)
		}
		if _, err = response.GetContent(); err != nil {
			t.Fatal(err)
		}
		client.Close()
	}
	if count := requests.Load(); count != 2 {
		t.Fatalf("unrestricted origin received %d calls, want 2", count)
	}
}
