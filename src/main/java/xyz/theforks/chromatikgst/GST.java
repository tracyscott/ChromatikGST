package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponent;
import heronarts.lx.LXComponentName;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.StringParameter;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.PlayBin;

import java.io.File;

/**
 * Deprecated test pattern with hard-coded video filename.
 * A pattern that plays a video file using GStreamer and displays it on the model.  This
 * uses the Java API for GStreamer.
 * https://gstreamer.freedesktop.org/
 * https://github.com/gstreamer-java
 * https://javadoc.io/doc/org.freedesktop.gstreamer/gst1-java-core/latest/index.html
 * @deprecated
 */
@LXCategory("Custom")
@LXComponentName("GST")
@LXComponent.Hidden()
public class GST extends GSTBase {

    public final StringParameter videoFile  =
            new StringParameter("video", "chromatikgst.mp4")
                    .setDescription("Video file to play");

    public final DiscreteParameter widthKnob =
            new DiscreteParameter("Width", 160, 1, 1920)
                    .setDescription("Convert video to width");

    public final DiscreteParameter heightKnob =
            new DiscreteParameter("Height", 120, 1, 1080)
                    .setDescription("Convert video to height");

    protected PlayBin playbin;
    protected Element capsFilter;


    public GST(LX lx) {
        super(lx);
        addParameter("video", this.videoFile);
        addParameter("width", this.widthKnob);
        addParameter("height", this.heightKnob);
        this.widthKnob.addListener((p) -> {
            updateCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
        });
        this.heightKnob.addListener((p) -> {
            updateCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
        });
    }

    protected String getVideoDir() {
        return GSTUtil.getVideoDir(lx);
    }

    @Override
    protected String getPipelineName() {
        return "GSTVideo";
    }

    protected Element createCapsFilter(int width, int height) {
        // Create caps filter for scaling
        String capsStr = String.format("video/x-raw,width=%d,height=%d,format=BGRx",
                width, height);
        Element capsFilter = ElementFactory.make("capsfilter", "filter");
        capsFilter.set("caps", Caps.fromString(capsStr));
        return capsFilter;
    }

    protected void updateCapsFilter(int width, int height) {
        if (capsFilter == null) return;
        String capsStr = String.format("video/x-raw,width=%d,height=%d,format=BGRx",
                widthKnob.getValuei(), heightKnob.getValuei());
        capsFilter.set("caps", Caps.fromString(capsStr));
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
        capsFilter = createCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
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
