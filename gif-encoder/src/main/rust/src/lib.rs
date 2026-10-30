// Keep upstream's stable C API available to the small JNI bridge. No video, PNG,
// command-line or FFmpeg features are enabled; Android supplies decoded RGBA.
pub use gifski::c_api::*;
