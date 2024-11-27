package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import heronarts.lx.color.LXColor;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.model.LXPoint;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;
import org.freedesktop.gstreamer.message.ErrorMessage;
import org.freedesktop.gstreamer.message.MessageType;

import java.nio.ByteOrder;
import java.util.EnumSet;

/**
 * An abstract base pattern that handles GST initialization, an AppSink for receiving images, and
 * the run method for mapping the image to the model.  Distinctly configured Pipelines should be
 * implemented as subclasses of this class and implement the initializePipeline method and the
 * getPipelineName method.
 *
 * https://gstreamer.freedesktop.org/
 * https://github.com/gstreamer-java
 * https://javadoc.io/doc/org.freedesktop.gstreamer/gst1-java-core/latest/index.html
 */
@LXCategory("Custom")
abstract public class GSTBase extends LXPattern {
    protected Pipeline pipeline;
    protected ChromatikSink chromatikSink  = new ChromatikSink();
    public boolean gstInitialized = false;

    public GSTBase(LX lx) {
        super(lx);
    }

    abstract protected Pipeline initializePipeline();
    abstract protected String getPipelineName();

    protected void initGSTWithThread(LX lx) {
        Thread gstThread = new Thread(() -> {
            initializeGST(lx);
        });
        gstThread.start();
    }

    /**
     * Gst.init() needs to be a static initializer.
     */
    protected void initializeGST(LX lx) {
        // Initialize the GStreamer pipeline
        // Initialize GStreamer
        if (gstInitialized) {
            return;
        }
        gstInitialized = true;

        LX.log("Initializing GStreamer pipeline: " + getPipelineName());
        Gst.init(getPipelineName());
        pipeline = initializePipeline();
        configurePipelineBus();
        LX.log("Starting GStreamer main loop : " + getPipelineName());
        Gst.main();
        LX.log("GStreamer main loop exited : " + getPipelineName());
    }

    protected void disposePipeline() {
        // Clean up after main loop exits
        if (pipeline != null) pipeline.setState(State.NULL);
    }

    /**
     * Create a video sink for the pipeline.  This method creates an element and connects the
     * ChromatikSink sample handler to the pipeline element.
     * @return AppSink pipeline element.
     */
    protected AppSink createVideoSink() {
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
        videoSink.connect(chromatikSink);
        return videoSink;
    }

    /**
     * Configures a generic pipeline bus to handle segments and perform loops.  This
     * will also set the pipeline state to Playing.
     */
    protected void configurePipelineBus() {
        // Add bus message handlers before starting playback
        Bus bus = pipeline.getBus();

        // Add bus message handler
        LX.log("Adding bus message handler for pipeline: " + getPipelineName());
        bus.connect((Bus.MESSAGE) (bus1, message) -> {
            MessageType type = message.getType();
            //LX.log("Message type: " + type + " : " + message.getSource().getName() + " pipeline: " + getPipelineName());

            // Loop video when segment is done
            if (type == MessageType.SEGMENT_DONE) {
                // GStreamer video looping
                // https://stackoverflow.com/questions/53747278/seamless-video-loop-in-gstreamer
                // LX.log("Segment done, re-seeking to start of pipeline: " + getPipelineName());
                chromatikSink.frameCount = 0;
                pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.SEGMENT), SeekType.SET, 0, SeekType.NONE, 0);
            }
            if (type == MessageType.ERROR) {
                ErrorMessage errMsg = (ErrorMessage) message;
                LX.log("Chromatik GST error on pipeline: " + getPipelineName() + " : " + errMsg.getCode() + " : " + errMsg.getMessage());
                pipeline.setState(State.NULL);
                Gst.quit();
            }
        });

        LX.log("Setting pipeline to PLAYING state: " + getPipelineName());
        // Start playing
        pipeline.setState(State.PLAYING);
        pipeline.getState(ClockTime.NONE);

        // Seek to start frame, this is necessary so that we get the segment done messages that we
        // need for re-seeking to the beginning in order to create an endless loop
        pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.SEGMENT), SeekType.SET, 0, SeekType.NONE, 0);
    }

    @Override
    protected void onActive() {
        // Unpause the stream if it is playing
        if (pipeline != null) {
            // LX.log("Resuming GStreamer playback on pipeline: " + getPipelineName());
            pipeline.setState(State.PLAYING);
        } else {
            // TODO(tracy): This could potentially copy a lot of files out of the jar file
            // so it should probably be in the constructor.
            initGSTWithThread(lx);
        }
    }

    @Override
    protected void onInactive() {
        // Pause the stream if it is playing
        if (pipeline != null) {
            // LX.log("Pausing GStreamer playback on pipeline: " + getPipelineName());
            pipeline.setState(State.PAUSED);
        }
    }

    @Override
    public void dispose() {
        LX.log("Disposing GStreamer pipeline: " + getPipelineName());
        disposePipeline();
        Gst.quit();
    }

    @Override
    protected void run(double deltaMs) {
        if (pipeline == null) {
            return;
        }
        if (chromatikSink.lastFrame == null) {
            return;
        }
        int width = chromatikSink.lastFrame.getWidth();
        int height = chromatikSink.lastFrame.getHeight();
        // Compute the x scale based on frame width and model width.
        float modelWidth = (float) lx.getModel().xMax - lx.getModel().xMin;
        float modelHeight = (float) lx.getModel().yMax - lx.getModel().yMin;
        float scaleX = modelWidth / width;
        float scaleY = modelHeight / height;

        for (LXPoint point: model.points) {
            int x = (int) ((point.x - lx.getModel().xMin) / scaleX);
            int y = (int) ((point.y - lx.getModel().yMin) / scaleY);
            int color = 0;
            synchronized(chromatikSink.frameLock) {
                if (chromatikSink.lastFrame != null && x >= 0 && x < width && y >= 0 && y < height) {
                    color = chromatikSink.lastFrame.getRGB(x, (height-1)-y);
                }
            }
            colors[point.index] = LXColor.rgb(LXColor.red(color), LXColor.green(color), LXColor.blue(color));
        }
    }
}
