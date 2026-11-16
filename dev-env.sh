#!/usr/bin/env bash

# Shared local-development helpers. .env.local is parsed as data, never sourced
# as shell code. Existing process environment values take precedence.
load_local_env() {
    local backend_dir="$1"
    local env_file="$backend_dir/.env.local"
    local line name value line_number=0

    [[ -f "$env_file" ]] || return 0

    while IFS= read -r line || [[ -n "$line" ]]; do
        ((line_number += 1))
        line="${line%$'\r'}"
        [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]] && continue

        if [[ ! "$line" =~ ^[[:space:]]*([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
            printf 'Invalid .env.local entry at line %s; expected NAME=value.\n' "$line_number" >&2
            return 1
        fi

        name="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        case "$name" in
            BACKEND_PORT|DB_URL|DB_PASSWORD|JWT_SECRET)
                ;;
            *)
                printf 'Unsupported variable name in .env.local at line %s.\n' "$line_number" >&2
                return 1
                ;;
        esac

        if [[ ${#value} -ge 2 ]]; then
            if [[ "${value:0:1}" == '"' && "${value: -1}" == '"' ]] ||
                [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
                value="${value:1:${#value}-2}"
            fi
        fi

        if [[ ! -v $name ]]; then
            export "$name=$value"
        fi
    done < "$env_file"
}

validate_backend_port() {
    if [[ ! "${BACKEND_PORT:-}" =~ ^[0-9]{1,5}$ ]] ||
        ((10#$BACKEND_PORT < 1 || 10#$BACKEND_PORT > 65535)); then
        printf 'BACKEND_PORT must be an integer between 1 and 65535.\n' >&2
        return 1
    fi
}

validate_local_database_config() {
    if [[ -z "${DB_URL:-}" ]]; then
        printf 'DB_URL is required. Set it in 2Dto3D_BE/.env.local for a local test database.\n' >&2
        return 1
    fi

    if [[ ! "$DB_URL" =~ ^jdbc:postgresql:// ]]; then
        printf 'DB_URL must be a JDBC PostgreSQL URL (local or remote, e.g. Railway).\n' >&2
        return 1
    fi

    case "${DB_URL,,}" in
        *password=*|*user=*)
            printf 'Keep database credentials out of DB_URL; use DB_PASSWORD and the configured database user.\n' >&2
            return 1
            ;;
    esac

    if [[ ! -v DB_PASSWORD ]]; then
        printf 'DB_PASSWORD must be set (it may be empty for a local trust-authenticated database).\n' >&2
        return 1
    fi

    if [[ -z "${JWT_SECRET:-}" || ${#JWT_SECRET} -lt 32 ]]; then
        printf 'JWT_SECRET must contain at least 32 characters. Use a local-only random value; it is never printed.\n' >&2
        return 1
    fi
}

maven_wrapper_hash() {
    local value="$1" hash=0 character code
    while [[ -n "$value" ]]; do
        character="${value:0:1}"
        printf -v code '%d' "'$character"
        hash=$(((hash * 31 + code) % 4294967296))
        value="${value:1}"
    done
    printf '%x\n' "$hash"
}

maven_wrapper_home() {
    local backend_dir="$1"
    local wrapper_properties="$backend_dir/.mvn/wrapper/maven-wrapper.properties"
    local distribution_url distribution_archive distribution_dir maven_user_home

    [[ -f "$wrapper_properties" ]] || return 1
    distribution_url="$(sed -n 's/^distributionUrl=//p' "$wrapper_properties" | tr -d '\r[:space:]')"
    distribution_archive="${distribution_url##*/}"
    distribution_dir="${distribution_archive%.zip}"
    distribution_dir="${distribution_dir%-bin}"
    [[ -n "$distribution_url" && -n "$distribution_dir" ]] || return 1

    maven_user_home="${MAVEN_USER_HOME:-${HOME:-}}"
    [[ -n "$maven_user_home" ]] || return 1
    if [[ -z "${MAVEN_USER_HOME:-}" ]]; then
        maven_user_home="$maven_user_home/.m2"
    fi

    # Keep this path in sync with MAVEN_HOME in mvnw: hash directory is the
    # Maven home itself; the distribution name is not an extra path component.
    printf '%s/wrapper/dists/%s/%s\n' \
        "$maven_user_home" "$distribution_dir" "$(maven_wrapper_hash "$distribution_url")"
}

maven_wrapper_is_cached() {
    local backend_dir="$1"
    local wrapper_home
    wrapper_home="$(maven_wrapper_home "$backend_dir")" || return 1
    [[ -x "$wrapper_home/bin/mvn" ]]
}
