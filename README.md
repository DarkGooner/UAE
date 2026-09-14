# Universal Unity Asset Extractor for Android

Android app which scans a Unity game folder and extracts Unity `Texture2D`/`Sprite` images and `VideoClip` data directly into a user-selected destination. It does not create a ZIP.

## Features
- Game folder -> destination folder workflow
- Images only, videos only, or both
- Skip existing output files
- Optional preservation of source folder structure
- 1-4 worker modes for phone performance
- Pause/resume and stop
- Progress, counts and extraction speed
- Recursive Unity asset discovery
- Uses Chaquopy 17 + Python 3.13 + UnityPy/Pillow

Chaquopy 17.0 is the current documented release and supports Android 16 KB page devices; Python 3.13 is recommended for compatibility. See https://chaquo.com/chaquopy/documentation/.

## Build without Android Studio
Push this project to GitHub. The included GitHub Actions workflow builds `app-release.apk` and uploads it as an artifact.
