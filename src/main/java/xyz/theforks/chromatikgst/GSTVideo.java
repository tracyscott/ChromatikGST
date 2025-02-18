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
@LXComponentName("GSTVideo")
public class GSTVideo extends GSTBase implements UIDeviceControls<GSTVideo> {

    public final StringParameter videoFile  =
            new StringParameter("video", "chromatikgst.mp4")
                    .setDescription("Video file to play");

    public final BooleanParameter sync =
            new BooleanParameter("Sync", true)
                    .setDescription("Reset video each time pattern becomes active");

    protected PlayBin playbin;
    protected Element capsFilter;
    private UIButton openButton;


    public GSTVideo(LX lx) {
        super(lx);
        addParameter("video", this.videoFile);
        addParameter("sync", this.sync);
    }

    @Override
    public void onParameterChanged(LXParameter p) {
        super.onParameterChanged(p);
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

    @Override
    protected Pipeline initializePipeline() {
        if (playbin != null)
            return playbin;

        GSTUtil.exportDefaultVideos(lx);
        if (GSTUtil.VERBOSE) LX.log("Initializing GST playbin pipeline: " + getPipelineName());
        playbin = new PlayBin("playbin");
        String videoFilename = getVideoDir() + videoFile.getString();
        if (GSTUtil.VERBOSE) LX.log("Playing : " + videoFilename);
        playbin.setURI(new File(getVideoDir() + videoFile.getString()).toURI());
        // TODO(tracy): Decide what to do with audio files.  Currently we just set the audio sink to
        // a fake sink so we don't generate audio.  This probably gets complicated to support in the
        // way that people want to use it across different platforms.  Also, if the audio device is
        // exclusive then this might cause problems for Chromatik to monitor?  Currently don't have
        // the time to do all the cross platform testing and current project doesn't require audio.

        // Another option for silence.
        // playbin.set("audio-sink", null);
        // Create a fake sink
        Element fakeSink = ElementFactory.make("fakesink", "audio-fake-sink");
        playbin.set("audio-sink", fakeSink);

        // Another strategy for not playing audio.
        //int flags = (1 << 1 | 1 << 2);  // Combines VIDEO and NATIVE_VIDEO flags
        //playbin.set("flags", flags);
        //playbin.set("flags", PlayFlags.VIDEO | PlayFlags.NATIVE_VIDEO);

        // Add videoconvert element to handle format conversion
        Element videoconvert = ElementFactory.make("videoconvert", "converter");
        if (videoconvert == null) {
            LX.error("Failed to create videoconvert element for pipeline: " + getPipelineName());
            return null;
        }

        Element videoscale = ElementFactory.make("videoscale", "scaler");
        if (videoscale == null) {
            LX.error("Failed to create videoscale element for pipeline: " + getPipelineName());
            return null;
        }

        capsFilter = createCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
        if (capsFilter == null) {
            LX.error("Failed to create capsFilter element for pipeline: " + getPipelineName());
            return null;
        }

        AppSink videoSink = createVideoSink();
        if (videoSink == null) {
            LX.error("Failed to create videoSink element for pipeline: " + getPipelineName());
            return null;
        }

        Bin scalerBin = new Bin("video-bin");
        scalerBin.add(videoconvert);
        scalerBin.add(videoscale);
        scalerBin.add(capsFilter);
        scalerBin.add(videoSink);

        try {
            videoconvert.link(videoscale);
            videoscale.link(capsFilter);
            capsFilter.link(videoSink);
        } catch (Exception e) {
            LX.error(e, "Failed to link elements in video-bin for pipeline: " + getPipelineName());
        }
        Pad pad = videoconvert.getStaticPad("sink");
        if (pad == null) {
            LX.log("Failed to get static pad from videoconvert element for pipeline: " + getPipelineName());
            return null;
        }
        scalerBin.addPad(new GhostPad("sink", pad));

        playbin.setVideoSink(scalerBin);
        return playbin;
    }

    @Override
    public void buildDeviceControls(LXStudio.UI ui, UIDevice uiDevice, GSTVideo pattern) {
        uiDevice.setContentWidth(250);
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
                            lx.getMediaPath() + File.separator + "GSTVideo" + File.separator,
                            (path) -> { onOpen(new File(path)); }
                    );
                }
            }
        }
                .setIcon(ui.theme.iconOpen)
                .setMomentary(true)
                .setDescription("Open Video")
                .addToContainer(fileContainer);

        final UI2dContainer knobsContainer = (UI2dContainer) new UI2dContainer(0, 25, 150, 40)
                .setLayout(UI2dContainer.Layout.HORIZONTAL)
                .addToContainer(uiDevice);

        knobsContainer.setPadding(5);
        knobsContainer.setChildSpacing(5);

        new UIKnob(0, 0, 35, 30)
                .setParameter(pattern.widthKnob)
                .addToContainer(knobsContainer);
        new UIKnob(40, 0, 35, 30)
                .setParameter(pattern.heightKnob)
                .addToContainer(knobsContainer);
        final UIButton syncT = (UIButton) new UIButton(105, 0, 35, 30)
                .setParameter(pattern.sync)
                .addToContainer(knobsContainer);
        syncT.setLabel("Sync");

        final UI2dContainer uvContainer= (UI2dContainer) new UI2dContainer(0, 70, 150, 40)
                .setLayout(UI2dContainer.Layout.HORIZONTAL)
                .addToContainer(uiDevice);
        uvContainer.setPadding(5);
        uvContainer.setChildSpacing(5);
        new UIKnob(0, 0, 35, 30)
                .setParameter(pattern.uOffset)
                .addToContainer(uvContainer);
        new UIKnob(40, 0, 35, 30)
                .setParameter(pattern.vOffset)
                .addToContainer(uvContainer);
        new UIKnob(80, 0, 35, 30)
                .setParameter(pattern.uWidth)
                .addToContainer(uvContainer);
        new UIKnob(120, 0, 35, 30)
                .setParameter(pattern.vHeight)
                .addToContainer(uvContainer);
        new UIButton(35, 30, pattern.flipHorizontal)
                .addToContainer(uvContainer);
        new UIButton(35, 30, pattern.flipVertical)
                .addToContainer(uvContainer);

        final UI2dContainer tileContainer = (UI2dContainer) new UI2dContainer(0, 115, 150, 40)
                .setLayout(UI2dContainer.Layout.HORIZONTAL)
                .addToContainer(uiDevice);
        tileContainer.setPadding(5);
        tileContainer.setChildSpacing(5);
        new UIKnob(0, 0, 35, 30)
                .setParameter(pattern.rotate)
                .addToContainer(tileContainer);
        new UIKnob(40, 0, 35, 30)
                .setParameter(pattern.tileX)
                .addToContainer(tileContainer);
        new UIKnob(80, 0, 35, 30)
                .setParameter(pattern.tileY)
                .addToContainer(tileContainer);
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

    /**
     * Each time the pattern becomes inactive, reset the play position to the beginning of the video whenever
     * sync is enabled.  This is handled in GSTBase because the default onInactive() pauses the pipeline and
     * sets the position to the current position to work around some bug on Mac OS X.  In order to reset to
     * the head of the video each time, we need to change that call because attempting to seek again to the
     * head after the pipeline is paused doesn't seem to have any effect.
     */
    @Override
    protected boolean isSyncOn() {
        return sync.isOn();
    }
}
