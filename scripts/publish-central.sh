#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "$0")/.." && pwd)"
GRADLE_PROPERTIES="$HOME/.gradle/gradle.properties"
VERSION_PROPERTIES="$ROOT_DIR/gradle.properties"
COMMON_REPOSITORY="$ROOT_DIR/stringplugin-common/build/publishing/mavenCentral"
CORE_REPOSITORY="$ROOT_DIR/stringplugin-core/build/publishing/mavenCentral"
PUBLISH_DIRECTORY="$ROOT_DIR/build/publish"

deployment_type="USER_MANAGED"
dry_run=false

fail() {
    printf 'Error: %s\n' "$1" >&2
    exit 1
}

read_property() {
    local properties_file="$1"
    local property_name="$2"
    awk -F= -v key="$property_name" '$1 == key {print substr($0, index($0, "=") + 1); exit}' "$properties_file"
}

for argument in "$@"; do
    case "$argument" in
        --automatic)
            deployment_type="AUTOMATIC"
            ;;
        --dry-run)
            dry_run=true
            ;;
        *)
            fail "unknown option: $argument"
            ;;
    esac
done

[[ -x "$ROOT_DIR/gradlew" ]] || fail "gradlew was not found"
[[ -f "$VERSION_PROPERTIES" ]] || fail "gradle.properties was not found"

version="$(read_property "$VERSION_PROPERTIES" VERSION)"
[[ -n "$version" ]] || fail "VERSION is missing from gradle.properties"
[[ "$version" != *-SNAPSHOT ]] || fail "release publishing does not support SNAPSHOT versions"

 deployment_name="${CENTRAL_DEPLOYMENT_NAME:-StringBlur-$version}"
[[ "$deployment_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "CENTRAL_DEPLOYMENT_NAME may contain only letters, numbers, dots, underscores, and hyphens"

zip_command="$(command -v zip || true)"
[[ -n "$zip_command" ]] || fail "zip is required"
unzip_command="$(command -v unzip || true)"
[[ -n "$unzip_command" ]] || fail "unzip is required"

rm -rf "$COMMON_REPOSITORY" "$CORE_REPOSITORY"
mkdir -p "$PUBLISH_DIRECTORY"

"$ROOT_DIR/gradlew" \
    :stringplugin-common:publishMavenPublicationToMavenCentralRepository \
    :stringplugin-core:publishPluginMavenPublicationToMavenCentralRepository \
    --exclude-task :stringplugin-common:prepareMavenCentralPublishing \
    --exclude-task :stringplugin-core:prepareMavenCentralPublishing

[[ -d "$COMMON_REPOSITORY" ]] || fail "common publication repository was not generated"
[[ -d "$CORE_REPOSITORY" ]] || fail "core publication repository was not generated"

bundle_path="$PUBLISH_DIRECTORY/$deployment_name.zip"
rm -f "$bundle_path"

(
    cd "$COMMON_REPOSITORY"
    "$zip_command" -q -r "$bundle_path" . -x '*.DS_Store'
)
(
    cd "$CORE_REPOSITORY"
    "$zip_command" -q -r -g "$bundle_path" . -x '*.DS_Store'
)

bundle_entries="$($unzip_command -Z1 "$bundle_path")"

for expected_path in \
    "io/github/dawnuu/common/$version/common-$version.pom" \
    "io/github/dawnuu/common/$version/common-$version.jar" \
    "io/github/dawnuu/common/$version/common-$version-sources.jar" \
    "io/github/dawnuu/common/$version/common-$version-javadoc.jar" \
    "io/github/dawnuu/stringblur/$version/stringblur-$version.pom" \
    "io/github/dawnuu/stringblur/$version/stringblur-$version.jar" \
    "io/github/dawnuu/stringblur/$version/stringblur-$version-sources.jar" \
    "io/github/dawnuu/stringblur/$version/stringblur-$version-javadoc.jar"; do
    printf '%s\n' "$bundle_entries" | grep -Fx "$expected_path" >/dev/null || fail "missing bundle entry: $expected_path"
done

if printf '%s\n' "$bundle_entries" | grep -E '(^|/)stringblur(\.gradle\.plugin|PluginMarkerMaven)/' >/dev/null; then
    fail "the bundle contains the unqualified stringblur plugin marker"
fi

printf 'Bundle ready: %s\n' "$bundle_path"
printf 'Deployment name: %s\n' "$deployment_name"

if [[ "$dry_run" == true ]]; then
    printf 'Dry run complete; nothing was uploaded.\n'
    exit 0
fi

[[ -f "$GRADLE_PROPERTIES" ]] || fail "$GRADLE_PROPERTIES was not found"
token_username="$(read_property "$GRADLE_PROPERTIES" mavenCentralUsername)"
token_password="$(read_property "$GRADLE_PROPERTIES" mavenCentralPassword)"
[[ -n "$token_username" && -n "$token_password" ]] || fail "Maven Central user token is not configured"

curl_command="$(command -v curl || true)"
[[ -n "$curl_command" ]] || fail "curl is required"

authorization="$(printf '%s:%s' "$token_username" "$token_password" | base64 | tr -d '\r\n')"
curl_config="$(mktemp "/tmp/stringblur-curl.XXXXXX")"
trap 'rm -f "$curl_config"' EXIT
chmod 600 "$curl_config"
printf 'header = "Authorization: Bearer %s"\n' "$authorization" > "$curl_config"

upload_url="https://central.sonatype.com/api/v1/publisher/upload?name=$deployment_name&publishingType=$deployment_type"
deployment_id="$("$curl_command" --silent --show-error --fail-with-body \
    --request POST \
    --config "$curl_config" \
    --form "bundle=@$bundle_path" \
    "$upload_url")" || fail "Central upload failed"

printf 'Deployment ID: %s\n' "$deployment_id"
printf 'Deployment type: %s\n' "$deployment_type"
