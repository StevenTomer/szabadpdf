# SzabadPDF

A free and open-source PDF viewer and editor for Android. *Szabad* is Hungarian for "free" (as in freedom).

No ads, no tracking, no watermarks, no paid tiers. Licensed under the [AGPL-3.0](LICENSE).

## Features

**Planned for the first version**
- View PDFs: smooth scrolling, zoom, thumbnails, text search and selection
- Annotate: highlight, underline, strikeout, freehand ink, notes, shapes; undo/redo
- Organize pages: reorder, rotate, delete, insert
- Scan: camera to PDF with automatic page detection and cleanup
- Open and save files anywhere through Android's file picker (local storage, Google Drive, Dropbox, OneDrive)

**Later**
- Form filling
- Signatures (drawn signatures first, then cryptographic digital signatures)
- Sync

## Tech stack

| Layer | Choice | License |
|---|---|---|
| PDF engine | [MuPDF](https://mupdf.com/) (`com.artifex.mupdf:fitz`) | AGPL-3.0 |
| UI | Kotlin, Jetpack Compose, Material 3 | Apache-2.0 |
| Scanning | Adapted from [FairScan](https://github.com/pynicolas/FairScan): CameraX, LiteRT segmentation model, OpenCV | GPL-3.0 / Apache-2.0 |
| Files | Storage Access Framework | (Android platform) |

Minimum Android version: 8.0 (API 26).

## Project structure

```
app/                  home screen, recent files, file picker, "Open with" handling
core/pdf/             Kotlin wrapper around MuPDF: opening, rendering, search, links, outline,
                      rebuilding page lists and safely writing files back
                      (all MuPDF calls run on one dedicated thread)
feature/viewer/       continuous scrolling, zoom, search, table of contents, go to page
feature/organize/     page grid: drag to reorder, rotate, delete, insert blank pages or other PDFs
testdata/             source for the test PDF used by core/pdf's instrumented tests
```

Planned: `feature/annotate/` (annotation tools, undo/redo) and `feature/scan/` (camera to PDF,
behind a swappable interface).

## Building

Requires JDK 21 and the Android SDK (platform 37.2).

```sh
./gradlew assembleDebug                         # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                          # install on a connected device or emulator
./gradlew :core:pdf:connectedDebugAndroidTest   # engine tests (need a device or emulator)
```

The debug build installs as "org.szabadpdf.debug", alongside any release build.

## Roadmap

1. Viewer (done: scrolling, pinch and double-tap zoom with sharp re-rendering, search, table of contents, links, go to page, recent files)
2. Page organization (done: drag to reorder, rotate, delete, move to start/end, insert blank pages or
   pages from another PDF, undo/redo; save in place or as a copy. Saving writes and checks a new file
   first, and backs up the original so a failed write puts it back)
3. Annotations
4. Scanner
5. Forms, signatures, sync

## License

SzabadPDF is licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

It builds on MuPDF by Artifex Software (AGPL-3.0) and on code and a model from FairScan by Pierre-Yves Nicolas (GPL-3.0).
