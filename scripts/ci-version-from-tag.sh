#!/usr/bin/env bash
# Derive appVersionName / appVersionCode from a release tag (e.g. v0.8.7-alpha.1)
# and export them for Gradle via ORG_GRADLE_PROJECT_* (into GITHUB_ENV when present).
set -euo pipefail

TAG="${1:-${RELEASE_TAG:-}}"
if [[ -z "$TAG" ]]; then
  echo "usage: $0 <tag>   (or set RELEASE_TAG)" >&2
  exit 1
fi

VERSION="${TAG#v}"
if ! echo "$VERSION" | grep -qP '^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$'; then
  echo "::error::Invalid version from tag '${TAG}': ${VERSION}" >&2
  exit 1
fi

ALPHA_NUM="$(echo "$VERSION" | grep -oP 'alpha\.\K\d+' || echo "0")"
DATE_PART="$(date -u +%y%m%d)"
VERSION_CODE="${DATE_PART}$(printf '%02d' "$ALPHA_NUM")"

echo "Release version: name=${VERSION} code=${VERSION_CODE} (from tag ${TAG})"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  {
    echo "ORG_GRADLE_PROJECT_appVersionName=${VERSION}"
    echo "ORG_GRADLE_PROJECT_appVersionCode=${VERSION_CODE}"
    echo "APP_VERSION=${VERSION}"
  } >> "$GITHUB_ENV"
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "name=${VERSION}"
    echo "code=${VERSION_CODE}"
  } >> "$GITHUB_OUTPUT"
fi
