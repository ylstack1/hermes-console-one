#!/bin/bash
# Fetch pre-compiled PRoot binaries from Termux packages for Android.
# Extracts proot, libtalloc, and libandroid-shmem from Termux .deb packages.
# Places them in app/src/main/jniLibs/arm64-v8a/lib*.so so Android auto-extracts
# them to nativeLibraryDir with execute permission (bypasses W^X).
#
# At runtime, LocalEngineBootstrap copies libtalloc.so -> libtalloc.so.2
# (matching the SONAME proot expects) into the app's libDir.
#
# Usage:
#   ./android/scripts/fetch-proot-binaries.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

# Termux mirrors (Tsinghua first for speed in China, official CDN fallback)
TERMUX_MIRRORS=(
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
    "https://packages.termux.dev/apt/termux-main"
)

# Only arm64-v8a (matches abiFilters in build.gradle.kts)
DEB_ARCH="aarch64"
JNI_ABI="arm64-v8a"

mkdir -p "$JNILIBS_DIR"

# Fetch a Termux package and extract binaries
fetch_termux_pkg() {
    local pkg_name="$1"
    local out_name="$2"
    echo "    Fetching $pkg_name for $DEB_ARCH..."

    local pkg_url=""
    for mirror in "${TERMUX_MIRRORS[@]}"; do
        if pkg_url=$(curl -fsSL "${mirror}/dists/stable/main/binary-${DEB_ARCH}/Packages" \
                | grep -A 20 "^Package: ${pkg_name}$" \
                | grep "^Filename:" \
                | head -1 \
                | awk '{print $2}' 2>/dev/null) && [ -n "$pkg_url" ]; then
            echo "    Using mirror: $mirror"
            break
        fi
    done

    if [ -z "$pkg_url" ]; then
        echo "    ERROR: $pkg_name not found in any Termux mirror for $DEB_ARCH"
        return 1
    fi

    local deb_file="$TMP_DIR/${pkg_name}-${DEB_ARCH}.deb"
    local extract_dir="$TMP_DIR/${pkg_name}-extract"
    mkdir -p "$extract_dir"

    for mirror in "${TERMUX_MIRRORS[@]}"; do
        if curl -fsSL "${mirror}/${pkg_url}" -o "$deb_file" 2>/dev/null; then
            # Extract data.tar from .deb (ar archive)
            if command -v ar >/dev/null 2>&1; then
                ar x "$deb_file" "$TMP_DIR/data.tar" 2>/dev/null || true
            fi
            if [ ! -f "$TMP_DIR/data.tar" ]; then
                # Fallback: try extracting with dpkg-deb if available
                if command -v dpkg-deb >/dev/null 2>&1; then
                    dpkg-deb -x "$deb_file" "$extract_dir" 2>/dev/null || true
                fi
            else
                # data.tar might be .gz, .xz, or .zst
                if tar -tf "$TMP_DIR/data.tar" >/dev/null 2>&1; then
                    tar -xf "$TMP_DIR/data.tar" -C "$extract_dir" 2>/dev/null || true
                elif command -v gzip >/dev/null 2>&1 && gzip -t "$TMP_DIR/data.tar" 2>/dev/null; then
                    tar -xzf "$TMP_DIR/data.tar" -C "$extract_dir" 2>/dev/null || true
                elif command -v xz >/dev/null 2>&1 && xz -t "$TMP_DIR/data.tar" 2>/dev/null; then
                    tar -xJf "$TMP_DIR/data.tar" -C "$extract_dir" 2>/dev/null || true
                fi
            fi
            rm -f "$TMP_DIR/data.tar"
            break
        fi
    done

    if [ ! -d "$extract_dir" ] || [ -z "$(ls -A "$extract_dir" 2>/dev/null)" ]; then
        echo "    ERROR: Failed to extract $pkg_name"
        return 1
    fi

    # Find the .so file (resolve symlinks)
    local lib_file
    lib_file=$(find "$extract_dir" -name "${out_name}.so*" -type f 2>/dev/null | head -1)
    if [ -z "$lib_file" ]; then
        # Try symlink resolution
        lib_file=$(find "$extract_dir" -name "${out_name}.so*" -type l 2>/dev/null | head -1)
        if [ -n "$lib_file" ]; then
            lib_file=$(readlink -f "$lib_file" 2>/dev/null || echo "$lib_file")
        fi
    fi

    # Fallback: some packages (e.g. proot) ship an executable binary,
    # not a shared library. Look for any executable in bin/ or usr/bin/.
    if [ -z "$lib_file" ] || [ ! -f "$lib_file" ]; then
        lib_file=$(find "$extract_dir" \( -path "*/bin/*" -o -path "*/usr/bin/*" \) -type f -executable 2>/dev/null | head -1)
    fi

    if [ -z "$lib_file" ] || [ ! -f "$lib_file" ]; then
        echo "    ERROR: ${out_name}.so not found in $pkg_name package"
        return 1
    fi

    cp -L "$lib_file" "$JNILIBS_DIR/${out_name}.so"
    chmod 755 "$JNILIBS_DIR/${out_name}.so"
    echo "    OK: $JNILIBS_DIR/${out_name}.so"
}

echo "=== Fetching PRoot + dependencies from Termux packages ==="
echo ""

SUCCESS=0
FAILED=0

# libproot.so — the proot binary itself (Termux packages it as bin/proot;
    # the fallback in fetch_termux_pkg finds executables too).
    if fetch_termux_pkg "proot" "libproot"; then
    SUCCESS=$((SUCCESS + 1))
else
    FAILED=$((FAILED + 1))
fi

echo ""

# libtalloc.so — proot links libtalloc.so.2 at runtime; we copy as .so.2 in Kotlin
if fetch_termux_pkg "libtalloc" "libtalloc"; then
    SUCCESS=$((SUCCESS + 1))
else
    FAILED=$((FAILED + 1))
fi

echo ""

# libandroid-shmem.so — proot dlopen's it at startup
if fetch_termux_pkg "libandroid-shmem" "libandroid-shmem"; then
    SUCCESS=$((SUCCESS + 1))
else
    FAILED=$((FAILED + 1))
fi

echo ""
echo "=== Summary ==="
echo "Success: $SUCCESS / 3"
if [ "$FAILED" -gt 0 ]; then
    echo "Failed: $FAILED"
    exit 1
fi
echo ""
echo "Files:"
ls -la "$JNILIBS_DIR"/lib*.so 2>/dev/null || echo "  (none)"
