# Legacy GeoSite + GeoIP regression

This fixes a BROKEN-BASELINE predating Phase 1 and Phase 2. Both calls to
`makeSingBoxRule` reset `rule_set`. The IP call discarded the GeoSite tags before
`generateRuleSet`, while DNS still referenced them.

The legacy model has domain/IP address fields and no logical-mode field. Its
converter creates one default rule. Both the former Matsuri matcher and official
sing-box v1.14.0 put domain and destination IP conditions in the same address
group (OR). Rule-set alternatives are also OR; port, network, protocol, source
and user constraints still apply to the entire rule. This is not an AND between
GeoSite and GeoIP. See official `route/rule/rule_abstract.go` (`evaluateGroups`)
and `route/rule/rule_item_rule_set.go` (`matchWithOuterGroups`). The App's existing
Geo compatibility loader supplies one address-only headless rule per Geo tag.

The production change saves domain tags before IP conversion and restores them
in the address alternatives before definitions are generated. DNS generation,
actions, custom config, Room, UI and libcore are unchanged.

## Actual App fixtures

Compile `:app:compileOssDebugJavaWithJavac` using the project's existing build
toolchain. Supply a text file containing one absolute resolved compile dependency
JAR/AAR path per line (Gradle `ossDebugCompileClasspath.files`). For example, a
temporary audit init script can register a task printing these paths; no production
Gradle change is needed.

```text
python app/src/test/fixtures/legacy_geo_fixture.py --repo REPO --classpath-file CLASSPATH.txt --android-jar ANDROID_SDK/platforms/android-35/android.jar --jdk JDK17 --output AUDIT
```

The runner executes the actual compiled ConfigBuilder, formatter and RuleEntity.
Only Android platform/DAO access is stubbed. It produces the original 37 Phase 1
fixtures, four Phase 2 fixtures and 68 Geo fixtures. The Geo matrix includes single
and multiple tags, DNS routing/FakeIP toggles, BYPASS/PROXY/BLOCK/specific-node,
DIRECT custom override, constraints, invert and Remote Rule Set coexistence.

`LegacyGeoRuleDatabaseTest` separately verifies the real Room DAO excludes disabled
mixed rules and retains their data. Run with the existing test-only init script:

```text
gradlew -I app/src/test/phase2.init.gradle :app:testOssDebugUnitTest
```

## Core contracts

Run `core/legacy_geo_rule_test.go` in the same isolated libcore host harness as
the existing Phase 1/2 tests (the explicit production file list, unchanged
`compat_host_test.go`, `dns_callback_test.go`, `remote_rule_set_test.go` and the
platform-specific replacement helper). Set these absolute paths:

```text
NEKO_CONFIG_FIXTURES=AUDIT/config-fixture/configs
NEKO_REMOTE_CONFIG_FIXTURES=AUDIT/config-fixture/remotes/configs
NEKO_LEGACY_GEO_FIXTURES=AUDIT/config-fixture/geo-configs
NEKO_GEO_ASSETS=VERIFIED_GEO_DATABASE_DIRECTORY
```

The harness uses official Core commit `0b8995879f29a9b98ee027bc17b75e101445b238`
without modification. Geo tests check all route/DNS tag references, actual Core
creation, domain-only/IP-only/both/neither matches, IPv4/IPv6, second Geo values,
invert and negative tests for each extra constraint. Matching starts the actual
rule object without opening Android inbounds. It is not an Android device test.

Before the fix, the original 64-case Geo matrix had 32 single-field passes and
32 mixed-field failures, including missing GeoSite DNS definitions. Afterward,
the original 37 configurations are identical, the four Phase 2 configurations
are identical after normalizing only fixture cache directories, all 32 single
Geo configurations are identical, and mixed configurations differ only by the
restored GeoSite tags/definitions. Android device tests remain deferred.
