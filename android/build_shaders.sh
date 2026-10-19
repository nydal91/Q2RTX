#!/usr/bin/env bash
# Compiles Q2RTX's GLSL shaders to SPIR-V on the build host (glslang is built from the
# extern/glslang submodule) and packs them into app/src/main/assets/shaders.pkz.
# Shaders are platform independent, so this runs once on the host, not per ABI.
#
# For the ray query shaders it also writes spirv-opt'ed alternatives, which the engine
# falls back to when a mobile driver can't compile the default build, plus small probe
# shaders used to report which ray query feature a driver fails on.
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

glslang="$build/Bin/glslang"
out_dir="$root/baseq2/shader_vkpt"

# --- spirv-opt (SPIRV-Tools), pinned to the version glslang was tested with ---
spirv_opt="${SPIRV_OPT:-}"
if [ -z "$spirv_opt" ]; then
    tools="$build/spirv-tools"
    spirv_opt="$tools/build/tools/spirv-opt"
    if [ ! -x "$spirv_opt" ]; then
        fetch() { # repo commit dir
            mkdir -p "$3" && git -C "$3" init -q && git -C "$3" fetch -q --depth 1 "https://github.com/$1" "$2" && git -C "$3" checkout -q FETCH_HEAD
        }
        commit() { python3 -c "import json,sys;print([c['commit'] for c in json.load(open(sys.argv[1]))['commits'] if c['name']==sys.argv[2]][0])" \
                   "$root/extern/glslang/known_good.json" "$1"; }
        fetch KhronosGroup/SPIRV-Tools "$(commit spirv-tools)" "$tools"
        fetch KhronosGroup/SPIRV-Headers "$(commit spirv-tools/external/spirv-headers)" "$tools/external/spirv-headers"
        cmake -S "$tools" -B "$tools/build" "${generator[@]}" -DCMAKE_BUILD_TYPE=Release \
              -DSPIRV_SKIP_TESTS=ON -DSPIRV_WERROR=OFF -DCMAKE_POLICY_VERSION_MINIMUM=3.5
        cmake --build "$tools/build" --target spirv-opt --parallel
    fi
fi

# --- alternative builds of the ray query shaders ---
for base in primary_rays reflect_refract direct_lighting indirect_lighting; do
    in="$out_dir/$base.rgen.query.spv"
    "$spirv_opt" --target-env=vulkan1.2 -O  "$in" -o "$out_dir/$base.rgen.query.opt.spv"
    "$spirv_opt" --target-env=vulkan1.2 -Os "$in" -o "$out_dir/$base.rgen.query.size.spv"
    "$spirv_opt" --target-env=vulkan1.2 --merge-return --inline-entry-points-exhaustive \
                 --eliminate-dead-functions --eliminate-dead-code-aggressive \
                 "$in" -o "$out_dir/$base.rgen.query.legal.spv"
    # -O with spirv-opt's undefined values replaced by zeros (the Android default)
    python3 "$here/spirv_null_undef.py" "$out_dir/$base.rgen.query.opt.spv" "$out_dir/$base.rgen.query.safe.spv"
done

# --- driver probes ---
mkdir -p "$out_dir/probe"
for src in "$root"/src/refresh/vkpt/shader/probe/*.comp; do
    name="$(basename "$src" .comp)"
    "$glslang" -S comp --target-env vulkan1.2 --quiet -DVKPT_SHADER -V -DKHR_RAY_QUERY \
               "$src" -o "$out_dir/probe/$name.spv"
done

mkdir -p "$here/app/src/main/assets"
out="$here/app/src/main/assets/shaders.pkz"
rm -f "$out"
# same layout as setup/package_shaders.cmake: shader_vkpt/<name>.spv inside a zip
(cd "$root/baseq2" && cmake -E tar cf "$out" --format=zip shader_vkpt)
echo "Wrote $out ($(find "$out_dir" -name '*.spv' | wc -l) shaders)"
