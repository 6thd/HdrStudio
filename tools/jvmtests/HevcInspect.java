import com.mojahed.hdrstudio.encode.HevcBitstream;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

/** CLI: prints what the app's HEVC parser reads from an Annex-B file (used by check.py). */
public class HevcInspect {
    public static void main(String[] a) throws Exception {
        HevcBitstream.Info i = HevcBitstream.inspect(Collections.singletonList(Files.readAllBytes(Paths.get(a[0]))));
        System.out.println("{\"sps\":" + i.spsFound + ",\"bitDepth\":" + i.bitDepthLuma + ",\"primaries\":" + i.colourPrimaries
                + ",\"transfer\":" + i.transfer + ",\"matrix\":" + i.matrix + ",\"fullRange\":" + i.fullRange
                + ",\"mdcv\":" + i.mdcvFound + ",\"mdcvMaxLum\":" + i.mdcvMaxLum + ",\"cll\":" + i.cllFound
                + ",\"maxCll\":" + i.maxCll + ",\"maxFall\":" + i.maxFall + ",\"hdr10\":" + i.isHdr10Signal() + "}");
    }
}
