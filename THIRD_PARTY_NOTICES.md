# MIMI Android — Third-party software

Original license texts and notices are included under `third-party/licenses/` and in the app's offline **设置 → 开源组件** viewer. Copyright remains with the respective upstream authors. The font's original notice is also in `app/src/main/assets/fonts/OFL.txt`.

| Component | Version | License / source |
| --- | --- | --- |
| VideoLAN LibVLC Android | Maven 3.7.7; VLC 3.0.25, changeset `3.0.24-35-gfb1b3c819b` | LGPL-2.1; https://code.videolan.org/videolan/libvlcjni ; https://code.videolan.org/videolan/vlc |
| FFmpegKit maintained, audio | Maven 8.1.9; FFmpeg n8.1.3 | LGPL-3.0; https://github.com/ffmpegkit-maintained/ffmpeg ; https://github.com/FFmpeg/FFmpeg/tree/n8.1.3 |
| libarchive-android | 1.1.7; libarchive 3.8.8 | Apache-2.0 bindings; BSD-compatible native components; https://github.com/zhanghai/libarchive-android/tree/v1.1.7 |
| AndroidX Media3 common/session | 1.9.4 | Apache-2.0; https://github.com/androidx/media/tree/1.9.4 |
| AndroidX activity / legacy support | 1.10.1 / 1.0.0 | Apache-2.0; https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library | 2.2.10 | Apache-2.0; https://github.com/JetBrains/kotlin/tree/v2.2.10 |
| Zip4j | 2.11.6 | Apache-2.0; https://github.com/srikanth-lingala/zip4j/tree/v2.11.6 |
| juniversalchardet | 2.5.2 | MPL-1.1 / GPL / LGPL upstream tri-license; https://github.com/albfernandez/juniversalchardet/tree/v2.5.2 |
| smart-exception-java | 0.2.1 | LGPL-3.0; https://github.com/arthenica/smart-exception-java |
| LLVM libc++ | LibVLC-supplied shared runtime | Apache-2.0 WITH LLVM-exception; https://github.com/llvm/llvm-project |
| ZCOOL KuaiLe | bundled TTF | SIL OFL 1.1; https://github.com/google/fonts/tree/main/ofl/zcoolkuaile |

FFmpeg's upstream audio AAR includes original notices for cpu_features, LAME, libiconv, libilbc, libogg, libsndfile, libvorbis, opencore-amr, Opus, Shine, SoXR, Speex, TwoLAME and vo-amrwbenc. These are preserved verbatim. Runtime configuration is in `third-party/native-build-info.json`. This is the **audio** LGPL variant. GPL v3's text is included because LGPL v3 incorporates its terms.

libarchive component licenses cover libarchive, bzip2, LZ4, Mbed TLS, XZ/liblzma and Zstandard. `third-party/libarchive/submodules.json` records their upstream commits; `provenance.json` records original AAR and file hashes.

## Local packaging changes and rebuilding

1. LibVLC AAR: retain ARM64/x86_64; omit unused 32-bit ABIs. Retained decoder binaries are unchanged. The shared C++ runtime has non-allocated debug/symbol sections stripped; all allocated sections are verified unchanged (see `third-party/symbol-stripping.json`).
2. FFmpegKit AAR: same filtering; omit duplicate `libc++_shared.so` so both libraries use the copy supplied by LibVLC. Required exported C++ symbols and native execution were checked.
3. libarchive-android: compile three unchanged upstream Java bindings against SDK 36 instead of consuming AAR metadata requiring a preview SDK. JNI binaries are unchanged; unused ABIs are omitted.

Run `python tools/prepare-native-libs.py` to retrieve and verify the pinned upstream AARs, then extract the eligible ABIs. Use `python tools/strip-native-symbols.py --llvm-strip /path/to/llvm-strip` to apply the optional release symbol optimization. `third-party/native-artifacts.json` and `third-party/libarchive/provenance.json` record artifact versions and checksums.

The supplied unminified application source/build scripts allow replacing and relinking modified libraries. Build a debug APK with your local key or provide your own release signing configuration. Android requires removing an existing differently signed installation before installing a differently signed build; export data beforehand if needed. Application code imposes no restriction on modifying these libraries or reverse engineering needed to debug LGPL modifications.

The original release signing key and OpenRouter credentials are not included. They are not needed to build an independently signed APK.

The bundled font is losslessly repackaged as WOFF2; the original TTF and verification are in `third-party/fonts/`. All 7055 glyph outlines, cmap entries and advances are preserved. Native source code was not changed or rebuilt.
