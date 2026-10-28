#!/vendor/bin/sh

# Droidspaces Container Auto-Boot script
#
# Two callers, one file. Under the native init path it is a oneshot service
# triggered on sys.boot_completed=1 and on droidspacesd restart, running the
# /vendor/bin binary with the system's own tools. The Magisk module's
# service.sh sources it instead, with BUSYBOX_BINARY set, so its counters land
# in the module's boot summary. Strict POSIX sh, no bashisms.
#
# Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
# SPDX-License-Identifier: GPL-3.0-or-later

DROIDSPACE_DIR=/data/local/Droidspaces
LOGS_DIR=${DROIDSPACE_DIR}/Logs
LOGS_FILE=${LOGS_DIR}/boot-module.log
CONTAINERS_DIR=${DROIDSPACE_DIR}/Containers
# service.sh sets the binary, the busybox prefix and log() before sourcing
# this; under init none of them is set. BUSYBOX_BINARY is the one marker: a
# PATH probe for log would find toybox's /system/bin/log instead.
: "${DROIDSPACE_BINARY:=/vendor/bin/droidspaces}"
BB=${BUSYBOX_BINARY:-}

mkdir -p "${LOGS_DIR}" 2>/dev/null
exec >> "${LOGS_FILE}" 2>&1

[ -n "${BUSYBOX_BINARY:-}" ] || log() {
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "$(date +%s)")
    echo "[${timestamp}] [autoboot] $*"
}

strip_colors() {
    $BB sed "s/$($BB printf '\033')\[[0-9;]*[mK]//g"
}

wait_for_network() {
    log "Waiting for network..."

    [ -x /system/bin/ip ] || log "WARNING: /system/bin/ip missing, waiting blind"
    local timeout=25
    local count=0
    while [ $count -lt $timeout ]; do
        if /system/bin/ip route get 8.8.8.8 2>/dev/null | $BB grep -qv "ds-br0"; then
            log "Network is ready (${count}s). Waiting 10s for stability..."
            sleep 10
            return 0
        fi
        sleep 1
        count=$((count + 1))
    done
    log "WARNING: Network not ready after ${timeout}s, proceeding anyway"
    return 1
}

# One function, so a missing binary can return early whether this file runs on
# its own or is sourced. The counters are globals for service.sh's summary.
autoboot() {
log "Droidspaces autoboot started"

if [ ! -x "${DROIDSPACE_BINARY}" ]; then
    log "ERROR: Binary not found or not executable at ${DROIDSPACE_BINARY}, aborting"
    return 1
fi

# Without this, the containers are starting too early
# Breaking container's networking :)
wait_for_network

# Scan and boot containers (priority-ordered, gateway-aware)
#
# Containers boot from the smallest run_at_boot_priority to the largest; missing
# or invalid priorities sort last (best-effort), then alphabetically. A
# net_mode=gateway client is skipped if its gateway_container did not come up,
# so we never boot a router-less client. Independent containers always boot, and
# a single failure never blocks the rest.
log "Scanning for containers with run_at_boot=1..."
success=0
failed=0
skipped=0

TAB=$($BB printf '\t')
BOOT_TABLE="${DROIDSPACE_DIR}/.autoboot_table"
SORTED_FILE="${DROIDSPACE_DIR}/.autoboot_sorted"
STARTED_OK="${DROIDSPACE_DIR}/.autoboot_started"
: > "${BOOT_TABLE}"
: > "${STARTED_OK}"

# Read a single key=value from a config file
get_cfg_val() {
    $BB grep "^$1=" "$2" 2>/dev/null | $BB head -1 | $BB sed 's/^[^=]*=//' | $BB tr -d '\r\n'
}

# Find the container.config whose name= matches $1 (echoes path, empty if none)
cfg_for_name() {
    for c in $($BB find "${CONTAINERS_DIR}" -name "container.config" 2>/dev/null); do
        n=$(get_cfg_val name "$c")
        [ -n "$n" ] || n=$($BB basename "$($BB dirname "$c")")
        if [ "$n" = "$1" ]; then
            echo "$c"
            return 0
        fi
    done
    return 1
}

# A gateway is available if it started during this run, or is already running
gateway_available() {
    if $BB grep -qxF "$1" "${STARTED_OK}" 2>/dev/null; then
        return 0
    fi
    gcfg=$(cfg_for_name "$1")
    [ -n "${gcfg}" ] || return 1
    gpid=$("${DROIDSPACE_BINARY}" --config "${gcfg}" pid 2>/dev/null)
    [ "${gpid}" != "NONE" ] && [ -n "${gpid}" ]
}

# Start one container; returns 0 if it ends up with a live PID
boot_container() {
    "${DROIDSPACE_BINARY}" --config "$1" start 2>&1 | strip_colors
    bpid=$("${DROIDSPACE_BINARY}" --config "$1" pid 2>/dev/null)
    [ "${bpid}" != "NONE" ] && [ -n "${bpid}" ]
}

# Build the boot table: PRIORITY \t NAME \t NET_MODE \t GATEWAY \t CFG
for cfg in $($BB find "${CONTAINERS_DIR}" -name "container.config" 2>/dev/null); do
    [ -f "${cfg}" ] || continue
    [ "$(get_cfg_val run_at_boot "${cfg}")" = "1" ] || continue

    name=$(get_cfg_val name "${cfg}")
    [ -n "${name}" ] || name=$($BB basename "$($BB dirname "${cfg}")")

    prio=$(get_cfg_val run_at_boot_priority "${cfg}")
    case "${prio}" in
        ''|*[!0-9]*) prio=99999999 ;;
    esac

    # '-' placeholder for empty fields: IFS=<tab> collapses adjacent tabs
    # (tab is IFS whitespace), so empty middle fields would shift the columns.
    netmode=$(get_cfg_val net_mode "${cfg}")
    [ -n "${netmode}" ] || netmode="-"
    gw=$(get_cfg_val gateway_container "${cfg}")
    [ -n "${gw}" ] || gw="-"

    $BB printf '%s\t%s\t%s\t%s\t%s\n' "${prio}" "${name}" "${netmode}" "${gw}" "${cfg}" >> "${BOOT_TABLE}"
done

# Smallest priority first, then name. Gaps/holes and large sentinels sort cleanly.
$BB sort -t"${TAB}" -k1,1n -k2,2 "${BOOT_TABLE}" > "${SORTED_FILE}"

# Reading from a file (not a pipe) keeps the loop in this shell so the counters
# survive each iteration.
while IFS="${TAB}" read -r prio name netmode gw cfg; do
    [ -n "${cfg}" ] || continue
    [ "${gw}" = "-" ] && gw=""

    if [ "${netmode}" = "gateway" ] && [ -n "${gw}" ]; then
        if ! gateway_available "${gw}"; then
            log "SKIP: ${name} (gateway '${gw}' not running)"
            skipped=$((skipped + 1))
            continue
        fi
    fi

    log "Starting container: ${name}"
    if boot_container "${cfg}"; then
        PID=$("${DROIDSPACE_BINARY}" --config "${cfg}" pid 2>/dev/null)
        log "SUCCESS: ${name} (PID: ${PID})"
        $BB printf '%s\n' "${name}" >> "${STARTED_OK}"
        success=$((success + 1))
    else
        log "FAILED: ${name}"
        failed=$((failed + 1))
    fi
done < "${SORTED_FILE}"

$BB rm -f "${BOOT_TABLE}" "${SORTED_FILE}" "${STARTED_OK}" 2>/dev/null

log "Autoboot complete: ${success} started | ${failed} failed | ${skipped} skipped"
}

autoboot
