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

## Planned structure

```
app/                  navigation, file picker, recent files
core/pdf/             Kotlin wrapper around MuPDF (single dedicated thread)
core/render/          tiled page rendering, bitmap cache, thumbnails
feature/viewer/       scrolling, zoom, search, text selection
feature/organize/     page grid: reorder, rotate, delete, insert
feature/annotate/     annotation tools, undo/redo
feature/scan/         camera to PDF, behind a swappable interface
```

## Roadmap

1. Viewer
2. Page organization, with safe saving (write to a temp file, then swap it in)
3. Annotations
4. Scanner
5. Forms, signatures, sync

## License

SzabadPDF is licensed under the GNU Affero General Public License v3.0. See [LICENSE](LICENSE).

It builds on MuPDF by Artifex Software (AGPL-3.0) and on code and a model from FairScan by Pierre-Yves Nicolas (GPL-3.0).
