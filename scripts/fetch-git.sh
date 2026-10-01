#!/usr/bin/env bash

# Downloads Termux's latest arm64 git and the libraries it links, and lays them out for the APK:
#   <jniLibs dir>/arm64-v8a/lib*.so  executables and libraries, named so the APK extracts them to nativeLibraryDir
#   <assets dir>/git/links           "<path> <packaged name>" per symlink the app makes into nativeLibraryDir:
#                                    exec/<name> for git and its helpers, lib/<soname> for a soname that is not lib*.so
#   <assets dir>/git/packages        "<package> <version>" per Termux package used, for the GPL source offer
# Termux builds against bionic with 16 KB page alignment, which is what Play requires of every bundled ELF.
# Usage: scripts/fetch-git.sh <jniLibs dir> <assets dir>
# Bash 3 compatible, since macOS ships no newer one.

set -euo pipefail

LIBS=$1/arm64-v8a
ASSETS=$2/git
REPO=https://packages.termux.dev/apt/termux-main
# git's Depends that the binaries we ship never load: less is the pager, libexpat only serves http-push
SKIP_PACKAGES=" less ncurses libexpat ca-certificates resolv-conf "
SKIP_LIBS=" libpcre2-16.so libpcre2-32.so libpcre2-posix.so libcharset.so "

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
rm -rf "$LIBS" "$ASSETS"
mkdir -p "$LIBS" "$ASSETS"

curl -fsSL "$REPO/dists/stable/main/binary-aarch64/Packages" -o "$WORK/Packages"

field() {
  awk -v pkg="$1" -v key="$2:" '$1 == "Package:" { hit = ($2 == pkg) } hit && $1 == key { $1 = ""; sub(/^ /, ""); print; exit }' "$WORK/Packages"
}

SEEN=" "
visit() {
  case "$SEEN$SKIP_PACKAGES" in *" $1 "*) return ;; esac
  SEEN="$SEEN$1 "

  # The index can list a package twice while Termux replaces it; refusing then beats pairing an old library with a new git
  COUNT=$(grep -c "^Package: $1\$" "$WORK/Packages" || true)
  if [ "$COUNT" != 1 ]; then
    echo "Termux lists package $1 $COUNT times, expected once" >&2
    exit 1
  fi

  for DEP in $(field "$1" Depends | tr ',' '\n' | sed 's/(.*)//; s/|.*//' | tr -d ' '); do
    visit "$DEP"
  done
}
visit git

ROOT=$WORK/root
mkdir -p "$ROOT"
for PKG in $SEEN; do
  curl -fsSL "$REPO/$(field "$PKG" Filename)" -o "$WORK/$PKG.deb"
  echo "$(field "$PKG" SHA256)  $WORK/$PKG.deb" | shasum -a 256 -c - > /dev/null
  echo "$PKG $(field "$PKG" Version)" >> "$ASSETS/packages"

  # dpkg-deb on Linux; on macOS bsdtar reads the .deb, which macOS's own ar cannot
  if command -v dpkg-deb > /dev/null; then
    dpkg-deb -x "$WORK/$PKG.deb" "$ROOT"
  else
    tar -xOf "$WORK/$PKG.deb" 'data.tar.*' | tar -xf - -C "$ROOT"
  fi
done
sort -o "$ASSETS/packages" "$ASSETS/packages"

PREFIX=$ROOT/data/data/com.termux/files/usr
cp "$PREFIX/bin/git" "$LIBS/libgit.so"
cp "$PREFIX/libexec/git-core/git-remote-http" "$LIBS/libgit-remote-http.so"
cat > "$ASSETS/links" <<EOF
exec/git libgit.so
exec/git-remote-http libgit-remote-http.so
exec/git-remote-https libgit-remote-http.so
EOF

# A versioned soname such as libssl.so.3 ships as libssl.so and is linked back to its soname
for LIB in "$PREFIX"/lib/lib*.so "$PREFIX"/lib/lib*.so.[0-9] "$PREFIX"/lib/lib*.so.[0-9][0-9]; do
  [ -e "$LIB" ] || continue
  [ -L "$LIB" ] && [ "${LIB%.so}" != "$LIB" ] && continue
  NAME=$(basename "$LIB")
  PACKAGED=${NAME%%.so*}.so
  case "$SKIP_LIBS" in *" $PACKAGED "*) continue ;; esac

  cp -L "$LIB" "$LIBS/$PACKAGED"
  if [ "$NAME" != "$PACKAGED" ]; then
    echo "lib/$NAME $PACKAGED" >> "$ASSETS/links"
  fi
done

echo "Fetched $(wc -l < "$ASSETS/packages" | tr -d ' ') Termux packages, $(ls "$LIBS" | wc -l | tr -d ' ') files into $LIBS"
