#!/bin/sh
# Regenerates the scanner test photo from scan-photo.ps (needs Ghostscript).
cd "$(dirname "$0")"
mkdir -p ../core/scan/src/androidTest/assets
gs -q -dBATCH -dNOPAUSE -dSAFER -sDEVICE=jpeg -dJPEGQ=90 -r72 -sOutputFile=../core/scan/src/androidTest/assets/photo.jpg scan-photo.ps
