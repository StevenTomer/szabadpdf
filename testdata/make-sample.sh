#!/bin/sh
# Rebuilds the test PDF used by the core:pdf instrumented tests. Needs Ghostscript.
set -e
cd "$(dirname "$0")"
gs -q -dBATCH -dNOPAUSE -dSAFER -sDEVICE=pdfwrite \
   -o ../core/pdf/src/androidTest/assets/sample.pdf sample.ps
