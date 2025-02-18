package xyz.theforks.chromatikgst;

import heronarts.lx.LX;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class GSTUtil {

    public static boolean VERBOSE = false;
    public static boolean resourceFilesCopied = false;

    static protected String getVideoDir(LX lx) {
        return lx.getMediaPath() + File.separator + "GSTVideo" + File.separator;
    }

    static public void exportDefaultVideos(LX lx) {
        if (resourceFilesCopied) {
            return;
        }
        LX.log("Exporting default videos for ChromatikGST");
        // Copy all the files from the resources/video folder in the jar that this class
        // came from into the media folder getVideoDir()
        String videoDir = getVideoDir(lx);
        File videoDirFile = new File(videoDir);
        if (!videoDirFile.exists()) {
            videoDirFile.mkdirs();
        }
        // Inspect the directory contents of resources/video in the jar file that this
        // class was loaded from.
        List<String> includedVideos = GSTUtil.getIncludedVideoFiles(GSTVideo.class, "video");
        for (String includedVideo : includedVideos) {
            File videoFile = new File(videoDir + includedVideo);
            if (!videoFile.exists()) {
                LX.log("Exporting ChromatikGST video: " + includedVideo);
                try {
                    GSTUtil.copyResourceToFile(GSTVideo.class, "video/" + includedVideo, videoFile);
                } catch (Exception e) {
                    LX.log("Error copying video file: " + e.getMessage());
                }
            }
        }
        resourceFilesCopied = true;
    }

    static public List<String> getIncludedVideoFiles(Class<?> clazz, String resourcePath) {
        return listResourceFiles(clazz, resourcePath);
    }

    static public List<String> listResourceFiles(Class<?> clazz, String resourcePath) {
        try {
            URL resourceUrl = clazz.getClassLoader().getResource(resourcePath);
            if (resourceUrl == null) {
                return Collections.emptyList();
            }

            if (resourceUrl.getProtocol().equals("jar")) {
                // Handle resources in JAR
                try (FileSystem fileSystem = FileSystems.newFileSystem(
                        resourceUrl.toURI(), Collections.<String, Object>emptyMap())) {
                    Path path = fileSystem.getPath(resourcePath);
                    return listFiles(path);
                }
            } else {
                // Handle resources in regular directory (useful for development)
                Path path = Path.of(resourceUrl.toURI());
                return listFiles(path);
            }
        } catch (URISyntaxException | IOException e) {
            LX.log("Error listing resource files: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    static private List<String> listFiles(Path path) throws IOException {
        List<String> fileList = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(path, 1)) {
            walk.filter(Files::isRegularFile)
                    .forEach(filePath -> {
                        String fileName = filePath.getFileName().toString();
                        fileList.add(fileName);
                    });
        }

        return fileList;
    }

    /**
     * Copy a file from the resources path of a JAR to a file on disk.
     * @param gstClass
     * @param s
     * @param videoFile
     */
    public static void copyResourceToFile(Class<GSTVideo> gstClass, String s, File videoFile) {
        // Copy a file from the resources path of a JAR to a file on disk
        try {
            URL resourceUrl = gstClass.getClassLoader().getResource(s);
            if (resourceUrl == null) {
                LX.log("Resource not found: " + s);
                return;
            }

            if (resourceUrl.getProtocol().equals("jar")) {
                // Handle resources in JAR
                try (FileSystem fileSystem = FileSystems.newFileSystem(
                        resourceUrl.toURI(), Collections.<String, Object>emptyMap())) {
                    Path path = fileSystem.getPath(s);
                    Files.copy(path, videoFile.toPath());
                }
            } else {
                // Handle resources in regular directory (useful for development)
                Path path = Path.of(resourceUrl.toURI());
                Files.copy(path, videoFile.toPath());
            }
        } catch (URISyntaxException | IOException e) {
            LX.log("Error copying resource file: " + e.getMessage());
        }
    }
}
