package com.amr3d.preview.pro

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.floor

/**
 * Unit tests + benchmark for NestingEngine, focused on square/rectangular
 * MDF panels (cabinet sides, shelves, drawer fronts, etc).
 *
 * Run with:
 *   ./gradlew testDebugUnitTest --tests "com.amr3d.preview.pro.NestingEngineTest"
 *
 * No emulator/device needed — pure JVM.
 */
class NestingEngineTest {

    /**
     * ============================================================
     *  عدّل هنا فقط — مقاساتك الخاصة للاختبار
     * ============================================================
     * label   : اسم وصفي يظهر في نتيجة التقرير
     * w, h    : عرض وارتفاع القطعة بالمليمتر
     * copies  : عدد النسخ المطلوب اختبارها
     * boardW  : عرض البورد (mm)
     * boardH  : ارتفاع البورد (mm)
     * clearance: المسافة بين القطع (mm) — 0 لو عايز تلاصق تام
     *
     * ضيف/احذف/عدّل أي سطر هنا بحرية، مفيش أي تعديل لازم في باقي الملف.
     */
    private data class PanelCase(
        val label: String,
        val w: Double,
        val h: Double,
        val copies: Int,
        val boardW: Double = 1220.0,
        val boardH: Double = 2440.0,
        val clearance: Double = 3.0
    )

    private val customCases = listOf(
        PanelCase(label = "قطعة 1", w = 500.0, h = 500.0, copies = 10),
        PanelCase(label = "قطعة 2", w = 900.0, h = 450.0, copies = 15),
        // مثال ببورد مختلف الحجم:
        // PanelCase(label = "قطعة 3", w = 1000.0, h = 1000.0, copies = 5, boardW = 1830.0, boardH = 3660.0),
    )
    // ============================================================

    // ---------- Helpers ----------

    /** Builds a simple axis-aligned rectangle polygon of the given size (mm). */
    private fun rect(w: Double, h: Double): NestingPolygon {
        return NestingPolygon(
            outer = listOf(
                NestingPoint(0.0, 0.0),
                NestingPoint(w, 0.0),
                NestingPoint(w, h),
                NestingPoint(0.0, h)
            )
        )
    }

    /** Theoretical max copies of (pw x ph) that fit an axis-aligned grid on (bw x bh), no rotation. */
    private fun theoreticalMaxNoRotation(pw: Double, ph: Double, bw: Double, bh: Double): Int {
        val cols = floor(bw / pw).toInt()
        val rows = floor(bh / ph).toInt()
        return (cols * rows).coerceAtLeast(0)
    }

    // ---------- Correctness tests ----------

    @Test
    fun `simple square places at least one copy on a standard board`() {
        val shape = rect(400.0, 400.0)
        val config = NestingConfig(
            boardWidth = 1220.0,
            boardHeight = 2440.0,
            copies = 1,
            rotationMode = RotationMode.HORIZONTAL,
            clearanceMm = 0.0
        )
        val result = NestingEngine.nest(shape, config)
        assertEquals(1, result.totalPlaced)
        assertEquals(1, result.boards.size)
    }

    @Test
    fun `rectangle does not overlap itself when multiple copies requested`() {
        val shape = rect(300.0, 600.0)
        val config = NestingConfig(
            boardWidth = 1220.0,
            boardHeight = 2440.0,
            copies = 10,
            rotationMode = RotationMode.HORIZONTAL,
            clearanceMm = 3.0
        )
        val result = NestingEngine.nest(shape, config)
        val allPieces = result.boards.flatMap { board -> board.pieces.map { board to it } }

        for (i in allPieces.indices) {
            for (j in i + 1 until allPieces.size) {
                val (boardA, pieceA) = allPieces[i]
                val (boardB, pieceB) = allPieces[j]
                if (boardA !== boardB) continue // different boards can't overlap physically
                assertFalse(
                    "Pieces ${pieceA.index} and ${pieceB.index} overlap on board ${boardA.index}",
                    rectsOverlap(pieceA, pieceB)
                )
            }
        }
    }

    @Test
    fun `oversized piece is rejected instead of crashing`() {
        val shape = rect(2000.0, 3000.0) // bigger than any board
        val config = NestingConfig(
            boardWidth = 1220.0,
            boardHeight = 2440.0,
            copies = 5,
            rotationMode = RotationMode.FREE
        )
        val result = NestingEngine.nest(shape, config)
        assertEquals(0, result.totalPlaced)
        assertTrue(result.boards.isEmpty())
    }

    @Test
    fun `zero clearance allows touching pieces without false collision`() {
        val shape = rect(610.0, 1220.0) // exactly half a standard 1220x2440 board
        val config = NestingConfig(
            boardWidth = 1220.0,
            boardHeight = 2440.0,
            copies = 4, // should tile perfectly 2x2 with zero clearance
            rotationMode = RotationMode.HORIZONTAL,
            clearanceMm = 0.0
        )
        val result = NestingEngine.nest(shape, config)

        // Diagnostic: print exact placement of every piece so a failure is traceable.
        println("\n=== zero-clearance diagnostic ===")
        println("Placed: ${result.totalPlaced} / requested: 4, boards: ${result.boards.size}")
        for (board in result.boards) {
            println("Board ${board.index} (${board.width}x${board.height}):")
            for (p in board.pieces) {
                println("  piece #${p.index} at x=${p.x}, y=${p.y}, rot=${p.rotationDeg}, bounds=${p.boundsWidth}x${p.boundsHeight}")
            }
        }
        println("=== end diagnostic ===\n")

        assertEquals("Expected all 4 pieces to fit with zero clearance", 4, result.totalPlaced)
        assertEquals(1, result.boards.size)
    }

    // ---------- Benchmark (prints utilization/time, always passes) ----------

    @Test
    fun `benchmark custom panel sizes`() {
        println("\n=== MDF Panel Nesting Benchmark (custom sizes) ===")
        println("%-16s %10s %10s %10s %10s %12s".format("Case", "Board", "Placed", "Boards", "Util%", "Time(ms)"))

        for (case in customCases) {
            val shape = rect(case.w, case.h)
            val config = NestingConfig(
                boardWidth = case.boardW,
                boardHeight = case.boardH,
                copies = case.copies,
                rotationMode = RotationMode.FREE,
                rotationStepDeg = 90.0, // rectangles only need 0/90 for a fair axis-aligned baseline
                clearanceMm = case.clearance,
                gridStepMm = 10.0
            )
            val start = System.currentTimeMillis()
            val result = NestingEngine.nest(shape, config)
            val elapsed = System.currentTimeMillis() - start

            val theoreticalPerBoard = theoreticalMaxNoRotation(case.w, case.h, case.boardW, case.boardH)
            val boardLabel = "${case.boardW.toInt()}x${case.boardH.toInt()}"

            println(
                "%-16s %10s %10d %10d %9.1f%% %10dms".format(
                    case.label, boardLabel, result.totalPlaced, result.boards.size, result.utilization, elapsed
                )
            )
            println(
                "   -> مقاس القطعة: ${case.w.toInt()}x${case.h.toInt()} mm | نظريًا لكل بورد (بدون دوران/فراغ): $theoreticalPerBoard"
            )

            // Sanity floor: engine should place at least something for any case that fits at all.
            if (theoreticalPerBoard > 0) {
                assertTrue(
                    "Engine placed 0 pieces for '${case.label}' though at least $theoreticalPerBoard should fit",
                    result.totalPlaced > 0
                )
            }
        }
        println("=== End benchmark ===\n")
    }

    // ---------- Overlap check helper (axis-aligned bounding check, safe for 0/90/180/270 rotations) ----------

    private fun rectsOverlap(a: NestingPiece, b: NestingPiece): Boolean {
        fun bounds(p: NestingPiece): DoubleArray {
            val rad = Math.toRadians(p.rotationDeg)
            val c = kotlin.math.cos(rad)
            val s = kotlin.math.sin(rad)
            val xs = p.polygon.outer.map { it.x * c - it.y * s + p.x }
            val ys = p.polygon.outer.map { it.x * s + it.y * c + p.y }
            return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        }
        val ba = bounds(a)
        val bb = bounds(b)
        val eps = 1e-6
        return !(ba[2] <= bb[0] + eps || bb[2] <= ba[0] + eps || ba[3] <= bb[1] + eps || bb[3] <= ba[1] + eps)
    }
}
