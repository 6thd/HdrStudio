#!/usr/bin/env bash
# JVM tests of the app's Android-independent code.
# usage: tools/jvmtests/run.sh [work_dir]   (the tested classes use no Android APIs)
#   needs ffmpeg/ffprobe with libx265 and python3.
#   Regression proofs against v2.1 compile the baseline commit ($HDR_BASELINE_COMMIT, default
#   cba40c6) against android.jar; if that commit or android.jar is missing they are reported as MISSING.
# Exit code: 0 only if every check passed. A summary line "JVM SUMMARY pass=.. fail=.. missing=.." is printed last.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); T=${1:-$(mktemp -d)}; mkdir -p "$T"; T=$(cd "$T" && pwd)
LOG="$T/jvm_results.txt"; : > "$LOG"
SRC="$ROOT/app/src/main/java/com/mojahed/hdrstudio"
step() { "$@" 2>&1 | tee -a "$LOG"; return "${PIPESTATUS[0]}"; }
rc=0
javac --release 11 -encoding UTF-8 -d "$T" \
  "$SRC/core/ColorMath.java" "$SRC/core/FrameRate.java" "$SRC/encode/HdrMetadata.java" "$SRC/stages/SceneAnalyzer.java" \
  "$SRC/encode/HevcBitstream.java" "$SRC/encode/PrecisionMetric.java" "$SRC/encode/EncodeConfig.java" "$SRC/encode/Check.java" \
  "$SRC/encode/PathTestVerdict.java" "$SRC/encode/PixelDiagnostics.java" "$SRC/encode/Hdr10FileCheck.java" "$SRC/encode/HdrSeiGuard.java" \
  "$SRC/core/MemoryBudget.java" "$SRC/core/PreviewCrop.java" "$SRC/core/SourceInfo.java" "$SRC/stages/PipelineParams.java" \
  "$SRC/ai/TilePlan.java" "$SRC/encode/AvcProfileLevel.java" || exit 1
J="$ROOT/tools/jvmtests"
javac --release 11 -encoding UTF-8 -cp "$T" -d "$T" "$J/JvmTests.java" "$J/BitstreamTests.java" "$J/HevcInspect.java" "$J/StreamUtil.java" \
  "$J/SeiGuardTests.java" "$J/NotesTests.java" "$J/MemoryTests.java" "$J/PreviewCropTests.java" "$J/TileTests.java" "$J/DiagTests.java" "$J/AvcTests.java" || exit 1
step java -Dstdout.encoding=UTF-8 -cp "$T" JvmTests || rc=1
step java -Dstdout.encoding=UTF-8 -cp "$T" TileTests || rc=1
step java -Dstdout.encoding=UTF-8 -cp "$T" DiagTests || rc=1
# the harness pipeline (engine.js) without a GPU: allocation and crop geometry must equal the app's models
if command -v node >/dev/null && node "$ROOT/tools/harness/alloc_probe.js" > "$T/alloc_probe.txt"; then
  step java -Dstdout.encoding=UTF-8 -cp "$T" MemoryTests "$T/alloc_probe.txt" || rc=1
else echo "FAIL mem_model_equals_pipeline_allocation  node or alloc_probe.js failed" | tee -a "$LOG"; rc=1; fi
if command -v node >/dev/null && node "$ROOT/tools/harness/crop_probe.js" > "$T/crop_probe.txt"; then
  step java -Dstdout.encoding=UTF-8 -cp "$T" PreviewCropTests "$T/crop_probe.txt" || rc=1
else echo "FAIL preview_crop_geometry_java_equals_harness  node or crop_probe.js failed" | tee -a "$LOG"; rc=1; fi
B="$T/streams"; mkdir -p "$B"
command -v ffmpeg >/dev/null || { echo "FAIL bitstream tests: ffmpeg missing" | tee -a "$LOG"; exit 1; }
ffmpeg -hide_banner -version | head -1 | sed 's/^/INFO /' | tee -a "$LOG"
# AVC SPS profile_idc/level_idc read from real x264 streams (Annex B)
ffmpeg -v error -y -f lavfi -i testsrc2=size=1072x1920:rate=30 -frames:v 2 -c:v libx264 -profile:v high -level:v 5.2 -pix_fmt yuv420p "$B/avc_high_52.h264" \
 && ffmpeg -v error -y -f lavfi -i testsrc2=size=640x360:rate=30 -frames:v 2 -c:v libx264 -profile:v main -level:v 3.1 -pix_fmt yuv420p "$B/avc_main_31.h264" \
 && step java -Dstdout.encoding=UTF-8 -cp "$T" AvcTests "$B/avc_high_52.h264=100:52" "$B/avc_main_31.h264=77:31" \
 || { echo "FAIL avc_tests (x264 streams or checks)" | tee -a "$LOG"; rc=1; }
X="log-level=error"
P3="G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50)"
PQ="colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
enc() { ffmpeg -v error -y "$@"; }
enc -f lavfi -i testsrc2=size=768x576:rate=10 -frames:v 6 -c:v libx265 -pix_fmt yuv420p10le -x265-params \
  "$X:$PQ:hdr10=1:repeat-headers=1:max-cll=841,61:master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,50)" -f hevc "$B/hdr10.hevc"
enc -f lavfi -i testsrc2=size=1920x1080:rate=30 -frames:v 6 -c:v libx265 -pix_fmt yuv420p10le -x265-params \
  "$X:$PQ:hdr10=1:repeat-headers=1:max-cll=1000,400:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):scaling-list=default:bframes=4" -f hevc "$B/fhd_lists.hevc"
enc -f lavfi -i testsrc2=size=320x240:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p -x265-params "$X" -f hevc "$B/sdr8.hevc"
enc -f lavfi -i testsrc2=size=320x240:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p10le -x265-params "$X:colorprim=bt709:transfer=bt709:colormatrix=bt709" -f hevc "$B/ten_nosei.hevc"
enc -f lavfi -i testsrc2=size=640x360:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p10le -x265-params "$X:$PQ" -f hevc "$B/hdr_nosei.hevc"
# note C: three IRAPs per stream (keyint 4, no scene cuts), SEI written by x265 or not at all
K="keyint=4:min-keyint=4:scenecut=0:bframes=0"
enc -f lavfi -i testsrc2=size=640x360:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p10le -x265-params "$X:$PQ:$K" -f hevc "$B/hdr_nosei_k.hevc"
enc -f lavfi -i testsrc2=size=640x360:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p10le -x265-params \
  "$X:$PQ:$K:hdr10=1:repeat-headers=1:max-cll=987,321:master-display=$P3" -f hevc "$B/hdr_both.hevc"
enc -f lavfi -i testsrc2=size=640x360:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p10le -x265-params \
  "$X:$PQ:$K:hdr10=1:repeat-headers=1:max-cll=1000,400:master-display=$P3" -f hevc "$B/hdr_conflict.hevc"
# note A: valid 10-bit content with few codes, natural-like content, and an 8-bit stage
# decoded as raw yuv420p10le (no range conversion: limited-range codes as a P010 decoder delivers them)
px() { local name=$1; shift; enc -f lavfi -i "$1" -frames:v 3 -c:v libx265 -pix_fmt yuv420p10le -x265-params "$X:$PQ" -f hevc "$B/px_$name.hevc" \
  && ffmpeg -v error -y -i "$B/px_$name.hevc" -pix_fmt yuv420p10le -f rawvideo "$B/px_$name.yuv10"; }
px black_opener "color=c=black:size=320x240:rate=10"
px flat_colours "color=c=0x2a6fb0:size=320x240:rate=10"
px colour_bars "smptebars=size=320x240:rate=10"
px fade_from_black "color=c=white:size=320x240:rate=10:duration=3,fade=t=in:st=0:d=3"
px natural "mandelbrot=size=320x240:rate=10,format=yuv420p10le,noise=alls=4:allf=t"
# the same natural content through an 8-bit Y'CbCr stage after the encoder (8-bit codes << 2 in a 10-bit container)
enc -f lavfi -i "mandelbrot=size=320x240:rate=10,noise=alls=4:allf=t" -frames:v 3 -c:v libx265 -pix_fmt yuv420p -x265-params "$X" -f hevc "$B/px_natural_8bit.hevc"
ffmpeg -v error -y -i "$B/px_natural_8bit.hevc" -pix_fmt yuv420p -f rawvideo "$B/px_natural_8bit.yuv8"
python3 -c "import sys; d=open(sys.argv[1],'rb').read(); open(sys.argv[2],'wb').write(b''.join((v<<2).to_bytes(2,'little') for v in d))" \
  "$B/px_natural_8bit.yuv8" "$B/px_natural_8bit_stage.yuv10"
python3 "$ROOT/tools/jvmtests/make_ramp_streams.py" "$B/ramp"
step java -Dstdout.encoding=UTF-8 -cp "$T" BitstreamTests "$B" 841 || rc=1
step java -Dstdout.encoding=UTF-8 -cp "$T" SeiGuardTests "$B" || rc=1

# v2.1 classes for the regression proofs
BASE=${HDR_BASELINE_COMMIT:-cba40c6}
AJ="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platforms/android-35/android.jar"
# real org.json, pinned (android.jar has only stubs for it); first on every classpath that holds android.jar
OJ=$("$ROOT/tools/jvmtests/org_json.sh" "$T") || { echo "FAIL org_json_dependency  tools/jvmtests/org_json.sh failed" | tee -a "$LOG"; rc=1; OJ=""; }
[ -n "$OJ" ] && echo "INFO org.json 20240303 commit $(cat "$T/org-json/COMMIT") jar $(sha256sum "$OJ" | cut -d' ' -f1)" | tee -a "$LOG"
LCP="$AJ"; [ -n "$OJ" ] && LCP="$OJ:$AJ"
if command -v cygpath >/dev/null; then LCP=$(cygpath -m "$AJ"); [ -n "$OJ" ] && LCP="$(cygpath -m "$OJ");$LCP"; fi  # Windows java: ';' and C:/
LEG=-
if git -C "$ROOT" cat-file -e "$BASE^{commit}" 2>/dev/null && [ -f "$AJ" ]; then
  L="$T/legacy"; rm -rf "$L"; mkdir -p "$L/src" "$L/classes"
  git -C "$ROOT" archive --format=tar "$BASE" app/src/main/java | tar -x -C "$L/src"
  LS="$L/src/app/src/main/java/com/mojahed/hdrstudio"
  rm -rf "$LS/ui" "$LS/job/ExportService.java"          # use R / BuildConfig (generated by Gradle)
  # @list is not MSYS-converted; Windows javac needs C:/... not /c/...
  /usr/bin/find "$L/src" -name '*.java' > "$L/list.unix"
  if command -v cygpath >/dev/null; then
    while IFS= read -r f; do cygpath -m "$f"; done < "$L/list.unix" > "$L/list"
  else
    cat "$L/list.unix" > "$L/list"
  fi
  if javac --release 11 -nowarn -encoding UTF-8 -cp "$LCP" -d "$L/classes" @"$L/list" >"$L/javac.log" 2>&1; then
    LEG="$L/classes"; echo "INFO v2.1 classes compiled from $BASE" | tee -a "$LOG"
  else echo "INFO v2.1 classes failed to compile (see $L/javac.log)" | tee -a "$LOG"; fi
fi
step java -Dstdout.encoding=UTF-8 -cp "$T" NotesTests "$B" "$LEG" "$LCP" "$ROOT" || rc=1

# the streams with app-inserted SEI must decode completely and show the metadata to an independent tool
probe_sei() {   # file frames maxcll maxfall
  local f=$1 n=$2
  local E F
  E=$(ffmpeg -v error -i "$f" -f null - 2>&1 | wc -l)
  F=$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$f")
  if [ "$E" -eq 0 ] && [ "$F" -eq "$n" ]; then echo "PASS ffmpeg_decodes_$(basename "$f" .hevc)  frames=$F errors=0"
  else echo "FAIL ffmpeg_decodes_$(basename "$f" .hevc)  frames=$F errors=$E"; return 1; fi
  ffprobe -v error -select_streams v:0 -show_frames -show_entries frame=key_frame,side_data_list -of json "$f" | python3 -c "
import json,sys
fr=json.load(sys.stdin)['frames']; key=[x for x in fr if x.get('key_frame')==1]; bad=[]
for i,x in enumerate(key):
    d=x.get('side_data_list',[]); m=[s for s in d if s['side_data_type']=='Mastering display metadata']; c=[s for s in d if s['side_data_type']=='Content light level metadata']
    ok=len(m)==1 and len(c)==1 and c[0]['max_content']==$3 and c[0]['max_average']==$4 and m[0]['max_luminance']=='10000000/10000' and m[0]['min_luminance']=='50/10000' \
       and m[0]['green_x']=='13250/50000' and m[0]['green_y']=='34500/50000' and m[0]['blue_x']=='7500/50000' and m[0]['red_x']=='34000/50000' and m[0]['white_point_x']=='15635/50000'
    if not ok: bad.append(i)
name='ffprobe_reads_sei_$(basename "$f" .hevc)'
print(('PASS ' if key and not bad else 'FAIL ')+name, json.dumps({'key_frames':len(key),'bad_key_frames':bad}))
sys.exit(0 if key and not bad else 1)"
}
for f in hdr_injected sei_case1_both_missing sei_case2_mdcv_only sei_case3_cll_only; do
  step probe_sei "$B/$f.hevc" 12 987 321 || rc=1
done
P=$(grep -c '^PASS ' "$LOG"); FL=$(grep -c '^FAIL ' "$LOG"); M=$(grep -c '^MISSING ' "$LOG")
[ "$FL" -eq 0 ] && [ "$M" -eq 0 ] || rc=1
echo "JVM SUMMARY pass=$P fail=$FL missing=$M exit=$rc" | tee -a "$LOG"
exit $rc
