package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.color.LXColor;
import heronarts.lx.parameter.BooleanParameter;
import heronarts.lx.parameter.CompoundParameter;
import heronarts.lx.parameter.DiscreteParameter;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.model.LXPoint;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.event.SeekFlags;
import org.freedesktop.gstreamer.event.SeekType;
import org.freedesktop.gstreamer.message.ErrorMessage;
import org.freedesktop.gstreamer.message.MessageType;
import org.freedesktop.gstreamer.Version;

import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

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

    static {
        appendJnaPath();
    }

    static void appendJnaPath() {
        // NOTE(tracy): This requires the GStreamer framework to be installed on the system in the standard
        // location on Mac.  For Windows, GStreamer being in the path was good enough.  Since this needs to
        // be bound before we attempt any JNA code it is currently in a static
        // initializer for our Pattern classes.  It would be good to make this more configurable as a property
        // in the ~/Chromatik/ChromatikGST directory perhaps.
        String osName = System.getProperty("os.name");
        if (osName.startsWith("Mac")) {
            String[] directories = {
              "/Volumes/Macintosh HD/System/Volumes/Data/Library/Frameworks/GStreamer.framework/Versions/1.0/lib"
            };
            String currentPath = System.getProperty("jna.library.path");
            if (currentPath == null) currentPath = "";
            StringBuilder newPath = new StringBuilder(currentPath);
            for (String dir : directories) {
                if (newPath.length() > 0) {
                    newPath.append(":");
                }
                newPath.append(dir);
            }
            System.setProperty("jna.library.path", newPath.toString());
        }
    }

    protected Pipeline pipeline;
    protected ChromatikSink chromatikSink  = new ChromatikSink();
    public boolean gstInitialized = false;
    protected List<UVPoint> uvPoints = null;
    protected Thread gstThread;
    protected boolean uvsNeedUpdate;
    protected Element capsFilter;

    public final DiscreteParameter widthKnob =
            new DiscreteParameter("Width", 160, 1, 1920)
                    .setDescription("Convert video to width");

    public final DiscreteParameter heightKnob =
            new DiscreteParameter("Height", 120, 1, 1080)
                    .setDescription("Convert video to height");

    public final CompoundParameter uOffset =
            new CompoundParameter("uOff", 0, -1, 1)
                    .setDescription("U Offset");
    public final CompoundParameter vOffset =
            new CompoundParameter("vOff", 0, -1, 1)
                    .setDescription("V Offset");
    public final CompoundParameter uWidth =
            new CompoundParameter("uWidth", 1, 0, 2)
                    .setDescription("U Width");
    public final CompoundParameter vHeight =
            new CompoundParameter("vHeight", 1, 0, 2)
                    .setDescription("V Height");

    public final CompoundParameter rotate =
            new CompoundParameter("Rotate", 0, 0, 1)
                    .setDescription("Rotate uv coordinates.");

    public final DiscreteParameter tileX =
            new DiscreteParameter("TileX", 1, 1, 10)
                    .setDescription("Tile X");
    public final DiscreteParameter tileY =
            new DiscreteParameter("TileY", 1, 1, 10)
                    .setDescription("Tile Y");

    BooleanParameter flipHorizontal = new BooleanParameter("FlipX", false);
    BooleanParameter flipVertical = new BooleanParameter("FlipY", false);

    public GSTBase(LX lx) {
        super(lx);
        model.addListener((p) -> {
            computeUVs();
        });
        addParameter("width", widthKnob);
        addParameter("height", heightKnob);
        addParameter("uOff", uOffset);
        addParameter("vOff", vOffset);
        addParameter("uWidth", uWidth);
        addParameter("vHeight", vHeight);
        addParameter("flipX", flipHorizontal);
        addParameter("flipY", flipVertical);
        addParameter("rotate", rotate);
        addParameter("tileX", tileX);
        addParameter("tileY", tileY);
    }

    abstract protected Pipeline initializePipeline();
    abstract protected String getPipelineName();

    protected void initGSTWithThread(LX lx) {
        gstThread = new Thread(() -> {
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
        if (GSTUtil.VERBOSE) LX.log("Initializing GStreamer");
        String[] initResult = Gst.init(Version.BASELINE, getPipelineName());
        for (String result : initResult) {
            if (GSTUtil.VERBOSE) LX.log("GStreamer init result: " + result);
        }
        pipeline = initializePipeline();
        configurePipelineBus();
        if (GSTUtil.VERBOSE) LX.log("Starting GStreamer main loop : " + getPipelineName());
        Gst.main();
        if (GSTUtil.VERBOSE) LX.log("GStreamer main loop exited : " + getPipelineName());
    }

    protected void updateCapsFilter(int width, int height) {
        if (capsFilter == null) {
            return;
        }
        String capsStr = String.format("video/x-raw,width=%d,height=%d,format=BGRx",
                widthKnob.getValuei(), heightKnob.getValuei());
        capsFilter.set("caps", Caps.fromString(capsStr));
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
        if (GSTUtil.VERBOSE) LX.log("Creating appsink");
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
        if (GSTUtil.VERBOSE) LX.log("Adding bus message handler for pipeline: " + getPipelineName());
        bus.connect((Bus.MESSAGE) (bus1, message) -> {
            MessageType type = message.getType();
            if (GSTUtil.VERBOSE) LX.log("Message type: " + type + " : " + message.getSource().getName() + " pipeline: " + getPipelineName());

            // Loop video when segment is done
            if (type == MessageType.SEGMENT_DONE) {
                // GStreamer video looping
                // https://stackoverflow.com/questions/53747278/seamless-video-loop-in-gstreamer
                if (GSTUtil.VERBOSE) LX.log("Segment done, re-seeking to start of pipeline: " + getPipelineName());

                chromatikSink.frameCount = 0;
                //pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE), SeekType.SET, 0, SeekType.NONE, -1);
                pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.SEGMENT, SeekFlags.ACCURATE), SeekType.SET, 0, SeekType.NONE, 0);
                //pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.FLUSH), SeekType.SET, 0, SeekType.NONE, 0);
            }
            if (type == MessageType.ERROR) {
                ErrorMessage errMsg = (ErrorMessage) message;
                if (GSTUtil.VERBOSE) LX.log("Chromatik GST error on pipeline: " + getPipelineName() + " : " + errMsg.getCode() + " : " + errMsg.getMessage());

                pipeline.setState(State.NULL);
                Gst.quit();
            }
        });

        if (GSTUtil.VERBOSE) LX.log("Setting pipeline to PLAYING state: " + getPipelineName());
        // Start playing
        pipeline.setState(State.PLAYING);
        pipeline.getState(ClockTime.NONE);

        // Seek to start frame, this is necessary so that we get the segment done messages that we
        // need for re-seeking to the beginning in order to create an endless loop
        pipeline.seek(1.0, Format.TIME, EnumSet.of(SeekFlags.SEGMENT), SeekType.SET, 0, SeekType.NONE, 0);
    }

    @Override
    public void onParameterChanged(LXParameter p) {
        super.onParameterChanged(p);
        if (p == widthKnob || p == heightKnob) {
            updateCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
        }
    }

    @Override
    protected void onActive() {
        // Compute the plane normal for the model and then compute necessary rotations
        // to return the plane to the XY plane. Rotate the points into the xy plane.
        // Translate the points to the origin.

        // Unpause the stream if it is playing
        if (pipeline != null) {
            if (GSTUtil.VERBOSE) LX.log("Resuming GStreamer playback on pipeline: " + getPipelineName());
            pipeline.setState(State.PLAYING);
            //pipeline.play();
        } else {
            // TODO(tracy): This could potentially copy a lot of files out of the jar file
            // so it should probably be in the constructor?  This only happens the first time
            // the pattern plays.
            initGSTWithThread(lx);
        }
    }

    @Override
    protected void onInactive() {
        // Pause the stream if it is playing
        if (pipeline != null) {
            if (GSTUtil.VERBOSE) LX.log("Pausing GStreamer playback on pipeline: " + getPipelineName());
            pipeline.setState(State.PAUSED);
            // By default, just set the seek position to the current position.  Note, that if
            // we don't do this then the pipeline will eventually blow up on Mac OS X.  Not
            // sure why, but this sacrificed chicken seems to be working.
            long targetPosition = pipeline.queryPosition(Format.TIME);
            if (isSyncOn())
                targetPosition = 0;
            pipeline.seek(1.0, Format.TIME,
              EnumSet.of(SeekFlags.SEGMENT, SeekFlags.FLUSH, SeekFlags.ACCURATE),
              SeekType.SET, targetPosition,
              SeekType.NONE, -1);
        }
    }

    @Override
    public void dispose() {
        if (GSTUtil.VERBOSE) LX.log("Disposing GStreamer pipeline: " + getPipelineName());
        disposePipeline();
        Gst.quit();
    }

    protected void preRun(double deltaMs) {
    }

    protected void postRun(double deltaMs) {
    }

    protected boolean isSyncOn() {
        return false;
    }

    /**
     * Renders the current frame of the video to the model based on the UVPoints that
     * have computed normalized uv coordinates for the points even if the plane is
     * rotated.
     * @param deltaMs
     */
    @Override
    protected void run(double deltaMs) {
        if (pipeline == null) {
            return;
        }
        if (chromatikSink.lastFrame == null) {
            return;
        }
        preRun(deltaMs);
        BufferedImage lastFrame = null;
        synchronized(chromatikSink.frameLock) {
            lastFrame = chromatikSink.lastFrame;
        }

        if (lastFrame != null) renderWithUV(lastFrame);

        postRun(deltaMs);
    }

    protected void renderWithUV(BufferedImage lastFrame) {
        int width = lastFrame.getWidth();
        int height = lastFrame.getHeight();
        // Re-use this array so we aren't allocating so many objects.
        float[] uvs = {0f, 0f};

        if (uvPoints == null || uvsNeedUpdate) {
            computeUVs();
        }

        // Use the UVPoint coordinates to map the colors to the model.  This is based on computing the normal plane
        // and then handling fixture rotations to compute the uv coordinates.
        for (UVPoint uv : uvPoints) {
            uvs[0] = uv.u;
            uvs[1] = uv.v;
            if (flipHorizontal.isOn()) {
                uvs[0] = 1f - uvs[0];
            }
            if (flipVertical.isOn()) {
                uvs[1] = 1f - uvs[1];
            }
            if (tileX.getValuei() > 1) {
                uvs[0] = uvs[0] * tileX.getValuei() % 1f;
            }
            if (tileY.getValuei() > 1) {
                uvs[1] = uvs[1] * tileY.getValuei() % 1f;
            }
            if (rotate.getValuef() > 0) {
                rotateUV(uvs[0], uvs[1], rotate.getValuef() * (float)Math.PI * 2, uvs);
            }

            int x = Math.round((uOffset.getValuef() + uvs[0] * uWidth.getValuef()) * (width-1));
            int y = Math.round((vOffset.getValuef() + uvs[1] * vHeight.getValuef()) * (height-1));

            int color = 0;
            if (x >= 0 && x < width && y >= 0 && y < height) {
                color = lastFrame.getRGB(x, (height-1) - y);
            }
            if (uv.point.index < colors.length)
                colors[uv.point.index] = LXColor.rgb(LXColor.red(color), LXColor.green(color), LXColor.blue(color));
            else {
                uvsNeedUpdate = true;
            }
        }
    }

    //
    //
    // =========== SPATIAL UV COORDINATE MAPPING BELOW ===========
    //
    // TODO(tracy): There is still an issue with 'Roll' on the fixture since a purely plane normal approach
    // can't fix that.  It would be nice to use the transform matrix on the fixture to handle it, but not
    // sure that works in general with a 'View' (a view can be a group of fixtures).  This will have to
    // wait until a GridModel selector approach is implemented.
    //

    public void rotateUV(float u, float v, float rad, float[] results) {
        // Rotate a UV coordinate around the origin by rad radians. uv are 0 to 1 normalized coordinates.
        // we want to rotate around the center at 0.5, 0.5.
        float x = u - 0.5f;
        float y = v - 0.5f;
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        results[0] = x * cos - y * sin + 0.5f;
        results[1] = x * sin + y * cos + 0.5f;
    }

    protected void computeUVs() {
        if (uvPoints == null || uvsNeedUpdate)
            uvPoints = new ArrayList<UVPoint>(model.points.length);
        else
            uvPoints.clear();

        float[] planeNormal = UVUtil.computePlaneNormal(model);
        //LX.log("Plane normal= " + planeNormal[0] + " " + planeNormal[1] + " " + planeNormal[2]);

        UVUtil.normalizePlaneNormal(planeNormal);
        //LX.log("Normalized plane normal= " + planeNormal[0] + " " + planeNormal[1] + " " + planeNormal[2]);

        float[] rotateAxisAngle = UVUtil.computeAxesRotates(planeNormal);
        float[] rotateAxis = {rotateAxisAngle[0], rotateAxisAngle[1], rotateAxisAngle[2]};
        float rotateAngle = rotateAxisAngle[3];
        //LX.log("Rotate angle: " + rotateAngle);
        float[] rotatedPoint = new float[3];
        //LX.log("Updating UV coordinates. Rotating plane to XY plane: " + rotateAxis[0] + " " + rotateAxis[1] + " " + rotateAxis[2] + " " + rotateAngle);

        // NOTE: Using just the plane normal doesn't handle the 'Roll' of the fixture.
        UVUtil.normalizePlaneNormal(rotateAxis);
        boolean vectorNonZero = UVUtil.vectorLength(rotateAxis) > 0;
        for (LXPoint p : model.points) {
            // For each point, rotate by rotateAxisAngle to get the plane normal to the XY plane.
            // And then construct the UVPoint from the x and y coordinates.
            float[] point = {p.x, p.y, p.z};

            // Rotate the point to the XY plane
            //if (vectorNonZero) {
                UVUtil.rotatePointAroundAxis(point, rotateAxis, rotateAngle, rotatedPoint);
            //} else {
            //    rotatedPoint[0] = point[0];
            //    rotatedPoint[1] = point[1];
            //    rotatedPoint[2] = point[2];
            //}

            UVPoint uv = new UVPoint(p, rotatedPoint[0], rotatedPoint[1]);
            uv.u = p.x;
            uv.v = p.y;
            uvPoints.add(uv);
        }
        // Points are still in world space.  Renormalize the UVs to be between 0 and 1.
        UVPoint.renormalizeUVs(uvPoints);
    }
}
