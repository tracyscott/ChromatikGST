package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;

/**
 * Creates a pipeline that uses the autovideosrc element to capture video from the default camera.
 */
@LXCategory("Custom")
@LXComponentName("GSTAutoVideo")
public class GSTAutoVideo extends GSTBase {
    public GSTAutoVideo(LX lx) {
        super(lx);
    }

    @Override
    protected Pipeline initializePipeline() {
        Bin bin = Gst.parseBinFromDescription(
                "autovideosrc ! "
                        + "videoscale ! videoconvert ! "
                        + "capsfilter caps=video/x-raw,width="+widthKnob.getValuei()+",height="+heightKnob.getValuei(),
                true);
        capsFilter = bin.getElementByName("capsfilter0");
        Pipeline pipeline = new Pipeline(getPipelineName());
        AppSink videoSink = createVideoSink();
        pipeline.add(bin);
        pipeline.add(videoSink);
        bin.link(videoSink);
        return pipeline;
    }

    @Override
    protected String getPipelineName() {
        return "GSTAutoVideo";
    }
}
