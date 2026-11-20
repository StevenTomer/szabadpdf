# SzabadPDF

A free and open-source PDF viewer and editor for Android. *Szabad* is Hungarian for "free" (as in freedom).

No ads, no tracking, no watermarks, no paid tiers. Licensed under the [AGPL-3.0](LICENSE).

## Features

- View PDFs page by page or continuously: zoom, search, table of contents, links, page
  thumbnails, night mode; several documents open in tabs (drag to reorder); tooltips on icons
  when a stylus hovers
- Annotate: pen (pressure-sensitive with a stylus) and highlighter, text boxes, rectangles, ovals,
  lines and arrows, highlight/underline/strikeout (also across pages), notes; undo/redo
- Sign with a saved drawn signature; fill in forms (text, checkboxes, radio buttons, lists)
- Unsaved changes survive the app being closed (kept as a draft and offered back)
- Password protection: add, change or remove an AES-256 open password
- Edit pages: reorder, rotate, flip, duplicate, delete, insert; merge PDFs; extract pages or
  split into several files
- Reduce file size by recompressing large photos
- Scan: camera to PDF with automatic page detection and cleanup, into a new or an existing
  document; English OCR makes scans searchable, and can be run on existing scanned PDFs
- Share, save as, print; open and save files anywhere through Android's file picker

**Later**
- Cryptographic digital signatures
- Sync

## Tech stack

| Layer | Choice | License |
|---|---|---|
| PDF engine | [MuPDF](https://mupdf.com/) (`com.artifex.mupdf:fitz`) | AGPL-3.0 |
| UI | Kotlin, Jetpack Compose, Material 3 | Apache-2.0 |
| Scanning | Adapted from [FairScan](https://github.com/pynicolas/FairScan): CameraX, LiteRT segmentation model, OpenCV | GPL-3.0 / Apache-2.0 |
| OCR | [Tesseract](https://github.com/tesseract-ocr/tesseract) via [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android), tessdata_fast English | Apache-2.0 |
| Files | Storage Access Framework | (Android platform) |

Minimum Android version: 8.0 (API 26).

## Project structure

```
app/                  home screen, recent files, file picker, "Open with" handling
core/ui/              small shared Compose pieces (tooltips)
core/pdf/             Kotlin wrapper around MuPDF: opening, rendering, search, links, outline,
                      rebuilding page lists and safely writing files back
                      (all MuPDF calls run on one dedicated thread)
feature/viewer/       continuous scrolling, zoom, search, table of contents, go to page, and
                      annotating: pen and highlighter ink (stylus-aware), text selection, highlight/
                      underline/strikeout, sticky notes, eraser, undo/redo, saving
feature/organize/     page grid: drag to reorder, rotate, duplicate, delete, insert blank pages or
                      other PDFs; and merging several PDFs into one
core/scan/            document detection and cleanup: FairScan's image processing (copied unchanged,
                      see core/scan/README.md) and segmentation model, behind DocumentScanner
feature/scan/         camera with live page outline, photo import, review (crop, rotate, colour,
                      reorder, delete) and saving as PDF
testdata/             source for the test PDF used by core/pdf's instrumented tests
```


## Building

Requires JDK 21 and the Android SDK (platform 37.2).

```sh
./gradlew assembleDebug                         # app/build/outputs/apk/debug/
./gradlew assembleRelease                       # app/build/outputs/apk/release/ (see below)
./gradlew installDebug                          # install on a connected device or emulator
./gradlew :core:pdf:connectedDebugAndroidTest :core:scan:connectedDebugAndroidTest
                                                # engine and scanner tests (need a device or emulator)
```

The debug build installs as "org.szabadpdf.debug", alongside any release build. Builds produce one
APK per processor type (arm64-v8a, armeabi-v7a, x86_64, x86), since the native libraries (MuPDF,
OpenCV, LiteRT) are large. The first build downloads the scanner's segmentation model from GitHub
and checks its SHA-256.

Release builds are shrunk with R8 and signed with a key kept outside the repository: put
`storeFile`, `storePassword`, `keyAlias` and `keyPassword` in
`~/.android-keys/szabadpdf-release.properties` (or pass `-Pszabadpdf.signing=<path>`). Without
it, release APKs come out unsigned. Released builds are signed with the certificate whose SHA-256 is
`09:CB:F8:95:8D:A7:F6:6C:8D:AD:74:A5:12:5A:F5:1A:DE:21:D0:C0:73:E9:BB:5D:4A:7C:55:3A:9C:A3:06:2A`.

## Viewing

Documents open page by page (swipe sideways to turn pages) by default; "Scroll continuously" in
the ⋮ menu switches to one long scroll and is remembered. Every opened document stays open in a
tab (kept across restarts, up to 10). The ⋮ menu also has Share, Save as and Print; sharing and
printing include unsaved annotations, and printing draws them into the pages so every printer
shows them.

## Roadmap

1. Viewer (done: scrolling, pinch and double-tap zoom with sharp re-rendering, search, table of contents, links, go to page, recent files)
2. Page organization (done: drag to reorder, rotate, duplicate, delete, move to start/end, merge PDFs,
   insert blank pages or
   pages from another PDF, undo/redo; save in place or as a copy. Saving writes and checks a new file
   first, and backs up the original so a failed write puts it back)
3. Annotations (done: freehand pen and highlighter with colours and widths, stylus support (a pen
   draws while fingers scroll and zoom; the pen's eraser end erases), text selection and copy,
   highlight/underline/strikeout, sticky notes, eraser, undo/redo. Annotations are standard PDF
   annotations, so other readers show them; saving appends to the original file when possible,
   which keeps existing digital signatures valid)
4. Scanner (done: live page detection, automatic crop, perspective correction and cleanup, colour /
   greyscale / black-and-white, manual corner adjustment, import from photos, save as PDF, or add
   the scanned pages to an existing document from the viewer's ⋮ menu or the page organizer). Scanned
   pages are searchable: text is recognized with Tesseract (English) and laid invisibly over the
   page image
5. Drafts (autosave), passwords, signatures, forms, extract/split/compress, OCR for existing
   PDFs, text boxes and shapes, pen pressure, selection across pages, night mode, thumbnails (done,
   released as 0.6.0)
6. Sync

## License

SzabadPDF is licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

It builds on MuPDF by Artifex Software (AGPL-3.0) and on code and a model from FairScan by Pierre-Yves Nicolas (GPL-3.0).
