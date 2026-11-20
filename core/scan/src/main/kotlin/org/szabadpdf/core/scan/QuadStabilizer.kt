/*
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 The SzabadPDF authors
 *
 * Adapted from FairScan (https://github.com/pynicolas/FairScan).
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.szabadpdf.core.scan

/**
 * Only reports a live outline once it has held still for a few frames, so the overlay
 * doesn't flicker while the phone moves.
 */
class QuadStabilizer {
    private var stableCount = 0
    private var last: Corners? = null

    fun update(raw: Corners?): Corners? {
        val previous = last
        last = raw
        if (raw == null || previous == null) {
            stableCount = if (raw == null) 0 else 1
            return null
        }
        // 0.03 of the frame is about 20 px on a 640x480 preview, FairScan's threshold.
        stableCount = if (previous.maxDistanceTo(raw) < 0.03f) stableCount + 1 else 1
        return if (stableCount >= 3) raw else null
    }

    fun reset() {
        stableCount = 0
        last = null
    }
}
