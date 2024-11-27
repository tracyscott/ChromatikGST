package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import heronarts.lx.parameter.StringParameter;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.PlayBin;

import java.io.File;

/**
 * A pattern that plays a video file using GStreamer and displays it on the model.  This
 * uses the Java API for GStreamer.
 * https://gstreamer.freedesktop.org/
 * https://github.com/gstreamer-java
 * https://javadoc.io/doc/org.freedesktop.gstreamer/gst1-java-core/latest/index.html
 */
@LXCategory("Custom")
@LXComponentName("GST")
public class GST extends GSTBase {

    public final StringParameter videoFile  =
            new StringParameter("video", "chromatikgst.mp4")
                    .setDescription("Video file to play");

    public final int WIDTH = 160;
    public final int HEIGHT = 120;

    protected PlayBin playbin;

    public GST(LX lx) {
        super(lx);
        addParameter("video", this.videoFile);
    }

    protected String getVideoDir() {
        return GSTUtil.getVideoDir(lx);
    }

    @Override
    protected String getPipelineName() {
        return "GSTVideo";
    }

    @Override
    protected Pipeline initializePipeline() {
        if (playbin != null)
            return playbin;

        LX.log("Initializing GST playbin pipeline: " + getPipelineName());
        playbin = new PlayBin("playbin");
        String videoFilename = getVideoDir() + videoFile.getString();
        LX.log("Playing : " + videoFilename);
        playbin.setURI(new File(getVideoDir() + videoFile.getString()).toURI());
        // Create and configure AppSink with correct video format
        AppSink videoSink = createVideoSink();

        // Add videoconvert element to handle format conversion
        Element videoconvert = ElementFactory.make("videoconvert", "converter");
        Element videoscale = ElementFactory.make("videoscale", "scaler");

        // Create caps filter for scaling
        String capsStr = String.format("video/x-raw,width=%d,height=%d,format=BGRx",
                WIDTH, HEIGHT);
        Element capsFilter = ElementFactory.make("capsfilter", "filter");
        capsFilter.set("caps", Caps.fromString(capsStr));

        // Create bin to hold converter and sink
        Bin bin = new Bin("video-bin");
        bin.addMany(videoconvert, videoscale, capsFilter, videoSink);
        Element.linkMany(videoconvert, videoscale, capsFilter, videoSink);

        // Add ghost pad to bin
        Pad pad = videoconvert.getStaticPad("sink");
        bin.addPad(new GhostPad("sink", pad));

        // Set bin as video sink
        playbin.setVideoSink(bin);


        return playbin;
    }
}
