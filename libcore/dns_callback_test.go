//go:build !android

package libcore

import (
	"context"
	"errors"
	"fmt"
	"runtime"
	"sync"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
)

type callbackResolver struct{ lookup func(*ExchangeContext) error }

func (r callbackResolver) Raw() bool            { return false }
func (r callbackResolver) NetworkHandle() int64 { return 0 }
func (r callbackResolver) Lookup(c *ExchangeContext, network, domain string) error {
	return r.lookup(c)
}
func (r callbackResolver) Exchange(*ExchangeContext, []byte) error {
	return errors.New("unexpected raw exchange")
}

type cancelFunc func() error

func (f cancelFunc) Invoke() error { return f() }

func lookupWith(t *testing.T, ctx context.Context, f func(*ExchangeContext) error) (*mDNS.Msg, error) {
	t.Helper()
	q := new(mDNS.Msg)
	q.SetQuestion("fixture.invalid.", mDNS.TypeA)
	return newPlatformTransport(callbackResolver{f}, "fixture", option.LocalDNSServerOptions{}).Exchange(ctx, q)
}
func checkAddress(t *testing.T, msg *mDNS.Msg, want ...string) {
	t.Helper()
	if msg == nil || len(msg.Answer) != len(want) {
		t.Fatalf("answer=%v want=%v", msg, want)
	}
	for i, v := range want {
		record, ok := msg.Answer[i].(*mDNS.A)
		if !ok || record.A.String() != v {
			t.Fatalf("answer[%d]=%v want=%s", i, msg.Answer[i], v)
		}
	}
}
func TestDNSCallbackSuccess(t *testing.T) {
	for _, addresses := range []string{"192.0.2.1", "192.0.2.1\n192.0.2.2"} {
		t.Run(addresses, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 800*time.Millisecond)
			defer cancel()
			start := time.Now()
			msg, err := lookupWith(t, ctx, func(c *ExchangeContext) error { c.Success(addresses); return nil })
			if err != nil {
				t.Fatal(err)
			}
			if time.Since(start) > 400*time.Millisecond {
				t.Fatal("success waited for deadline")
			}
			if addresses == "192.0.2.1" {
				checkAddress(t, msg, "192.0.2.1")
			} else {
				checkAddress(t, msg, "192.0.2.1", "192.0.2.2")
			}
		})
	}
}
func TestDNSCallbackErrors(t *testing.T) {
	sentinel := errors.New("lookup failed")
	for _, test := range []struct {
		name string
		f    func(*ExchangeContext) error
		want error
	}{
		{"NXDOMAIN", func(c *ExchangeContext) error { c.ErrorCode(mDNS.RcodeNameError); return nil }, dns.RcodeError(mDNS.RcodeNameError)},
		{"errno", func(c *ExchangeContext) error { c.ErrnoCode(int32(syscall.EIO)); return nil }, syscall.EIO},
		{"lookup-error", func(c *ExchangeContext) error { return sentinel }, sentinel},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			start := time.Now()
			_, err := lookupWith(t, ctx, test.f)
			if !errors.Is(err, test.want) {
				t.Fatalf("got %v want %v", err, test.want)
			}
			if time.Since(start) > 500*time.Millisecond {
				t.Fatal("error waited for deadline")
			}
		})
	}
}
func TestDNSCallbackCancellation(t *testing.T) {
	for _, deadline := range []bool{false, true} {
		t.Run(fmt.Sprint(deadline), func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			if deadline {
				cancel()
				ctx, cancel = context.WithTimeout(context.Background(), 30*time.Millisecond)
			}
			defer cancel()
			started, stopped := make(chan struct{}), make(chan struct{})
			done := make(chan error, 1)
			go func() {
				_, err := lookupWith(t, ctx, func(c *ExchangeContext) error {
					c.OnCancel(cancelFunc(func() error { close(stopped); return nil }))
					close(started)
					return nil
				})
				done <- err
			}()
			<-started
			if !deadline {
				cancel()
			}
			select {
			case err := <-done:
				want := context.Canceled
				if deadline {
					want = context.DeadlineExceeded
				}
				if !errors.Is(err, want) {
					t.Fatalf("got %v want %v", err, want)
				}
			case <-time.After(time.Second):
				t.Fatal("cancellation hung")
			}
			select {
			case <-stopped:
			case <-time.After(time.Second):
				t.Fatal("cancellation listener leaked")
			}
		})
	}
}
func TestDNSCallbackDuplicate(t *testing.T) {
	for _, test := range []struct {
		name    string
		calls   func(*ExchangeContext)
		failure bool
	}{
		{"success-success", func(c *ExchangeContext) { c.Success("192.0.2.1"); c.Success("192.0.2.2") }, false},
		{"success-error", func(c *ExchangeContext) {
			c.Success("192.0.2.1")
			c.ErrorCode(mDNS.RcodeNameError)
			c.ErrnoCode(int32(syscall.EIO))
		}, false},
		{"error-success", func(c *ExchangeContext) { c.ErrorCode(mDNS.RcodeNameError); c.Success("192.0.2.1") }, true},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			msg, err := lookupWith(t, ctx, func(c *ExchangeContext) error { test.calls(c); return nil })
			if test.failure {
				if !errors.Is(err, dns.RcodeError(mDNS.RcodeNameError)) {
					t.Fatal(err)
				}
			} else {
				if err != nil {
					t.Fatal(err)
				}
				checkAddress(t, msg, "192.0.2.1")
			}
		})
	}
}
func TestDNSCallbackConcurrentCancellation(t *testing.T) {
	for i := 0; i < 500; i++ {
		ctx, cancel := context.WithCancel(context.Background())
		ready := make(chan struct{})
		var workers sync.WaitGroup
		msg, err := lookupWith(t, ctx, func(c *ExchangeContext) error {
			workers.Add(3)
			go func() { defer workers.Done(); <-ready; c.Success("192.0.2.1") }()
			go func() { defer workers.Done(); <-ready; cancel() }()
			go func() { defer workers.Done(); <-ready; c.ErrorCode(mDNS.RcodeNameError); c.Success("192.0.2.2") }()
			close(ready)
			return nil
		})
		workers.Wait()
		cancel()
		if err == nil {
			checkAddress(t, msg, "192.0.2.1")
		} else if !errors.Is(err, context.Canceled) && !errors.Is(err, dns.RcodeError(mDNS.RcodeNameError)) {
			t.Fatal(err)
		}
	}
}
func TestDNSCallbackNoWatcherLeak(t *testing.T) {
	before := runtime.NumGoroutine()
	var canceled atomic.Int32
	for i := 0; i < 200; i++ {
		_, err := lookupWith(t, context.Background(), func(c *ExchangeContext) error {
			c.OnCancel(cancelFunc(func() error { canceled.Add(1); return nil }))
			c.Success("192.0.2.1")
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	deadline := time.Now().Add(2 * time.Second)
	for runtime.NumGoroutine() > before+5 && time.Now().Before(deadline) {
		runtime.GC()
		time.Sleep(10 * time.Millisecond)
	}
	if n := runtime.NumGoroutine(); n > before+5 {
		t.Fatalf("watchers leaked: before=%d after=%d", before, n)
	}
}
func TestDNSRawCallbackFirstCompletion(t *testing.T) {
	msg := new(mDNS.Msg)
	msg.SetQuestion("fixture.invalid.", mDNS.TypeA)
	raw, err := msg.Pack()
	if err != nil {
		t.Fatal(err)
	}
	var completed atomic.Int32
	c := ExchangeContext{done: func() { completed.Add(1) }}
	c.RawSuccess(raw)
	c.ErrorCode(mDNS.RcodeNameError)
	c.RawSuccess([]byte{1})
	if completed.Load() != 1 || c.error != nil || len(c.message.Question) != 1 {
		t.Fatal("raw callback overwritten or completed twice")
	}
}

func TestDNSCallbackLookupErrorReleasesWatcher(t *testing.T) {
	stopped := make(chan struct{})
	sentinel := errors.New("lookup rejected")
	_, err := lookupWith(t, context.Background(), func(c *ExchangeContext) error {
		c.OnCancel(cancelFunc(func() error { close(stopped); return nil }))
		return sentinel
	})
	if !errors.Is(err, sentinel) {
		t.Fatal(err)
	}
	select {
	case <-stopped:
	case <-time.After(time.Second):
		t.Fatal("lookup error leaked watcher")
	}
}

func TestDNSCallbackConcurrentSuccess(t *testing.T) {
	for i := 0; i < 200; i++ {
		var workers sync.WaitGroup
		var completed atomic.Int32
		done := make(chan struct{})
		c := ExchangeContext{done: func() { completed.Add(1); close(done) }}
		workers.Add(2)
		for _, address := range []string{"192.0.2.1", "192.0.2.2"} {
			go func(address string) { defer workers.Done(); c.Success(address) }(address)
		}
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Fatal("callback hung")
		}
		if len(c.addresses) != 1 {
			t.Fatal("result missing after completion")
		}
		first := c.addresses[0]
		workers.Wait()
		if completed.Load() != 1 || c.addresses[0] != first {
			t.Fatal("duplicate changed result")
		}
	}
}

func TestDNSCallbackExchangeAsync(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	var resolverWorkers sync.WaitGroup
	transport := newPlatformTransport(callbackResolver{func(c *ExchangeContext) error {
		resolverWorkers.Add(1)
		go func() { defer resolverWorkers.Done(); c.Success("192.0.2.1"); c.Success("192.0.2.2") }()
		return nil
	}}, "fixture", option.LocalDNSServerOptions{})
	question := new(mDNS.Msg)
	question.SetQuestion("fixture.invalid.", mDNS.TypeA)
	done := make(chan struct{})
	var called atomic.Int32
	transport.ExchangeAsync(ctx, question, func(msg *mDNS.Msg, err error) {
		if err != nil {
			t.Error(err)
		} else {
			checkAddress(t, msg, "192.0.2.1")
		}
		called.Add(1)
		close(done)
	})
	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal("ExchangeAsync waited for timeout")
	}
	resolverWorkers.Wait()
	if called.Load() != 1 {
		t.Fatal("duplicate async completion")
	}
}
