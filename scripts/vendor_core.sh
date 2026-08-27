#!/usr/bin/env bash
# ============================================================================
# vendor_core.sh - snapshot the upstream RPCS4 core into the Android tree
#
# Two modes:
#   ./scripts/vendor_core.sh                # copies from rpcs4/ submodule
#   ./scripts/vendor_core.sh --download     # fetches a GitHub tarball instead
#
# The vendored copy lands in:
#   app/cpp/core            (emulation engine)
#   app/cpp/Dependencies    (third-party submodule content)
#
# Both paths are gitignored: the git submodule remains the source of truth,
# the snapshot simply makes builds possible without --recursive clones and
# produces reproducible CI inputs.
# ============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_TARGET="${REPO_ROOT}/app/cpp/core"
DEPS_TARGET="${REPO_ROOT}/app/cpp/Dependencies"

SUBMODULE_DIR="${REPO_ROOT}/rpcs4"
UPSTREAM_RAW="https://codeload.github.com/rpcs4/rpcs4/tar.gz/refs/heads/master"

log() { printf '\033[1;34m[vendor]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[vendor] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

copy_from_submodule() {
    [[ -f "${SUBMODULE_DIR}/core/CMakeLists.txt" ]] || die \
        "rpcs4/ submodule is empty. Run 'git submodule update --init --recursive' first, or use --download."
    log "Copying from ${SUBMODULE_DIR}"
    rm -rf "${CORE_TARGET}" "${DEPS_TARGET}"
    cp -R "${SUBMODULE_DIR}/core"         "${CORE_TARGET}"
    cp -R "${SUBMODULE_DIR}/Dependencies" "${DEPS_TARGET}"
}

download_tarball() {
    command -v curl >/dev/null || die "curl is required for --download"
    local tmp; tmp="$(mktemp -d)"
    trap 'rm -rf "${tmp}"' EXIT

    log "Downloading upstream master tarball..."
    curl -fsSL "${UPSTREAM_RAW}" -o "${tmp}/rpcs4.tar.gz"
    mkdir -p "${tmp}/extract"
    tar -xzf "${tmp}/rpcs4.tar.gz" -C "${tmp}/extract"

    local src_dir
    src_dir="$(find "${tmp}/extract" -maxdepth 1 -type d -name 'rpcs4-*' | head -n1)"
    [[ -d "${src_dir}/core" ]] || die "Downloaded tarball has no core/ directory"

    log "Extracting from ${src_dir}"
    rm -rf "${CORE_TARGET}" "${DEPS_TARGET}"
    cp -R "${src_dir}/core"         "${CORE_TARGET}"
    cp -R "${src_dir}/Dependencies" "${DEPS_TARGET}"

    # Tarball exports ship with EMPTY dependency submodules. We still keep the
    # dirs so CMake's error message stays actionable instead of silently
    # proceeding to a linker failure later.
    find "${DEPS_TARGET}" -maxdepth 1 -type d -empty -exec touch "{}/.SUBMODULE_PLACEHOLDER" \; || true
}

[[ -d "${REPO_ROOT}/app/cpp" ]] || die "Run this script from inside the rpcs4-android checkout."

case "${1:-}" in
    --download)
        download_tarball
        ;;
    "")
        copy_from_submodule
        ;;
    *)
        die "Usage: $0 [--download]"
        ;;
esac

log "Vendored into app/cpp/core + app/cpp/Dependencies"
log "Note: empty Dependencies/* subdirectories mean you must clone recursively;"
log "      they are required for xxHash/miniz/zydis/libco/glslang/VMA symbols."
log "Done."
