import java.io.InputStreamReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

int drawingAreaHeight = 4;  // 4 pixels high
int drawingAreaWidth = 468; // 468 pixels wide
int recordLengthSeconds = 5;
int fadeSecs = 2;
int videoFPS = 30;
int h264crf = 0; // 0 = lossless, 23 = default
int CRF_LOSSLESS = 0;
int CRF_LOSSY = 23;

int displayScale = 4;  // Scale factor for display
int toolbarHeight = 40;

// Start/Stop Recording
int buttonWidth = 120;
int buttonHeight = 30;
int buttonPadding = 10;

int loopMode = 0;
int noLoopBtnWidth = 120;
int fadeInFadeOutBtnWidth = 120;
int crossfadeBtnWidth = 120;

int buttonY = (drawingAreaHeight * displayScale) + (toolbarHeight - buttonHeight)/2;


boolean isRecording = false;
int frameCounter = 0;
PGraphics drawingBuffer;

void setup() {
  // Window size is scaled up for display
  //size(drawingAreaWidth*displayScale, 4 * displayScale +52 );  // (468 * 3) x ((4 * 3) + 40)
  size(1872, 56);
  background(0);
  frameRate(30);
  
  // Create buffer for actual drawing at original resolution
  drawingBuffer = createGraphics(drawingAreaWidth, drawingAreaHeight);
}

void draw() {
  // Clear the drawing buffer
  drawingBuffer.beginDraw();
  
  // Render frame into the buffer
  renderFrame(frameCounter);
  
  drawingBuffer.endDraw();
  
  // Display scaled-up version of the buffer
  pushMatrix();
  scale(displayScale);  // Scale up both dimensions
  image(drawingBuffer, 0, 0);
  popMatrix();
  
  
  // Only record the specified length of video frames.  Also, once we are done,
  // write the ffmpeg wrapper scripts.
  if (isRecording && frameCounter >= recordLengthSeconds * videoFPS) {
    isRecording = false;
    if (loopMode == 0) 
      generateVideoFile(ffmpegNoLoop());
    if (loopMode == 1) 
      generateVideoFile(ffmpegFadeInOut(fadeSecs, recordLengthSeconds));
    if (loopMode == 2) 
      generateVideoFile(ffmpegCrossFade(fadeSecs, recordLengthSeconds));
  }

  renderToolbar();
  
  // If recording, save the frame
  if (isRecording) {
    // Make sure the frames directory exists
    File framesDir = new File(sketchPath("frames"));
    if (!framesDir.exists()) {
      framesDir.mkdir();
    }
    // Save the unscaled buffer to file
    drawingBuffer.save(sketchPath("frames") + "/frame" + nf(frameCounter, 4) + ".png");
    frameCounter++;
  }
}

void renderToolbar() {
  // Draw toolbar background
  fill(200);
  noStroke();
  rect(0, drawingAreaHeight * displayScale, width, toolbarHeight);


  int toolbarTextY = (drawingAreaHeight * displayScale) + toolbarHeight/2;
  
  int buttonStart = buttonPadding;
  
  textAlign(CENTER, CENTER);
   
  // Draw buttons
  // Start Recording button
  if (!isRecording) {
    fill(0, 255, 0);
    rect(buttonPadding, buttonY, 
         buttonWidth, buttonHeight);
    fill(0);
    text("Start Recording", buttonPadding + buttonWidth/2, toolbarTextY);
  }
  
  // Stop Recording button
  if (isRecording) {
    fill(255, 0, 0);
    rect(buttonPadding, buttonY, 
         buttonWidth, buttonHeight);
    fill(0);
    text("Stop Recording", buttonPadding + buttonWidth/2, toolbarTextY);
  }
  
  buttonStart += buttonPadding + buttonWidth;
  
  // No loop button.
  if (loopMode == 0)
    fill(255, 255, 255);
  else
    fill(180, 180, 180);
  rect(buttonStart, buttonY, noLoopBtnWidth, buttonHeight);
  fill(0);
  text("No loop", buttonStart + noLoopBtnWidth/2, toolbarTextY);
  
  // Fade In Fade Out loop button.
  buttonStart += noLoopBtnWidth + buttonPadding;
  if (loopMode == 1)
    fill(255, 255, 255);
  else
    fill(180, 180, 180);
  rect(buttonStart, buttonY, fadeInFadeOutBtnWidth, buttonHeight);
  fill(0);
  text("Fade In Out", buttonStart + fadeInFadeOutBtnWidth/2, toolbarTextY);
  
  // Crossfade loop button.
  buttonStart += fadeInFadeOutBtnWidth + buttonPadding;
  if (loopMode == 2)
    fill(255, 255, 255);
  else
    fill(180, 180, 180);
  rect(buttonStart, buttonY, crossfadeBtnWidth, buttonHeight);
  fill(0);
  text("Crossfade", buttonStart + crossfadeBtnWidth/2, toolbarTextY);  
}

void writeToFile(String contents, String filename) {
  // Create any necessary parent directories
  File file = new File(filename);
  file.getParentFile().mkdirs();
  
  // Write the contents to the file
  PrintWriter writer = createWriter(filename);
  writer.print(contents);
  writer.flush();
  writer.close();
}

// Example of rendering animated colorized 2D perlin noise.
void renderFrameExample(int frameNumber) {
  float noiseScale = 0.02;  // Scale of the noise
  float ynoiseScale = 0.5 + 0.5 * sin(frameCount/100.0);
  drawingBuffer.loadPixels();
  for (int x = 0; x < drawingAreaWidth; x++) {
    for (int y = 0; y < drawingAreaHeight; y++) {
      // Use 2D noise, using frameCount to animate horizontally
      float noiseVal = noise(x * noiseScale + frameCount * 0.02, y * (noiseScale + ynoiseScale) + frameCount * 0.01);
      double[] rgb = palette0(noiseVal);
      color c = color((int)(rgb[0]*255), (int)(rgb[1]*255), (int)(rgb[2]*255)); //color(noiseVal * 255);
      drawingBuffer.pixels[y * drawingAreaWidth + x] = c;
    }
  }
  drawingBuffer.updatePixels();
}

// 4 pixel wide white line that advances to the right.
void renderLineTest(int frameNumber) {
  drawingBuffer.background(172);
  int totalFrames = videoFPS * recordLengthSeconds;
  int modFrameNumber = frameNumber % totalFrames;
  float xt = (float)modFrameNumber/(float)totalFrames;
  int xpos = (int)((float)drawingBuffer.width * xt);
  drawingBuffer.noStroke();
  drawingBuffer.fill(255);
  drawingBuffer.rect(xpos, 0, 4, 4);
}


void mousePressed() {
  // Check if click is in the button area
  if (mouseY > drawingAreaHeight * displayScale && mouseY < height) {
    if (mouseX > buttonPadding && mouseX < buttonPadding + buttonWidth) {
      if (!isRecording) {
        // Start recording
        isRecording = true;
        frameCounter = 0; // Reset frame counter when starting new recording
        cleanPreviousFrames();
      } else {
        // Stop recording
        isRecording = false;
      }
    }
  }
  int loopBtn = loopBtnPressed(mouseX, mouseY);
  if (loopBtn != -1)
    loopMode = loopBtn;
}

int loopBtnPressed(int x, int y) {
  int buttonStart = buttonPadding;
  buttonStart += buttonWidth + buttonPadding;
  if (y > buttonY) {
    if (x >= buttonStart && x <= buttonStart + noLoopBtnWidth)
      return 0;
    buttonStart += noLoopBtnWidth + buttonPadding;
    if (x >= buttonStart && x <= buttonStart + fadeInFadeOutBtnWidth)
      return 1;
    buttonStart += fadeInFadeOutBtnWidth + buttonPadding;
    if (x >= buttonStart && x <= buttonStart + crossfadeBtnWidth)
      return 2;
  }
  
  return -1;
}

double[] palette(double t, double[] a, double[] b, double[] c, double[] d) {
      double[] result = new double[3];
      for (int i = 0; i < 3; i++) {
        result[i] = Math.max(0, Math.min(1, a[i] + b[i] * Math.cos(6.28318 * (c[i] * t + d[i]))));
      }
      return result;
}

double[] palette0(double t) {
      double[] a = {0.5, 0.5, 0.5};
      double[] b = {0.5, 0.5, 0.5};
      double[] c = {1.0, 1.0, 1.0};
      double[] d = {0.263, 0.416, 0.557};
      return palette(t, a, b, c, d);
}


String ffmpegNoLoop() {
 return "ffmpeg -y -i frame%4d.png -c:v libx264 -crf " + h264crf + " -vf format=yuv420p -movflags +faststart output.mp4";
}

String ffmpegFadeInOut(int fadeSecs, int videoLengthSecs) {
  float fadeOutStartSecs = videoLengthSecs - fadeSecs;
  return "ffmpeg -y -i frame%4d.png -c:v libx264 -crf " + h264crf + " -vf fade=in:0:d=5,fade=out:st=" + fadeOutStartSecs + ":d=" + fadeSecs + ",format=yuv420p -movflags +faststart output.mp4";
}

// https://stackoverflow.com/questions/38186672/ffmpeg-make-a-seamless-loop-with-a-crossfade/38189232#38189232
// ffmpeg -i video.mp4 -filter_complex
//        "[0]split[body][pre];
//         [pre]trim=duration=1,format=yuva420p,fade=d=1:alpha=1,setpts=PTS+(28/TB)[jt];
//         [body]trim=1,setpts=PTS-STARTPTS[main];
//         [main][jt]overlay"   output.mp4
         
String ffmpegCrossFade(int fadeSecs, int videoLengthSecs) {
  int unmixedSecs = videoLengthSecs - 1 * fadeSecs;
  return "ffmpeg -y -i frame%4d.png -c:v libx264 -crf " + h264crf + " -filter_complex \"[0]split[body][pre];" +
  "[pre]trim=duration=" + fadeSecs + 
  ",format=yuv420p,fade=d=" + fadeSecs + ":alpha=" + 1 + ",setpts=PTS+(" + unmixedSecs + "/TB)[jt];" +
  "[body]trim=" + fadeSecs + ",setpts=PTS-STARTPTS[main];" +
  "[main][jt]overlay\" output.mp4";
}

void generateVideoFile(String ffmpegCmd) {
  String response = executeWindowsCommand(ffmpegCmd, sketchPath() + File.separator + "frames");
  if (response != null) {
    System.out.println(response);
  }
}

String executeWindowsCommand(String command, String workingDir) {
  // StringBuilder to store the command output
  StringBuilder output = new StringBuilder();
  String originalDir = System.getProperty("user.dir");
  
  try {
    if (workingDir != null && !workingDir.isEmpty()) {
      System.setProperty("user.dir", workingDir);
    }
    // Create ProcessBuilder instance for Windows command interpreter
    ProcessBuilder processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
    processBuilder.directory(new File(workingDir));
    
    // Redirect error stream to output stream
    processBuilder.redirectErrorStream(true);
    
    // Start the process
    Process process = processBuilder.start();
    
    // Read the output
    BufferedReader reader = new BufferedReader(
      new InputStreamReader(process.getInputStream(), "UTF-8")
    );
    
    String line;
    // Read line by line and append to output
    while ((line = reader.readLine()) != null) {
      output.append(line).append("\n");
    }
    
    // Wait for the process to complete
    int exitCode = process.waitFor();
    
    // Check if process completed successfully
    if (exitCode != 0) {
      println("Warning: Command exited with code " + exitCode);
    }
    
    // Close the reader
    reader.close();
    
  } catch (IOException e) {
    println("Error executing command: " + e.getMessage());
    return "Error: " + e.getMessage();
  } catch (InterruptedException e) {
    println("Command execution interrupted: " + e.getMessage());
    Thread.currentThread().interrupt(); // Restore interrupted status
    return "Error: Command interrupted";
  } finally {
    System.setProperty("user.dir", originalDir);
  }
  
  return output.toString();
}

public void cleanPreviousFrames() {
  try {
    int deleted = deleteMatchingFiles("*.png", "frames");
    System.out.println("Cleaned " + deleted + " previous frames");
  } catch (IOException ioex) {
    System.err.println("Error cleaning pre-existing image frames: " + ioex.getMessage());
  }
}

/**
 * Deletes all files in the specified directory that match the given glob pattern.
 * 
 * @param glob The glob pattern to match files against (e.g., "*.png")
 * @param targetDir The directory path where to delete matching files
 * @return The number of files deleted
 * @throws IOException If there's an error accessing the directory or deleting files
*/
public int deleteMatchingFiles(String glob, String targetDir) throws IOException {
    // Convert directory string to Path
    Path dirPath = Paths.get(targetDir);
    
    // Verify directory exists and is actually a directory
    if (!Files.exists(dirPath)) {
      throw new IOException("Directory does not exist: " + targetDir);
    }
    if (!Files.isDirectory(dirPath)) {
      throw new IOException("Path is not a directory: " + targetDir);
    }

    // Create a PathMatcher for the glob pattern
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
    
    // Counter for deleted files
    int deletedCount = 0;

    // Use try-with-resources to ensure the stream is closed
    try (Stream<Path> paths = Files.list(dirPath)) {
      // Find and delete matching files
      deletedCount = paths
          .filter(path -> matcher.matches(path.getFileName()))
          .mapToInt(path -> {
              try {
                Files.delete(path);
                return 1;
              } catch (IOException e) {
                System.err.println("Failed to delete file: " + path + " - " + e.getMessage());
                return 0;
              }
            })
            .sum();
    }

    return deletedCount;
}
    
//
//
// Implement your custom drawing below.
//
// Once your frames are recorded in the frames/ directory, construct a video file with ffmpeg:
//
// ffmpeg -i frame%4d.png -vf format=yuv420p -movflags +faststart output.mp4
//
// In order to have a non-glitching looping video you can implement an appropriate visual
// that has a start time and end time where the end visual is the same as the start visual.
// You will also need to change the recording to start and stop programmatically at some known
// time.
//
// Another option is to fade in and fade out the video which means the looping points will be
// black.  Fading can be achieved with the appropriate ffmpeg video filter arguments.  The fade
// out must specify a start time, so you will need to know the length of the video.  The below
// assumes 29 seconds.
//
// ffmpeg -i frame%4d.png -vf fade=in:0:d=5,fade=out:st=24:d=5,format=yuv420p -movflags +faststart output.mp4
//
// Another option is to crossfade the end of the video with it's own start.  A couple links:
// https://stackoverflow.com/questions/60043174/cross-fade-video-to-itself-with-ffmpeg-for-seamless-looping
// https://stackoverflow.com/questions/38186672/ffmpeg-make-a-seamless-loop-with-a-crossfade/38189232#38189232

// 
//
// https://video.stackexchange.com/questions/28269/how-do-i-fade-in-and-out-in-ffmpeg
// 

/**
 * Implement this method for drawing to a 4 x 468 canvas.  The drawing will be scaled by 3 when being
 * displayed to make it easier to see.
 */
void renderFrame(int frameNumber) {
  renderFrameExample(frameNumber);
  //renderLineTest(frameNumber);
}
