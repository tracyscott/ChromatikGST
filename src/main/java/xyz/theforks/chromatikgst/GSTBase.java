package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.color.LXColor;
import heronarts.lx.model.LXModel;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.model.LXPoint;
import heronarts.lx.transform.LXMatrix;
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

    public GSTBase(LX lx) {
        super(lx);
        model.addListener((p) -> {
            computeUVs();
        });
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
            // so it should probably be in the constructor.
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

        renderWithUV(lastFrame);

        postRun(deltaMs);

        /*  Using model normalized coordinates.  This works okay except if there are rotations.
        for (LXPoint lp : model.points) {
            int x = Math.round(lp.xn * (width-1));
            int y = Math.round(lp.yn * (height-1));
            int color = 0;
            synchronized(chromatikSink.frameLock) {
                if (chromatikSink.lastFrame != null && x >= 0 && x < width && y >= 0 && y < height) {
                    color = chromatikSink.lastFrame.getRGB(x, y);
                } else {
                    color = LXColor.RED;
                }
            }
            colors[lp.index] = LXColor.rgb(LXColor.red(color), LXColor.green(color), LXColor.blue(color));
        }
        *
         */
    }

    protected void renderWithUV(BufferedImage lastFrame) {
        int width = lastFrame.getWidth();
        int height = lastFrame.getHeight();

        if (uvPoints == null || uvsNeedUpdate) {
            computeUVs();
        }
        // Use the UVPoint coordinates to map the colors to the model.  This is based on computing the normal plane
        // and then handling rotations to compute the uv coordinates.
        for (UVPoint uv : uvPoints) {
            int x = Math.round(uv.u * (width-1));
            int y = Math.round(uv.v * (height-1));
            int color = 0;
            if (lastFrame != null && x >= 0 && x < width && y >= 0 && y < height) {
                color = lastFrame.getRGB(x, y); //(height-1)-y);
            }
            if (uv.point.index < colors.length)
                colors[uv.point.index] = LXColor.rgb(LXColor.red(color), LXColor.green(color), LXColor.blue(color));
            else {
                uvsNeedUpdate = true;
            }
        }
    }

    protected void runNoUV(double deltaMs) {
        if (pipeline == null) {
            return;
        }
        if (chromatikSink.lastFrame == null) {
            return;
        }
        int width = chromatikSink.lastFrame.getWidth();
        int height = chromatikSink.lastFrame.getHeight();
        // Compute the x scale based on frame width and model width.
        float modelWidth = (float) model.xMax - model.xMin;
        float modelHeight = (float) model.yMax - model.yMin;
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

    //
    //
    // =========== SPATIAL UV COORDINATE MAPPING BELOW ===========
    //
    //

    public LXMatrix inverseLXMatrix(LXMatrix matrix) {
        LXMatrix result = new LXMatrix();

        // First compute inverse of rotation part (transpose)
        result.m11 = matrix.m11;  result.m12 = matrix.m21;  result.m13 = matrix.m31;
        result.m21 = matrix.m12;  result.m22 = matrix.m22;  result.m23 = matrix.m32;
        result.m31 = matrix.m13;  result.m32 = matrix.m23;  result.m33 = matrix.m33;

        // Now compute -R^T * T for the translation part
        result.m14 = -(result.m11 * matrix.m14 + result.m12 * matrix.m24 + result.m13 * matrix.m34);
        result.m24 = -(result.m21 * matrix.m14 + result.m22 * matrix.m24 + result.m23 * matrix.m34);
        result.m34 = -(result.m31 * matrix.m14 + result.m32 * matrix.m24 + result.m33 * matrix.m34);

        // Set bottom row
        result.m41 = 0;
        result.m42 = 0;
        result.m43 = 0;
        result.m44 = 1;

        return result;
    }


    protected void computeUVs() {
        if (uvPoints == null || uvsNeedUpdate)
            uvPoints = new ArrayList<UVPoint>(model.points.length);
        else
            uvPoints.clear();

        LXMatrix viewMatrix = model.transform;
        //LX.log("View matrix=" + viewMatrix.toString());
        // Iterate over model children and log some info.
        for (LXModel child : model.children) {
            //LX.log("Child model: " + child.getClass().getName());
            for (String key : child.metaData.keySet()) {
                //LX.log("Child metadata: " + key + " : " + child.metaData.get(key));
            }
            for (String tag : child.tags) {
                //LX.log("Child tag: " + tag);
            }
            LXMatrix matrix = child.transform;
            //LX.log("Child matrix=" + matrix.toString());
        }

        float[] planeNormal = computePlaneNormal();
        //LX.log("Plane normal= " + planeNormal[0] + " " + planeNormal[1] + " " + planeNormal[2]);

        normalizePlaneNormal(planeNormal);
        //LX.log("Normalized plane normal= " + planeNormal[0] + " " + planeNormal[1] + " " + planeNormal[2]);

        float[] rotateAxisAngle = computeAxesRotates(planeNormal);
        float[] rotateAxis = {rotateAxisAngle[0], rotateAxisAngle[1], rotateAxisAngle[2]};
        float rotateAngle = rotateAxisAngle[3];
        //LX.log("Rotate angle: " + rotateAngle);
        float[] rotatedPoint = new float[3];
        //LX.log("Updating UV coordinates. Rotating plane to XY plane: " + rotateAxis[0] + " " + rotateAxis[1] + " " + rotateAxis[2] + " " + rotateAngle);

        normalizePlaneNormal(rotateAxis);
        boolean vectorNonZero = vectorLength(rotateAxis) > 0;
        for (LXPoint p : model.points) {
            // For each point, rotate by rotateAxisAngle to get the plane normal to the XY plane.
            // And then construct the UVPoint from the x and y coordinates.
            float[] point = {p.x, p.y, p.z};

            // Rotate the point to the XY plane
            //if (vectorNonZero) {
                rotatePointAroundAxis(point, rotateAxis, rotateAngle, rotatedPoint);
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

    protected float vectorLength(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    public void normalizePlaneNormal(float[] planeNormal) {
        // Normalize the plane normal
        float normalLength = (float)Math.sqrt(
                planeNormal[0] * planeNormal[0] +
                        planeNormal[1] * planeNormal[1] +
                        planeNormal[2] * planeNormal[2]
        );

        planeNormal[0] = planeNormal[0] / normalLength;
        planeNormal[1] = planeNormal[1] / normalLength;
        planeNormal[2] = planeNormal[2] / normalLength;
    }

    public float[] computeAxesRotates(float[] planeNormal) {
        // Given a plane normal, compute the series of rotations to return the plane to the XY plane.
        // Compute the rotation matrix to rotate the plane normal to the XY plane.
        float[] zAxis = {0, 0, 1};
        float[] cross = new float[3];
        cross[0] = zAxis[1] * planeNormal[2] - zAxis[2] * planeNormal[1];
        cross[1] = zAxis[2] * planeNormal[0] - zAxis[0] * planeNormal[2];
        cross[2] = zAxis[0] * planeNormal[1] - zAxis[1] * planeNormal[0];
        float dot = zAxis[0] * planeNormal[0] + zAxis[1] * planeNormal[1] + zAxis[2] * planeNormal[2];
        float[] axis = new float[3];
        axis[0] = cross[0];
        axis[1] = cross[1];
        axis[2] = cross[2];
        float angle = (float) Math.acos(dot);
        return new float[] {axis[0], axis[1], axis[2], angle};
    }

    public float[] computeAxesRotatesOld(float[] planeNormal) {
        // First normalize the plane normal
        float normalLength = (float)Math.sqrt(
                planeNormal[0] * planeNormal[0] +
                        planeNormal[1] * planeNormal[1] +
                        planeNormal[2] * planeNormal[2]
        );

        float[] normalizedPlaneNormal = new float[3];
        normalizedPlaneNormal[0] = planeNormal[0] / normalLength;
        normalizedPlaneNormal[1] = planeNormal[1] / normalLength;
        normalizedPlaneNormal[2] = planeNormal[2] / normalLength;

        float[] zAxis = {0, 0, 1};

        // Compute cross product
        float[] cross = new float[3];
        cross[0] = zAxis[1] * normalizedPlaneNormal[2] - zAxis[2] * normalizedPlaneNormal[1];
        cross[1] = zAxis[2] * normalizedPlaneNormal[0] - zAxis[0] * normalizedPlaneNormal[2];
        cross[2] = zAxis[0] * normalizedPlaneNormal[1] - zAxis[1] * normalizedPlaneNormal[0];

        // Normalize the cross product (rotation axis)
        float crossLength = (float)Math.sqrt(
                cross[0] * cross[0] +
                        cross[1] * cross[1] +
                        cross[2] * cross[2]
        );

        // Check for zero cross product (vectors are parallel)
        if (crossLength < 1e-6) {
            // Vectors are parallel - check if they point in same or opposite direction
            if (normalizedPlaneNormal[2] > 0) {
                // Vectors point in same direction - no rotation needed
                return new float[] {1, 0, 0, 0};
            } else {
                // Vectors point in opposite directions - rotate 180 degrees around any perpendicular axis
                return new float[] {1, 0, 0, (float)Math.PI};
            }
        }

        float[] axis = new float[3];
        axis[0] = cross[0] / crossLength;
        axis[1] = cross[1] / crossLength;
        axis[2] = cross[2] / crossLength;

        // Compute dot product with normalized vectors
        float dot = zAxis[0] * normalizedPlaneNormal[0] +
                zAxis[1] * normalizedPlaneNormal[1] +
                zAxis[2] * normalizedPlaneNormal[2];

        // Clamp dot product to [-1, 1] range to avoid NaN
        dot = Math.max(-1, Math.min(1, dot));

        float angle = (float)Math.acos(dot);

        return new float[] {axis[0], axis[1], axis[2], angle};
    }


    public float[] computePlaneNormal() {
        // Compute the plane normal for the model
        // Compute the plane normal for the model
        LXPoint p0 = model.points[0];
        LXPoint p1 = model.points[model.points.length/2];
        LXPoint p2 = model.points[model.points.length-1];
        float[] v1 = {p1.x - p0.x, p1.y - p0.y, p1.z - p0.z};
        float[] v2 = {p2.x - p0.x, p2.y - p0.y, p2.z - p0.z};
        float[] normal = new float[3];
        normal[0] = v1[1] * v2[2] - v1[2] * v2[1];
        normal[1] = v1[2] * v2[0] - v1[0] * v2[2];
        normal[2] = v1[0] * v2[1] - v1[1] * v2[0];
        return normal;
    }

    protected void rotatePointAroundAxis(float[] point, float[] axis, float angle, float[] rotatedPoint) {
        float[] axisUnit = new float[3];
        float axisLength = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        axisUnit[0] = axis[0] / axisLength;
        axisUnit[1] = axis[1] / axisLength;
        axisUnit[2] = axis[2] / axisLength;
        float dot = point[0] * axisUnit[0] + point[1] * axisUnit[1] + point[2] * axisUnit[2];
        float[] cross = new float[3];
        cross[0] = axisUnit[1] * point[2] - axisUnit[2] * point[1];
        cross[1] = axisUnit[2] * point[0] - axisUnit[0] * point[2];
        cross[2] = axisUnit[0] * point[1] - axisUnit[1] * point[0];
        rotatedPoint[0] = (float) (point[0] * Math.cos(angle) + cross[0] * Math.sin(angle) + dot * (1 - Math.cos(angle)) * axisUnit[0]);
        rotatedPoint[1] = (float) (point[1] * Math.cos(angle) + cross[1] * Math.sin(angle) + dot * (1 - Math.cos(angle)) * axisUnit[1]);
        rotatedPoint[2] = (float) (point[2] * Math.cos(angle) + cross[2] * Math.sin(angle) + dot * (1 - Math.cos(angle)) * axisUnit[2]);
    }

    /**
     * Wrap the LXPoint's with their computed uv coordinates.  The maximum dimension will
     * be 1 and the minimum dimension will be <= 1 for non 1:1 aspect ratios.
     */
    static public class UVPoint {
        public LXPoint point;
        public float u;
        public float v;
        public UVPoint(LXPoint p, float u, float v) {
            this.point = p;
            this.u = u;
            this.v = v;
        }

        static public void renormalizeUVs(List<UVPoint> uvPoints) {
            float uMin = Float.MAX_VALUE;
            float uMax = Float.MIN_VALUE;
            float vMin = Float.MAX_VALUE;
            float vMax = Float.MIN_VALUE;
            for (UVPoint uv : uvPoints) {
                if (uv.u < uMin) {
                    uMin = uv.u;
                }
                if (uv.u > uMax) {
                    uMax = uv.u;
                }
                if (uv.v < vMin) {
                    vMin = uv.v;
                }
                if (uv.v > vMax) {
                    vMax = uv.v;
                }
            }
            float uRange = uMax - uMin;
            float vRange = vMax - vMin;
            float maxRange = Math.max(uRange, vRange);
            for (UVPoint uv : uvPoints) {
                uv.u = (uv.u - uMin) / uRange;
                uv.v = (uv.v - vMin) / vRange;
            }
        }
    }
}
