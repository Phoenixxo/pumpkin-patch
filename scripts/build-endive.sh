#!/usr/bin/env bash
# Builds Endive and Endive CM from the pinned submodules into ~/.m2-pumpkin-patch, which
# build.gradle.kts reads. Your own ~/.m2 is not touched.
#
# third_party/endive-cm is github.com/Phoenixxo/endive-cm on fix/outer-alias-structural-type-match:
# upstream main plus one fix. Its linker compared outer-aliased types by raw equality, so a
# component that `use`s a record whose fields name other types (the radar's entity-snapshot)
# failed to link. The fix compares them structurally.
#
#   ENDIVE_DIR     checkout of github.com/Phoenixxo/endive          (default third_party/endive)
#   ENDIVE_CM_DIR  checkout of github.com/Phoenixxo/endive-cm      (default third_party/endive-cm)
#   ENDIVE_JAVA_HOME  JDK 25 home (default /Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
repo="$HOME/.m2-pumpkin-patch"
endive="${ENDIVE_DIR:-$root/third_party/endive}"
endive_cm="${ENDIVE_CM_DIR:-$root/third_party/endive-cm}"
# Spotless (run by both builds) cannot parse Java 26 class files, so this pins JDK 25.
export JAVA_HOME="${ENDIVE_JAVA_HOME:-/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home}"
mvn_args=(-q -B "-Dmaven.repo.local=$repo" -DskipTests -Dspotless.check.skip -Dcheckstyle.skip)

pinned() { # dir commit: refuse to build anything but the tested commit
  if [ ! -e "$1/.git" ]; then
    echo "$1 is empty; run: git submodule update --init" >&2; exit 1
  fi
  if [ "$(git -C "$1" rev-parse HEAD)" != "$(git -C "$1" rev-parse "$2")" ]; then
    echo "$1 is not at $2; run: git submodule update --init" >&2; exit 1
  fi
}

pinned "$endive" c596b80d
(cd "$endive" && mvn "${mvn_args[@]}" install)

pinned "$endive_cm" 5106f11
(cd "$endive_cm" && mvn "${mvn_args[@]}" install)
echo "Installed Endive and Endive CM 999-SNAPSHOT into $repo"
