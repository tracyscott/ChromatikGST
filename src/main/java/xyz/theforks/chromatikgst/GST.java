package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.StringParameter;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.model.LXPoint;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.PlayBin;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;
import org.freedesktop.gstreamer.message.ErrorMessage;
import org.freedesktop.gstreamer.message.MessageType;
import org.freedesktop.gstreamer.message.StateChangedMessage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;

@LXCategory("Custom")
@LXComponentName("GST")
public class GST extends LXPattern {

    public final StringParameter videoFile  =
            new StringParameter("video", "chromatikgst.mp4")
                    .setDescription("Video file to play");

    public final int WIDTH = 160;
    public final int HEIGHT = 120;

    protected int frameCount = 0;
    protected PlayBin playbin;
    BufferedImage lastFrame = null;
    final private Object frameLock = new Object();

    public GST(LX lx) {
        super(lx);
        addParameter("video", this.videoFile);
    }

    protected String getVideoDir() {
        return lx.getMediaPath() + File.separator + "Video" + File.separator;
    }

    protected void initGSTWithThread() {
        Thread gstThread = new Thread(() -> {
            initializeGST();
        });
        gstThread.start();
    }

    protected void initializeGST() {
        // Initialize the GStreamer pipeline
        // Initialize GStreamer
        LX.log("Initializing GStreamer");
        Gst.init("ChromatikGST");
        playbin = new PlayBin("playbin");
        String videoFilename = getVideoDir() + videoFile.getString();
        LX.log("Playing : " + videoFilename);
        playbin.setURI(new File(getVideoDir() + videoFile.getString()).toURI());
        // Create and configure AppSink with correct video format
        AppSink videoSink = (AppSink) ElementFactory.make("appsink", "video-output");
        videoSink.set("emit-signals", true);
        // Set caps for raw video format - use BGRx for proper color
        StringBuffer capsString = new StringBuffer("video/x-raw,");
        // JNA creates ByteBuffer using native byte order, set masks according to that.
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            capsString.append("format=BGRx");
        } else {
            capsString.append("format=xRGB");
        }
        videoSink.setCaps(Caps.fromString(capsString.toString()));

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

        // Handle new video frames
        videoSink.connect(new AppSink.NEW_SAMPLE() {
            @Override
            public FlowReturn newSample(AppSink elem) {
                // Process frame if within range
                Sample sample = elem.pullSample();
                Buffer buffer = sample.getBuffer();

                // Get frame data
                ByteBuffer bb = buffer.map(false);
                Structure caps = sample.getCaps().getStructure(0);
                int width = caps.getInteger("width");
                int height = caps.getInteger("height");

                // Create image from frame data
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                bb.asIntBuffer().get(pixels);
                buffer.unmap();
                // LX.log("Got frame: " + frameCount);
                synchronized (frameLock) {
                    lastFrame = image;
                }
                sample.dispose();
                frameCount++;

                return FlowReturn.OK;
            }
        });

        // Add bus message handlers before starting playback
        Bus bus = playbin.getBus();

        // Add bus message handler
        LX.log("Adding bus message handler");
        bus.connect((Bus.MESSAGE) (bus1, message) -> {
            MessageType type = message.getType();
            //LX.log("Message type: " + type);
            // Loop video when segment is done
            if (type == MessageType.SEGMENT_DONE) {
                LX.log("Segment done, re-seeking to start");
                frameCount = 0;
                playbin.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.SEGMENT), SeekType.SET, 0, SeekType.NONE, 0);
            }
            if (type == MessageType.ERROR) {
                ErrorMessage errMsg = (ErrorMessage) message;
                LX.log("Chromatik GST error: " + errMsg.getCode() + " : " + errMsg.getMessage());
                playbin.setState(State.NULL);
                Gst.quit();
            }
        });

        // Start playing
        playbin.setState(State.PLAYING);
        playbin.getState(ClockTime.NONE);

        // Seek to start frame, this is necessary so that we get the segment done messages that we
        // need for re-seeking to the beginning in order to create an endless loop
        playbin.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.SEGMENT), SeekType.SET, 0, SeekType.NONE, 0);

        LX.log("Starting GStreamer main loop");
        Gst.main();
        LX.log("GStreamer main loop exited");
        // Clean up after main loop exits
        playbin.setState(State.NULL);
    }

    @Override
    protected void onActive() {
        // Unpause the stream if it is playing
        if (playbin != null) {
            LX.log("Resuming GStreamer playback");
            playbin.setState(State.PLAYING);
        } else {
            initGSTWithThread();
        }
    }

    @Override
    protected void onInactive() {
        // Pause the stream if it is playing
        if (playbin != null) {
            LX.log("Pausing GStreamer playback");
            playbin.setState(State.PAUSED);
        }
    }

    @Override
    protected void run(double deltaMs) {
        if (playbin == null) {
            return;
        }
        if (lastFrame == null) {
            return;
        }
        // Compute the x scale based on frame width and model width.
        float modelWidth = (float) lx.getModel().xMax - lx.getModel().xMin;
        float modelHeight = (float) lx.getModel().yMax - lx.getModel().yMin;
        float scaleX = modelWidth / WIDTH;
        float scaleY = modelHeight / HEIGHT;

        for (LXPoint point: model.points) {
            int x = (int) ((point.x - lx.getModel().xMin) / scaleX);
            int y = (int) ((point.y - lx.getModel().yMin) / scaleY);
            int color = 0;
            synchronized(frameLock) {
                if (lastFrame != null && x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) {
                    color = lastFrame.getRGB(x, (HEIGHT-1)-y);
                }
            }
            colors[point.index] = LXColor.rgb(LXColor.red(color), LXColor.green(color), LXColor.blue(color));
        }
    }
}
