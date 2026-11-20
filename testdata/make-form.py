#!/usr/bin/env python3
"""Writes core/pdf/src/androidTest/assets/form.pdf: one page with a fillable form.

Fields: "name" (text), "agree" (checkbox), "color" (radio: Red / Blue), "size" (dropdown).
"""
import os

def stream(body):
    data = body.encode()
    return f"<< /Length {len(data)} >>\nstream\n".encode() + data + b"\nendstream"

objs = {}
objs[1] = b"<< /Type /Catalog /Pages 2 0 R /AcroForm 20 0 R >>"
objs[2] = b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
objs[3] = (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
           b"/Resources << /Font << /Helv 5 0 R >> >> /Annots [10 0 R 11 0 R 13 0 R 14 0 R 15 0 R] >>")
objs[4] = stream("BT /Helv 14 Tf 72 700 Td (Order form) Tj ET "
                 "BT /Helv 11 Tf 72 658 Td (Name) Tj ET "
                 "BT /Helv 11 Tf 72 606 Td (I agree) Tj ET "
                 "BT /Helv 11 Tf 72 556 Td (Colour: red / blue) Tj ET "
                 "BT /Helv 11 Tf 72 506 Td (Size) Tj ET")
objs[5] = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
# Appearance streams for the buttons: a filled square when on, an empty box when off.
objs[6] = stream("0 g 3 3 12 12 re f 0 G 0.5 0.5 17 17 re S").replace(b"<< /Length", b"<< /Type /XObject /Subtype /Form /BBox [0 0 18 18] /Length")
objs[7] = stream("0 G 0.5 0.5 17 17 re S").replace(b"<< /Length", b"<< /Type /XObject /Subtype /Form /BBox [0 0 18 18] /Length")
objs[10] = (b"<< /Type /Annot /Subtype /Widget /FT /Tx /T (name) /V () /F 4 /P 3 0 R "
            b"/Rect [150 650 450 672] /DA (/Helv 12 Tf 0 g) /MK << /BC [0 0 0] >> >>")
objs[11] = (b"<< /Type /Annot /Subtype /Widget /FT /Btn /T (agree) /V /Off /AS /Off /F 4 /P 3 0 R "
            b"/Rect [150 600 168 618] /MK << /CA (4) >> /AP << /N << /Yes 6 0 R /Off 7 0 R >> >> >>")
objs[12] = b"<< /FT /Btn /Ff 49152 /T (color) /V /Off /Kids [13 0 R 14 0 R] >>"
objs[13] = (b"<< /Type /Annot /Subtype /Widget /Parent 12 0 R /AS /Off /F 4 /P 3 0 R "
            b"/Rect [150 550 168 568] /AP << /N << /Red 6 0 R /Off 7 0 R >> >> >>")
objs[14] = (b"<< /Type /Annot /Subtype /Widget /Parent 12 0 R /AS /Off /F 4 /P 3 0 R "
            b"/Rect [190 550 208 568] /AP << /N << /Blue 6 0 R /Off 7 0 R >> >> >>")
objs[15] = (b"<< /Type /Annot /Subtype /Widget /FT /Ch /Ff 131072 /T (size) /V (Medium) "
            b"/Opt [(Small) (Medium) (Large)] /F 4 /P 3 0 R /Rect [150 500 300 522] /DA (/Helv 12 Tf 0 g) >>")
objs[20] = b"<< /Fields [10 0 R 11 0 R 12 0 R 15 0 R] /DA (/Helv 0 Tf 0 g) /DR << /Font << /Helv 5 0 R >> >> /NeedAppearances true >>"

out = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
offsets = {}
for num in sorted(objs):
    offsets[num] = len(out)
    out += f"{num} 0 obj\n".encode() + objs[num] + b"\nendobj\n"
size = max(objs) + 1
xref = len(out)
out += f"xref\n0 {size}\n0000000000 65535 f \n".encode()
for num in range(1, size):
    out += (f"{offsets[num]:010d} 00000 n \n" if num in offsets else "0000000000 65535 f \n").encode()
out += f"trailer\n<< /Size {size} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()

target = os.path.join(os.path.dirname(__file__), "../core/pdf/src/androidTest/assets/form.pdf")
with open(target, "wb") as f:
    f.write(out)
