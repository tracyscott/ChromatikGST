package xyz.theforks.chromatikgst;

import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.elements.AppSink;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class ChromatikSink implements AppSink.NEW_SAMPLE {

    protected int frameCount = 0;
    protected final AtomicReference<BufferedImage> lastFrame = new AtomicReference<>();

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
        lastFrame.set(image);
        sample.dispose();
        frameCount++;

        return FlowReturn.OK;
    }

}
