# core/scan

Document detection and cleanup for the scanner.

`src/main/kotlin/org/fairscan/imageprocessing/` is copied unchanged from
[FairScan](https://github.com/pynicolas/FairScan) (commit `1f0fce5`, module `imageprocessing`),
Copyright 2025-2026 The FairScan authors, GPL-3.0-or-later. `Ocr.kt` and `BitonalPacking.kt`
were left out because nothing here uses them.

`Segmentation.kt` and `QuadStabilizer.kt` are adapted from FairScan's app
(`ImageSegmentation.kt`, `QuadStabilizer.kt`), same copyright and license.

The segmentation model, `fairscan-segmentation-model.tflite` v1.2.0 (GPL-3.0), is downloaded from
[fairscan-segmentation-model](https://github.com/pynicolas/fairscan-segmentation-model) at build
time and checked against a pinned SHA-256 (see `build.gradle.kts`).

Text recognition uses [Tesseract](https://github.com/tesseract-ocr/tesseract) through
[Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) (both Apache-2.0), with
English data from [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) (Apache-2.0),
downloaded at build time from a pinned commit and checked against a SHA-256. `TextRecognizer.kt`
follows FairScan's `OcrService.kt`.

GPL-3.0 code may be combined with AGPL-3.0 code (section 13 of both licenses); SzabadPDF as a
whole is distributed under the AGPL-3.0.
