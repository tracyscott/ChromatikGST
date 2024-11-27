package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import org.freedesktop.gstreamer.Bin;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.elements.AppSink;

/**
 * Creates a pipeline that uses the autovideosrc element to capture video from the default camera.
 */
@LXCategory("Custom")
@LXComponentName("GSTAutoVideo")
public class GSTAutoVideo extends GSTBase {
    public final int WIDTH = 160;
    public final int HEIGHT = 120;

    public GSTAutoVideo(LX lx) {
        super(lx);
    }

    @Override
    protected Pipeline initializePipeline() {
        Bin bin = Gst.parseBinFromDescription(
                "autovideosrc ! "
                        + "videoscale ! videoconvert ! "
                        + "capsfilter caps=video/x-raw,width="+WIDTH+",height="+HEIGHT,
                true);
        Pipeline pipeline = new Pipeline(getPipelineName());
        AppSink videoSink = createVideoSink();
        pipeline.addMany(bin, videoSink);
        Pipeline.linkMany(bin, videoSink);
        return pipeline;
    }

    @Override
    protected String getPipelineName() {
        return "GstAutoVideo";
    }
}
