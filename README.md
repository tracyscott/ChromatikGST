## Chromatik GST

This is a Chromatik package that provides a simple implementation of the [GStreamer](https://gstreamer.freedesktop.org/) library for audio and video processing.



### Building and Installation

Packages are distributed as a JAR file containing all of the above copmonents.

- Build with `mvn package`
- Install via `mvn install`

_Note that `mvn install` does **not** automatically copy static files from [`src/main/resources`](src/main/resources) into your root `~/Chromatik` folder. You can either perform this step manually, or by importing the package using the Chromatik UI._
