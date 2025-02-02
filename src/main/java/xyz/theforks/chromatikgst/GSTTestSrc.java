package xyz.theforks.chromatikgst;

import heronarts.lx.LX;
import heronarts.lx.LXCategory;
import heronarts.lx.LXComponentName;
import heronarts.lx.parameter.DiscreteParameter;
import org.freedesktop.gstreamer.*;
import org.freedesktop.gstreamer.elements.AppSink;

/**
 * Creates a pipeline that uses the autovideosrc element to capture video from the default camera.
 */
@LXCategory("Custom")
@LXComponentName("GSTTestSrc")
public class GSTTestSrc extends GSTBase {
  public final DiscreteParameter widthKnob =
    new DiscreteParameter("Width", 160, 20, 1920)
      .setDescription("Convert video to width");

  public final DiscreteParameter heightKnob =
    new DiscreteParameter("Height", 120, 10, 1080)
      .setDescription("Convert video to height");

  protected Element capsFilter;

  public GSTTestSrc(LX lx) {
    super(lx);
    addParameter("width", this.widthKnob);
    addParameter("height", this.heightKnob);
    this.widthKnob.addListener((p) -> {
      updateCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
    });
    this.heightKnob.addListener((p) -> {
      updateCapsFilter(widthKnob.getValuei(), heightKnob.getValuei());
    });
  }

  @Override
  protected Pipeline initializePipeline() {
    Bin bin = Gst.parseBinFromDescription(
      "videotestsrc ! "
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

  protected void updateCapsFilter(int width, int height) {
    if (capsFilter == null) {
      return;
    }
    String capsStr = String.format("video/x-raw,width=%d,height=%d,format=BGRx",
      widthKnob.getValuei(), heightKnob.getValuei());
    capsFilter.set("caps", Caps.fromString(capsStr));
  }

  @Override
  protected String getPipelineName() {
    return "GSTTestSrc";
  }
}
