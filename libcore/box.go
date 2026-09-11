package libcore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/protocol/group"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

var mainInstance *BoxInstance
var instancesMu sync.Mutex
var instances = make(map[*BoxInstance]adapter.ConnectionManager)
var coreCommit string

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		"commit: " + coreCommit,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func ResetAllConnections(system bool) {
	if system {
		instancesMu.Lock()
		managers := make([]adapter.ConnectionManager, 0, len(instances))
		for _, manager := range instances {
			managers = append(managers, manager)
		}
		instancesMu.Unlock()
		for _, manager := range managers {
			manager.CloseAll()
		}
		log.Println("Reset system connections done")
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access      sync.Mutex
	selectionMu sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	v2api        *v2rayapi.StatsService
	selector     *group.Selector
	pauseManager pause.Manager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	defer func() {
		if err != nil {
			cancel()
		}
	}()
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(), certificate.NewRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	config, sniffOverrides, err := sniffCompatibility(config)
	if err != nil {
		return nil, fmt.Errorf("decode sniff compatibility: %w", err)
	}
	var options option.Options
	err = options.UnmarshalJSONContext(ctx, []byte(config))
	if err != nil {
		return nil, fmt.Errorf("decode config: %v", err)
	}
	if err = loadGeoRuleSets(&options); err != nil {
		return nil, fmt.Errorf("load geo rules: %w", err)
	}

	// create box
	applyExternalCertificateOptions(&options)
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}
	if err = applySniffCompatibility(instance.Router(), sniffOverrides); err != nil {
		instance.Close()
		return nil, err
	}

	b = &BoxInstance{
		Box:          instance,
		cancel:       cancel,
		pauseManager: service.FromContext[pause.Manager](ctx),
	}
	instancesMu.Lock()
	instances[b] = service.FromContext[adapter.ConnectionManager](ctx)
	instancesMu.Unlock()

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

	// clear main instance
	instancesMu.Lock()
	delete(instances, b)
	wasMain := mainInstance == b
	if wasMain {
		mainInstance = nil
	}
	instancesMu.Unlock()
	if wasMain {
		goServeProtect(false)
	}

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	instancesMu.Lock()
	mainInstance = b
	instancesMu.Unlock()
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.v2api != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.v2api = v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	b.Box.Router().AppendTracker(b.v2api)
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.v2api == nil {
		return 0
	}
	response, err := b.v2api.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
		Name: fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct), Reset_: true,
	})
	if err != nil {
		return 0
	}
	return response.Stat.Value
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		b.selectionMu.Lock()
		before := b.selector.Now()
		selected := b.selector.SelectOutbound(tag)
		changed := selected && before != tag
		b.selectionMu.Unlock()
		// Official SelectOutbound has completed Interrupt before the App resets
		// every instance and updates its traffic, notification and Binder state.
		if changed && intfNB4A != nil {
			intfNB4A.Selector_OnProxySelected(b.selector.Tag(), tag)
		}
		return selected
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	if i == nil {
		instancesMu.Lock()
		i = mainInstance
		instancesMu.Unlock()
	}
	return speedtest.UrlTest(urlTestClient(i), link, timeout, speedtest.UrlTestStandard_RTT)
}

// StatsService tracks inbound reads as uplink. A URL test owns the outbound
// side: reverse only the accounting orientation, leaving actual I/O unchanged.
type reverseStatsConn struct{ net.Conn }

func (c *reverseStatsConn) Read(p []byte) (int, error)  { return c.Conn.Write(p) }
func (c *reverseStatsConn) Write(p []byte) (int, error) { return c.Conn.Read(p) }

func urlTestClient(instance *BoxInstance) *http.Client {
	transport := &http.Transport{TLSHandshakeTimeout: 3 * time.Second, ResponseHeaderTimeout: 3 * time.Second}
	if instance != nil {
		transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
			outbound := instance.Outbound().Default()
			conn, err := dialer.NewDetour(instance.Outbound(), outbound.Tag(), true).DialContext(ctx, network, M.ParseSocksaddr(address))
			if err != nil {
				return nil, err
			}
			if instance.v2api != nil {
				conn = &reverseStatsConn{instance.v2api.RoutedConnection(ctx, &reverseStatsConn{conn}, adapter.InboundContext{}, nil, outbound)}
			}
			return conn, nil
		}
	}
	return &http.Client{Transport: transport}
}

var protectCloser io.Closer

func goServeProtect(start bool) {
	if protectCloser != nil {
		protectCloser.Close()
		protectCloser = nil
	}
	if start {
		protectCloser = protect_server.ServeProtect("protect_path", false, 0, func(fd int) {
			intfBox.AutoDetectInterfaceControl(int32(fd))
		})
	}
}
