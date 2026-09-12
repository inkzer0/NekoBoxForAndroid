# Phase 2 tests

Run the Android JVM tests with the existing Android/JDK toolchain:

```text
gradlew -I app/src/test/phase2.init.gradle :app:testOssDebugUnitTest
```

The test-only init script supplies JUnit and Robolectric without changing production dependencies.
Tests execute against SDK 21 and 28 shadows. They cover the generated Room 6→7 migration,
old node/group/rule data, CRUD, references, actual backup v1 import/export, HTTP failures,
invalid content, cache preservation, atomic replacement, and deletion. Windows can reject
replacing a concurrently opened file; the test reports those refusals and checks preservation.
These tests do not establish Android device networking, background scheduling or file-watch behavior.

`core/` contains additional host contracts for the unchanged libcore constructor and official
sing-box v1.14.0. Run in an isolated copy alongside the existing `compat_host_test.go` host
file list and `dns_callback_test.go`, selecting the platform-specific replacement helper.
Set `NEKO_GEO_ASSETS` to the verified GeoIP/GeoSite asset directory and
`NEKO_CONFIG_FIXTURES` to the 37 actual ConfigBuilder fixtures. Do not patch the official Core.
