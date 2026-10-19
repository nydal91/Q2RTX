#!/usr/bin/env bash
# Compiles Q2RTX's GLSL shaders to SPIR-V on the build host (glslang is built from the
# extern/glslang submodule) and packs them into app/src/main/assets/shaders.pkz.
# Shaders are platform independent, so this runs once on the host, not per ABI.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
build="${Q2RTX_HOST_BUILD:-$root/build-host}"

generator=()
command -v ninja >/dev/null && generator=(-G Ninja)

cmake -S "$root" -B "$build" "${generator[@]}" -DCMAKE_BUILD_TYPE=Release \
      -DCONFIG_USE_CURL=OFF -DCONFIG_COMPILE_SHADERS=ON -DCONFIG_BUILD_GLSLANG=ON \
      -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build "$build" --target shaders --parallel

mkdir -p "$here/app/src/main/assets"
out="$here/app/src/main/assets/shaders.pkz"
rm -f "$out"
# same layout as setup/package_shaders.cmake: shader_vkpt/<name>.spv inside a zip
(cd "$root/baseq2" && cmake -E tar cf "$out" --format=zip shader_vkpt)
echo "Wrote $out ($(ls "$root/baseq2/shader_vkpt" | wc -l) shaders)"
