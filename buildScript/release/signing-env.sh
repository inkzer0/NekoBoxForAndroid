#!/usr/bin/env bash
# Source before invoking Gradle; don't fall back to an unrelated signing key.
: "${RELEASE_KEYSTORE_BASE64:?Fork signing keystore required}"
: "${KEYSTORE_PASS:?Fork signing store password required}"
: "${ALIAS_NAME:?Fork signing key alias required}"
: "${ALIAS_PASS:?Fork signing key password required}"
export KEYSTORE_PATH="${RUNNER_TEMP:?Temporary directory required}/nekobox-release.p12"
(umask 077; printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 --decode > "$KEYSTORE_PATH")
unset RELEASE_KEYSTORE_BASE64
