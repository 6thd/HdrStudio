#!/usr/bin/env bash
# JVM tests of the app's Android-independent code.
# usage: tools/jvmtests/run.sh [classes_out_dir]   (the tested classes use no Android APIs)
#   needs ffmpeg with libx265 for the bitstream tests (skipped with a FAIL if missing)
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd); T=${1:-$(mktemp -d)}; mkdir -p "$T"
SRC="$ROOT/app/src/main/java/com/mojahed/hdrstudio"
javac --release 11 -encoding UTF-8 -d "$T" \
  "$SRC/core/ColorMath.java" "$SRC/encode/HdrMetadata.java" "$SRC/stages/SceneAnalyzer.java" "$SRC/encode/HevcBitstream.java" "$SRC/encode/PrecisionMetric.java"
javac --release 11 -cp "$T" -d "$T" "$ROOT/tools/jvmtests/JvmTests.java" "$ROOT/tools/jvmtests/BitstreamTests.java" "$ROOT/tools/jvmtests/HevcInspect.java"
java -Dstdout.encoding=UTF-8 -cp "$T" JvmTests
B="$T/streams"; mkdir -p "$B"
command -v ffmpeg >/dev/null || { echo "FAIL bitstream tests: ffmpeg missing"; exit 1; }
X="log-level=error"
ffmpeg -v error -y -f lavfi -i testsrc2=size=768x576:rate=10 -frames:v 6 -c:v libx265 -pix_fmt yuv420p10le -x265-params \
  "$X:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1:max-cll=841,61:master-display=G(8500,39850)B(6550,2300)R(35400,14600)WP(15635,16450)L(10000000,50)" -f hevc "$B/hdr10.hevc"
ffmpeg -v error -y -f lavfi -i testsrc2=size=1920x1080:rate=30 -frames:v 6 -c:v libx265 -pix_fmt yuv420p10le -x265-params \
  "$X:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc:hdr10=1:repeat-headers=1:max-cll=1000,400:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):scaling-list=default:bframes=4" -f hevc "$B/fhd_lists.hevc"
ffmpeg -v error -y -f lavfi -i testsrc2=size=320x240:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p -x265-params "$X" -f hevc "$B/sdr8.hevc"
ffmpeg -v error -y -f lavfi -i testsrc2=size=320x240:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p10le -x265-params "$X:colorprim=bt709:transfer=bt709:colormatrix=bt709" -f hevc "$B/ten_nosei.hevc"
ffmpeg -v error -y -f lavfi -i testsrc2=size=640x360:rate=10 -frames:v 12 -c:v libx265 -pix_fmt yuv420p10le -x265-params "$X:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc" -f hevc "$B/hdr_nosei.hevc"
python3 "$ROOT/tools/jvmtests/make_ramp_streams.py" "$B/ramp"
java -Dstdout.encoding=UTF-8 -cp "$T" BitstreamTests "$B" 841
# the stream with app-inserted SEI must decode completely and show the metadata to an independent tool
N=$(ffmpeg -v error -i "$B/hdr_injected.hevc" -f null - 2>&1 | wc -l)
F=$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=nb_read_frames -of csv=p=0 "$B/hdr_injected.hevc")
SD=$(ffprobe -v error -select_streams v:0 -read_intervals '%+#1' -show_frames -show_entries frame=side_data_list -of json "$B/hdr_injected.hevc")
echo "$SD" | python3 -c "import json,sys; d=json.load(sys.stdin)['frames'][0].get('side_data_list',[]); m=[x for x in d if x['side_data_type']=='Mastering display metadata']; c=[x for x in d if x['side_data_type']=='Content light level metadata']; ok=bool(m) and bool(c) and c[0]['max_content']==987 and c[0]['max_average']==321 and m[0]['max_luminance']=='10000000/10000' and m[0]['green_x']=='13250/50000'; print(('PASS' if ok else 'FAIL')+' ffprobe_reads_app_inserted_sei', json.dumps({'cll':c, 'mastering_max':m[0]['max_luminance'] if m else None})); sys.exit(0 if ok else 1)"
if [ "$N" -eq 0 ] && [ "$F" -eq 12 ]; then echo "PASS ffmpeg_decodes_stream_with_inserted_sei  frames=$F errors=0"; else echo "FAIL ffmpeg_decodes_stream_with_inserted_sei frames=$F errors=$N"; exit 1; fi
