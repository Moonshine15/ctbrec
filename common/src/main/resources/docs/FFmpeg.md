#### FFmpeg

[FFmpeg](https://ffmpeg.org) is a multimedia framework, which comes with a very powerful command line tool to process multimedia files.
It is available for all major platforms. FFmpeg can be used to manually and automatically remux recordings.

##### Installation

* Linux: Use the package manager of your distribution to find and install ffmpeg
* Windows:
    1. Download the latest stable version from the homepage
       (You probably want to get the 64-bit statically linked build)
    2. Extract the zip
    3. Add the bin directory to your PATH environment variable
       (If you don't know what I'm talking about, do a Youtube search for FFmpeg installation on Windows or something like that)
* macOS: See Windows

##### Manual remuxing

* Open a terminal (command prompt) and cd into the directory of the recording
* Run FFmpeg:

  `ffmpeg -i AwesomeGirl_-2019-04-04_15-46-19_195.ts -c:v copy -c:a copy AwesomeGirl_-2019-04-04_15-46-19_195.mp4`

  As you can see, the codecs for video and audio are set to copy, which means, that the recording is not reencoded, but just "copied" to
  another container format. You could also use mkv or avi for the output file suffix and FFmpeg would create that respective file. Depending
  on your hardware specs and the length of the recording, this process probably takes a few seconds up to a couple of minutes.

##### Automatic remuxing

FFmpeg can also be used to automatically remux recordings. ctbrec provides a [post-processing](PostProcessing.md) mechanism for this purpose.  

##### Chaturbate LL-HLS

For Chaturbate LL-HLS recordings, ctbrec now fetches the live video and audio playlists in Java and only uses FFmpeg on local manifests and local files.
This avoids letting FFmpeg chase Chaturbate's short live window and one-shot/sessionized master-playlist URLs directly.

If you customize merged-download FFmpeg arguments, keep them limited to output-side behavior.
For the Chaturbate LL-HLS local remux path, ctbrec ignores incompatible input-selection flags such as `-i`, `-map`, `-an`, `-vn`, `-sn`, and `-dn`
because the recorder must keep the selected local video and audio inputs fixed.
