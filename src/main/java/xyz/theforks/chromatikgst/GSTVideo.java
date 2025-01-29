package xyz.theforks.chromatikgst;

import heronarts.glx.GLX;
import heronarts.glx.ui.UI2dContainer;
import heronarts.glx.ui.component.UIButton;
import heronarts.glx.ui.component.UIKnob;
import heronarts.glx.ui.component.UILabel;
import heronarts.glx.ui.vg.VGraphics;
import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import heronarts.lx.color.LXColor;
import heronarts.lx.command.LXCommand;
import heronarts.lx.parameter.*;
import heronarts.lx.studio.LXStudio;
import heronarts.lx.studio.ui.device.UIDevice;
import heronarts.lx.studio.ui.device.UIDeviceControls;
import heronarts.lx.utils.LXUtils;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.PlayBin;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;

import java.io.File;
import java.util.EnumSet;

/**
 * A pattern that plays a video file using GStreamer and displays it on the model.  This
 * uses the Java API for GStreamer.
 * https://gstreamer.freedesktop.org/
 * https://github.com/gstreamer-java
 * https://javadoc.io/doc/org.freedesktop.gstreamer/gst1-java-core/latest/index.html
 */
@LXCategory("Custom")
@LXComponentName("GSTVideo")
public class GSTVideo extends GSTBase implements UIDeviceControls<GSTVideo> {

    public final StringParameter videoFile  =
            new StringParameter("video", "chromatikgst.mp4")
                    .setDescription("Video file to play");

    public final DiscreteParameter widthKnob =
            new DiscreteParameter("Width", 160, 1, 1920)
                    .setDescription("Convert video to width");

    public final DiscreteParameter heightKnob =
            new DiscreteParameter("Height", 120, 1, 1080)
                    .setDescription("Convert video to height");

    public final BooleanParameter sync =
            new BooleanParameter("Sync", true)
                    .setDescription("Reset video each time pattern becomes active");

    protected PlayBin playbin;
    protected Element capsFilter;
    private UIButton openButton;


    public GSTVideo(LX lx) {
        super(lx);
        addParameter("video", this.videoFile);
        addParameter("width", this.widthKnob);
        addParameter("height", this.heightKnob);
        addParameter("sync", this.sync);
    }

    @Override
    public void onParameterChanged(LXParameter p) {
        super.onParameterChanged(p);
        if (p == widthKnob || p == heightKnob) {
            updateCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
        }
        if (p == videoFile) {
            if (playbin != null) {
               disposePipeline();
               gstInitialized = false;
               playbin = null;
               gstThread.interrupt();
               initGSTWithThread(lx);
            }
        }
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

        //LX.log("Initializing GST playbin pipeline: " + getPipelineName());
        playbin = new PlayBin("playbin");
        String videoFilename = getVideoDir() + videoFile.getString();
        //LX.log("Playing : " + videoFilename);
        playbin.setURI(new File(getVideoDir() + videoFile.getString()).toURI());
        // TODO(tracy): GStreamer durations are in nanoseconds.  This is half a second. Or 15 frames at 30fps and
        // 30 frames at 60fps.  This should be tuned for the situation.  I haven't yet tested allowing for this to
        // be set as a parameter and updating it dynamically.
        playbin.set("buffer-duration", 500000000);
        // Create and configure AppSink with correct video format
        AppSink videoSink = createVideoSink();

        // Add videoconvert element to handle format conversion
        Element videoconvert = ElementFactory.make("videoconvert", "converter");
        Element videoscale = ElementFactory.make("videoscale", "scaler");

        // Create caps filter for scaling
        capsFilter = createCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
        // Create bin to hold converter and sink
        Bin scalerBin = new Bin("video-bin");
        scalerBin.addMany(videoconvert, videoscale, capsFilter, videoSink);
        Element.linkMany(videoconvert, videoscale, capsFilter, videoSink);

        // Add ghost pad to bin
        Pad pad = videoconvert.getStaticPad("sink");
        scalerBin.addPad(new GhostPad("sink", pad));

        // Set bin as video sink
        playbin.setVideoSink(scalerBin);

        return playbin;
    }

    @Override
    public void buildDeviceControls(LXStudio.UI ui, UIDevice uiDevice, GSTVideo pattern) {
        uiDevice.setContentWidth(150);
        uiDevice.setLayout(UI2dContainer.Layout.VERTICAL);
        uiDevice.setPadding(5, 0);
        uiDevice.setChildSpacing(5);
        final UI2dContainer fileContainer = new UI2dContainer(0, 0, 150, 18);
        fileContainer.addToContainer(uiDevice);
        final UILabel fileLabel = (UILabel)
                new UILabel(0, 0, 120, 18)
                        .setLabel(pattern.videoFile.getString())
                        .setBackgroundColor(LXColor.BLACK)
                        .setBorderRounding(4)
                        .setTextAlignment(VGraphics.Align.CENTER, VGraphics.Align.MIDDLE)
                        .setTextOffset(0, -1)
                        .addToContainer(fileContainer);

        pattern.videoFile.addListener(p -> {
            fileLabel.setLabel(pattern.videoFile.getString());
        });

        this.openButton = (UIButton) new UIButton(122, 0, 18, 18) {
            @Override
            public void onToggle(boolean on) {
                if (on) {
                    ((GLX)lx).showOpenFileDialog(
                            "Open Video File",
                            "Video Files",
                            new String[] { "mp4" },
                            lx.getMediaPath() + File.separator + "Video" + File.separator,
                            (path) -> { onOpen(new File(path)); }
                    );
                }
            }
        }
                .setIcon(ui.theme.iconOpen)
                .setMomentary(true)
                .setDescription("Open Video")
                .addToContainer(fileContainer);

        final UI2dContainer knobsContainer = (UI2dContainer) new UI2dContainer(0, 25, 150, 30)
                .setLayout(UI2dContainer.Layout.HORIZONTAL)
                .addToContainer(uiDevice);

        knobsContainer.setPadding(5);
        knobsContainer.setChildSpacing(5);

        final UIKnob widthK = (UIKnob) new UIKnob(0, 0, 35, 30)
                .setParameter(pattern.widthKnob)
                .addToContainer(knobsContainer);
        final UIKnob heightK = (UIKnob) new UIKnob(40, 0, 35, 30)
                .setParameter(pattern.heightKnob)
                .addToContainer(knobsContainer);
        final UIButton syncT = (UIButton) new UIButton(105, 0, 35, 30)
                .setParameter(pattern.sync)
                .addToContainer(knobsContainer);
        syncT.setLabel("Sync");
    }

    public void onOpen(final File openFile) {
        this.openButton.setActive(false);
        if (openFile != null) {
            LX lx = getLX();
            String baseFilename = openFile.getName().substring(0, openFile.getName().indexOf('.'));
            lx.engine.addTask(() -> {
                lx.command.perform(new LXCommand.Parameter.SetString(
                        videoFile,
                        baseFilename+".mp4"
                ));
            });
        }
    }

    @Override
    protected void onInactive() {
        resetPlayPosition();
        super.onInactive();
    }

    /**
     * Each time the pattern becomes inactive, reset the play position to the beginning of the video whenever
     * sync is enabled.
     */
    protected void resetPlayPosition() {
        if (sync.isOn() && pipeline != null) {
            pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.SEGMENT), SeekType.SET, 0, SeekType.NONE, 0);
        }
    }
}
