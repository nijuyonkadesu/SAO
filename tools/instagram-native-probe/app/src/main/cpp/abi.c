// PackageManager must launch the probe through the emulator's ARM64 native bridge.
// The probe loads Instagram's installed codecs; it never bundles those libraries.
int sao_codec_probe_abi(void) { return 0; }
