#!/usr/bin/env bash
# Reproduces the desktop verification from scratch. Fails on ANY test error, failed check or
# missing check. Results go to a NEW directory per run (never mixed with older results).
# usage: tools/harness/run_ci.sh <work_dir>
set -euo pipefail
W=$1; H=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$H/../.." && pwd)
RUN="$W/run_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$W/media" "$RUN/data" "$RUN/out"
cd "$W/media"
for f in samples/data/vtest.avi samples/data/baboon.jpg samples/data/messi5.jpg samples/data/fruits.jpg samples/data/building.jpg samples/data/lena.jpg samples/data/Megamind.avi; do
  [ -s "$(basename $f)" ] || curl -fsSL -o "$(basename $f)" "https://raw.githubusercontent.com/opencv/opencv/4.x/$f"; done
for f in inputs/0014.jpg inputs/OST_009.png inputs/ADE_val_00000114.jpg; do
  [ -s "$(basename $f)" ] || curl -fsSL -o "$(basename $f)" "https://raw.githubusercontent.com/xinntao/Real-ESRGAN/master/$f"; done
cd "$RUN"
# numerical reference for the AI network (NumPy implementation of srvgg_arch.py, validated against ncnn below)
python3 - "$ROOT" "$W/media" <<'PY'
import sys, subprocess, numpy as np
sys.path.insert(0, sys.argv[1] + '/tools'); import srvgg_reference as R
raw = subprocess.run(['ffmpeg','-v','error','-i',sys.argv[2] + '/baboon.jpg','-vf','crop=40:32:200:200','-f','rawvideo','-pix_fmt','rgb24','-'],capture_output=True,check=True).stdout
img = np.frombuffer(raw,np.uint8).reshape(32,40,3).transpose(2,0,1).astype(np.float32)/255.
np.save('in_baboon_crop.npy', img)
np.save('ref_baboon_crop.npy', R.forward(R.load_blob(sys.argv[1] + '/app/src/main/assets/models/srvgg_general_x4v3.bin'), img).astype(np.float32))
PY
python3 "$H/prepare_data.py" "$W/media" "$RUN/data" > /dev/null
python3 "$H/check.py" "$RUN/data" "$RUN/out" prep_yuv
TESTS="compile color sr_reference sr_tiling motion temporal resize_temporal flash scenecut banding video uhd_sdr uhd_pq v0_compare stills stills_same"
node "$H/run.js" "$RUN/data" "$RUN/out" $TESTS          # exits non-zero if any test throws
"$ROOT/tools/jvmtests/run.sh" "$RUN/jvm" | tee "$RUN/out/jvm_tests.log"
export HEVC_INSPECT_CP="$RUN/jvm" HDR_MEDIA="$W/media"
python3 "$H/check.py" "$RUN/data" "$RUN/out" jvm color sr motion temporal resize_temporal flash scenecut banding video uhd stills stills_same visual verify
echo "results: $RUN/out/report.json"
