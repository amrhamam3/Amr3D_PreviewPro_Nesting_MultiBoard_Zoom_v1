package com.amr3d.preview.pro

import kotlin.math.*

data class NestingPoint(val x: Double, val y: Double)

data class NestingPolygon(
    val outer: List<NestingPoint>,
    val holes: List<List<NestingPoint>> = emptyList()
)

data class NestingPiece(
    val index: Int,
    val polygon: NestingPolygon,
    val x: Double,
    val y: Double,
    val rotationDeg: Double,
    val boundsWidth: Double,
    val boundsHeight: Double
)

data class NestingBoard(
    val index: Int,
    val width: Double,
    val height: Double,
    val pieces: List<NestingPiece>,
    val color: Int = 0xFF0D0F14.toInt()
)

data class NestingResult(
    val boards: List<NestingBoard>,
    val totalRequested: Int,
    val totalPlaced: Int,
    val sourceWidth: Double,
    val sourceHeight: Double,
    val sourceArea: Double,
    val elapsedMs: Long
) {
    val boardArea: Double get() = boards.sumOf { it.width * it.height }
    val usedArea: Double get() = sourceArea * totalPlaced
    val utilization: Double get() = if (boardArea > 0.0) usedArea / boardArea * 100.0 else 0.0
    val wasteArea: Double get() = (boardArea - usedArea).coerceAtLeast(0.0)
}

data class NestingConfig(
    val boardWidth: Double = 1220.0,
    val boardHeight: Double = 2440.0,
    val copies: Int = 1,
    val rotationStepDeg: Double = 15.0,
    val rotationMode: RotationMode = RotationMode.FREE,
    val grainAxis: GrainAxis = GrainAxis.FREE,
    val grainDeviationDeg: Double = 10.0,
    val clearanceMm: Double = 0.0,
    val gridStepMm: Double = 12.0,
    val boardColor: Int = 0xFF0D0F14.toInt()
)

enum class RotationMode { FREE, HORIZONTAL, VERTICAL }
enum class GrainAxis { FREE, HORIZONTAL, VERTICAL }

data class NestingProgress(
    val placed: Int,
    val total: Int,
    val boardIndex: Int,
    val percent: Int
)

object NestingShapeBuilder {
    private const val EPS = 0.05

    fun fromModel(model: DxfModel): NestingPolygon? {
        val segments = mutableListOf<Pair<NestingPoint, NestingPoint>>()

        for (l in model.lines) {
            val a = NestingPoint(l.x1.toDouble(), l.y1.toDouble())
            val b = NestingPoint(l.x2.toDouble(), l.y2.toDouble())
            if (distance(a, b) > EPS) segments += a to b
        }

        // Curves are polygonized only for nesting; the original DXF remains untouched.
        for (c in model.circles) {
            val pts = circlePoints(c.cx.toDouble(), c.cy.toDouble(), c.r.toDouble(), 96)
            for (i in pts.indices) segments += pts[i] to pts[(i + 1) % pts.size]
        }
        for (a in model.arcs) {
            val span = normalizedSpan(a.startDeg.toDouble(), a.endDeg.toDouble())
            val steps = max(8, ceil(abs(span) / 7.5).toInt())
            val pts = (0..steps).map { i ->
                val d = a.startDeg + span * i / steps
                val r = Math.toRadians(d)
                NestingPoint(a.cx + a.r * cos(r), a.cy + a.r * sin(r))
            }
            for (i in 0 until pts.size - 1) segments += pts[i] to pts[i + 1]
        }

        if (segments.isEmpty()) return null

        val loops = traceFaces(segments)
            .map { cleanLoop(it) }
            .filter { it.size >= 3 && abs(signedArea(it)) > 0.01 }

        if (loops.isEmpty()) return null

        val outer = loops.maxByOrNull { abs(signedArea(it)) } ?: return null
        val holes = loops
            .filter { it !== outer && signedArea(it) * signedArea(outer) < 0.0 && pointInPolygon(it[0], outer) }
            .map { normalizeWinding(it, wantPositive = signedArea(outer) < 0.0) }

        val woundOuter = normalizeWinding(outer, true)
        val minX = woundOuter.minOf { it.x }
        val minY = woundOuter.minOf { it.y }
        val outerNorm = woundOuter.map { NestingPoint(it.x - minX, it.y - minY) }
        val holeNorm = holes.map { h ->
            h.map { NestingPoint(it.x - minX, it.y - minY) }
        }
        return NestingPolygon(outerNorm, holeNorm)
    }

    private fun traceFaces(segments: List<Pair<NestingPoint, NestingPoint>>): List<List<NestingPoint>> {
        val points = mutableListOf<NestingPoint>()
        fun pointId(p: NestingPoint): Int {
            val found = points.indexOfFirst { distance(it, p) <= EPS }
            if (found >= 0) return found
            points += p
            return points.lastIndex
        }

        data class Edge(val a: Int, val b: Int)
        val edges = segments.map { Edge(pointId(it.first), pointId(it.second)) }
        if (edges.isEmpty()) return emptyList()

        data class Half(val from: Int, val to: Int, val edge: Int)
        val half = mutableListOf<Half>()
        val outgoing = Array(points.size) { mutableListOf<Int>() }

        for ((ei, e) in edges.withIndex()) {
            val h0 = half.size
            half += Half(e.a, e.b, ei)
            half += Half(e.b, e.a, ei)
            outgoing[e.a] += h0
            outgoing[e.b] += h0 + 1
        }

        val order = outgoing.map { list ->
            list.sortedWith(compareBy { atan2(
                points[half[it].to].y - points[half[it].from].y,
                points[half[it].to].x - points[half[it].from].x
            ) })
        }

        val next = IntArray(half.size) { -1 }
        for (h in half.indices) {
            val v = half[h].to
            val list = order[v]
            val reverse = list.indexOfFirst { half[it].to == half[h].from }
            if (reverse >= 0) {
                next[h] = list[(reverse - 1 + list.size) % list.size]
            }
        }

        val visited = BooleanArray(half.size)
        val faces = mutableListOf<List<NestingPoint>>()
        for (start in half.indices) {
            if (visited[start] || next[start] < 0) continue
            val loop = mutableListOf<NestingPoint>()
            var h = start
            var guard = 0
            while (!visited[h] && guard++ < half.size + 4) {
                visited[h] = true
                loop += points[half[h].from]
                h = next[h]
                if (h == start) break
            }
            if (h == start && loop.size >= 3) faces += loop
        }
        return faces
    }

    private fun cleanLoop(loop: List<NestingPoint>): List<NestingPoint> {
        val out = mutableListOf<NestingPoint>()
        for (p in loop) if (out.isEmpty() || distance(out.last(), p) > EPS) out += p
        if (out.size > 1 && distance(out.first(), out.last()) <= EPS) out.removeAt(out.lastIndex)
        return out
    }

    private fun normalizeWinding(p: List<NestingPoint>, wantPositive: Boolean): List<NestingPoint> {
        val a = signedArea(p)
        return if ((a > 0) == wantPositive) p else p.asReversed()
    }

    private fun normalizeToOrigin(p: List<NestingPoint>): List<NestingPoint> {
        val minX = p.minOf { it.x }
        val minY = p.minOf { it.y }
        return p.map { NestingPoint(it.x - minX, it.y - minY) }
    }

    private fun circlePoints(cx: Double, cy: Double, r: Double, n: Int): List<NestingPoint> =
        (0 until n).map {
            val a = 2.0 * Math.PI * it / n
            NestingPoint(cx + r * cos(a), cy + r * sin(a))
        }

    private fun normalizedSpan(start: Double, end: Double): Double {
        var d = end - start
        while (d <= -360.0) d += 360.0
        while (d > 360.0) d -= 360.0
        return d
    }

    private fun signedArea(p: List<NestingPoint>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return s * 0.5
    }

    private fun distance(a: NestingPoint, b: NestingPoint) = hypot(a.x - b.x, a.y - b.y)
    private fun pointInPolygon(p: NestingPoint, poly: List<NestingPoint>): Boolean {
        var inside = false
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < x) inside = !inside
            }
        }
        return inside
    }
}

object NestingEngine {
    private const val EPS = 1e-7

    fun nest(
        shape: NestingPolygon,
        config: NestingConfig,
        onProgress: (NestingProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): NestingResult {
        val start = System.currentTimeMillis()
        val copies = config.copies.coerceAtLeast(1)
        val area = abs(area(shape.outer)) - shape.holes.sumOf { abs(area(it)) }
        val boards = mutableListOf<NestingBoard>()
        var remaining = copies
        var pieceIndex = 1

        while (remaining > 0 && !isCancelled()) {
            val placed = mutableListOf<NestingPiece>()
            val boardIndex = boards.size + 1

            for (i in 0 until remaining) {
                if (isCancelled()) break
                val best = findBestPlacement(shape, placed, config)
                if (best == null) break
                placed += best.copy(index = pieceIndex++)
                remaining--
                val done = copies - remaining
                val pct = (done * 100 / copies).coerceIn(0, 100)
                onProgress(NestingProgress(done, copies, boardIndex, pct))
            }

            if (placed.isEmpty()) break
            boards += NestingBoard(boardIndex, config.boardWidth, config.boardHeight, placed, config.boardColor)
        }

        val totalPlaced = boards.sumOf { it.pieces.size }
        return NestingResult(
            boards = boards,
            totalRequested = copies,
            totalPlaced = totalPlaced,
            sourceWidth = bounds(shape.outer).w,
            sourceHeight = bounds(shape.outer).h,
            sourceArea = area.coerceAtLeast(0.0),
            elapsedMs = System.currentTimeMillis() - start
        )
    }

    private fun findBestPlacement(
        shape: NestingPolygon,
        placed: List<NestingPiece>,
        config: NestingConfig
    ): NestingPiece? {
        val rotations = allowedRotations(config)
        val candidates = ArrayList<NestingPiece>(256)

        // First piece: test every allowed rotation and choose the smallest bounding box.
        if (placed.isEmpty()) {
            for (r in rotations) {
                val poly = transformed(shape.outer, r, 0.0, 0.0)
                val b = bounds(poly)
                if (b.w <= config.boardWidth + EPS && b.h <= config.boardHeight + EPS) {
                    candidates += NestingPiece(0, shape, -b.minX, -b.minY, r, b.w, b.h)
                }
            }
            return candidates.minWithOrNull(compareBy<NestingPiece> { it.boundsHeight * it.boundsWidth }
                .thenBy { it.y }.thenBy { it.x })
        }

        // Bottom-left candidate generation from existing piece bounds/edges.
        // It is intentionally bounded: no full board raster scan.
        val step = config.gridStepMm.coerceIn(1.0, 100.0)
        val xs = mutableListOf<Double>()
        val ys = mutableListOf<Double>()
        xs += 0.0; ys += 0.0
        for (p in placed) {
            val pb = placedBounds(p)
            xs += pb.maxX
            xs += max(0.0, pb.minX - step)
            ys += pb.maxY
            ys += max(0.0, pb.minY - step)
        }

        for (r in rotations) {
            val raw = transformed(shape.outer, r, 0.0, 0.0)
            val b0 = bounds(raw)
            if (b0.w > config.boardWidth || b0.h > config.boardHeight) continue

            // Try x/y coordinates near existing geometry, then refine toward bottom-left.
            for (x0 in xs.distinct()) {
                for (y0 in ys.distinct()) {
                    val x = x0 - b0.minX
                    val y = y0 - b0.minY
                    val candidate = NestingPiece(0, shape, x, y, r, b0.w, b0.h)
                    if (!insideBoard(candidate, config)) continue
                    if (overlapsAny(candidate, placed, config.clearanceMm)) continue
                    candidates += candidate
                }
            }
        }

        // Fallback bounded grid. This guarantees progress when edge candidates miss a valid slot.
        if (candidates.isEmpty()) {
            for (r in rotations) {
                val raw = transformed(shape.outer, r, 0.0, 0.0)
                val b0 = bounds(raw)
                if (b0.w > config.boardWidth || b0.h > config.boardHeight) continue
                var y = 0.0
                while (y + b0.h <= config.boardHeight + EPS) {
                    var x = 0.0
                    while (x + b0.w <= config.boardWidth + EPS) {
                        val c = NestingPiece(0, shape, x - b0.minX, y - b0.minY, r, b0.w, b0.h)
                        if (!overlapsAny(c, placed, config.clearanceMm)) {
                            candidates += c
                            break
                        }
                        x += step
                    }
                    if (candidates.isNotEmpty()) break
                    y += step
                }
            }
        }

        return candidates.minWithOrNull(
            compareBy<NestingPiece> { it.y + it.boundsHeight * 0.0001 }
                .thenBy { it.x }
                .thenBy { it.rotationDeg }
        )
    }

    private fun allowedRotations(c: NestingConfig): List<Double> {
        val step = c.rotationStepDeg.coerceIn(1.0, 90.0)
        val base = when (c.rotationMode) {
            RotationMode.HORIZONTAL -> listOf(0.0, 180.0)
            RotationMode.VERTICAL -> listOf(90.0, 270.0)
            RotationMode.FREE -> (0 until ceil(360.0 / step).toInt()).map { it * step }
        }
        return base.filter { r ->
            when (c.grainAxis) {
                GrainAxis.FREE -> true
                GrainAxis.HORIZONTAL -> angularDistanceToAxis(r, 0.0) <= c.grainDeviationDeg
                GrainAxis.VERTICAL -> angularDistanceToAxis(r, 90.0) <= c.grainDeviationDeg
            }
        }.ifEmpty { listOf(0.0) }
    }

    private fun angularDistanceToAxis(a: Double, axis: Double): Double {
        var d = abs(((a - axis + 180.0) % 360.0 + 360.0) % 360.0 - 180.0)
        d = min(d, abs(d - 180.0))
        return d
    }

    private fun insideBoard(p: NestingPiece, c: NestingConfig): Boolean {
        val poly = translated(transformed(p.polygon.outer, p.rotationDeg, 0.0, 0.0), p.x, p.y)
        val b = bounds(poly)
        return b.minX >= -EPS && b.minY >= -EPS &&
            b.maxX <= c.boardWidth + EPS && b.maxY <= c.boardHeight + EPS
    }

    private fun overlapsAny(p: NestingPiece, others: List<NestingPiece>, clearance: Double): Boolean {
        val pa = translated(transformed(p.polygon.outer, p.rotationDeg, 0.0, 0.0), p.x, p.y)
        for (o in others) {
            val pb = translated(transformed(o.polygon.outer, o.rotationDeg, 0.0, 0.0), o.x, o.y)
            if (polygonsOverlap(pa, pb, clearance)) return true
        }
        return false
    }

    private fun polygonsOverlap(a: List<NestingPoint>, b: List<NestingPoint>, clearance: Double): Boolean {
        val ba = bounds(a); val bb = bounds(b)
        val pad = max(0.0, clearance)
        if (ba.maxX < bb.minX - pad || bb.maxX < ba.minX - pad ||
            ba.maxY < bb.minY - pad || bb.maxY < ba.minY - pad) return false

        if (clearance > 0.0) {
            // Clearance is a collision envelope only; original geometry is never modified.
            // Use a conservative segment distance test in addition to true polygon overlap.
            if (minSegmentDistance(a, b) < clearance - EPS) return true
        }

        for (i in a.indices) {
            val a1 = a[i]; val a2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                val b1 = b[j]; val b2 = b[(j + 1) % b.size]
                if (properCross(a1, a2, b1, b2)) return true
                if (collinearPositiveOverlap(a1, a2, b1, b2)) return true
            }
        }
        for (p in a) if (pointInStrict(p, b)) return true
        for (p in b) if (pointInStrict(p, a)) return true

        // Exact coincidence: every vertex can lie on the other boundary.
        if (abs(abs(area(a)) - abs(area(b))) <= max(1.0, abs(area(a))) * 1e-9) {
            val ca = centroid(a); val cb = centroid(b)
            if (hypot(ca.x - cb.x, ca.y - cb.y) <= 1e-6) return true
        }
        return false
    }

    private fun properCross(a1:NestingPoint,a2:NestingPoint,b1:NestingPoint,b2:NestingPoint):Boolean {
        val c1=cross(a1,a2,b1); val c2=cross(a1,a2,b2)
        val c3=cross(b1,b2,a1); val c4=cross(b1,b2,a2)
        return ((c1>EPS&&c2<-EPS)||(c1<-EPS&&c2>EPS)) &&
               ((c3>EPS&&c4<-EPS)||(c3<-EPS&&c4>EPS))
    }

    private fun collinearPositiveOverlap(a1:NestingPoint,a2:NestingPoint,b1:NestingPoint,b2:NestingPoint):Boolean {
        val c1=cross(a1,a2,b1); val c2=cross(a1,a2,b2)
        val c3=cross(b1,b2,a1); val c4=cross(b1,b2,a2)
        if (abs(c1)>EPS||abs(c2)>EPS||abs(c3)>EPS||abs(c4)>EPS) return false
        val dx=abs(a2.x-a1.x); val dy=abs(a2.y-a1.y)
        val overlap=if(dx>=dy)
            min(max(a1.x,a2.x),max(b1.x,b2.x))-max(min(a1.x,a2.x),min(b1.x,b2.x))
        else
            min(max(a1.y,a2.y),max(b1.y,b2.y))-max(min(a1.y,a2.y),min(b1.y,b2.y))
        return overlap>EPS
    }

    private fun pointInStrict(p:NestingPoint, poly:List<NestingPoint>):Boolean {
        var inside=false
        for(i in poly.indices){
            val a=poly[i]; val b=poly[(i+1)%poly.size]
            if(onSegment(a,b,p)) return false
            if((a.y>p.y)!=(b.y>p.y) && p.x < (b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x) inside=!inside
        }
        return inside
    }

    private fun minSegmentDistance(a:List<NestingPoint>,b:List<NestingPoint>):Double {
        var best=Double.POSITIVE_INFINITY
        for(i in a.indices){
            val a1=a[i];val a2=a[(i+1)%a.size]
            for(j in b.indices){
                val d=segmentDistance(a1,a2,b[j],b[(j+1)%b.size])
                if(d<best) best=d
            }
        }
        return best
    }

    private fun segmentDistance(a:NestingPoint,b:NestingPoint,c:NestingPoint,d:NestingPoint):Double {
        if(properCross(a,b,c,d) || onSegment(a,b,c) || onSegment(a,b,d) || onSegment(c,d,a) || onSegment(c,d,b)) return 0.0
        return min(min(pointSegmentDistance(a,c,d),pointSegmentDistance(b,c,d)),
            min(pointSegmentDistance(c,a,b),pointSegmentDistance(d,a,b)))
    }

    private fun pointSegmentDistance(p:NestingPoint,a:NestingPoint,b:NestingPoint):Double {
        val dx=b.x-a.x;val dy=b.y-a.y
        val t=((p.x-a.x)*dx+(p.y-a.y)*dy)/(dx*dx+dy*dy).coerceAtLeast(EPS)
        val u=t.coerceIn(0.0,1.0)
        return hypot(p.x-(a.x+u*dx),p.y-(a.y+u*dy))
    }

    private fun cross(a:NestingPoint,b:NestingPoint,p:NestingPoint):Double =
        (b.x-a.x)*(p.y-a.y) - (b.y-a.y)*(p.x-a.x)

    private fun onSegment(a:NestingPoint,b:NestingPoint,p:NestingPoint):Boolean {
        return abs(cross(a,b,p))<=EPS &&
            p.x>=min(a.x,b.x)-EPS && p.x<=max(a.x,b.x)+EPS &&
            p.y>=min(a.y,b.y)-EPS && p.y<=max(a.y,b.y)+EPS
    }

    private fun transformed(poly:List<NestingPoint>,deg:Double,tx:Double,ty:Double):List<NestingPoint>{
        val r=Math.toRadians(deg);val c=cos(r);val s=sin(r)
        return poly.map{NestingPoint(it.x*c-it.y*s+tx,it.x*s+it.y*c+ty)}
    }
    private fun translated(poly:List<NestingPoint>,x:Double,y:Double)=poly.map{NestingPoint(it.x+x,it.y+y)}

    private data class B(val minX:Double,val minY:Double,val maxX:Double,val maxY:Double){
        val w get()=maxX-minX
        val h get()=maxY-minY
    }
    private fun bounds(p:List<NestingPoint>):B = B(p.minOf{it.x},p.minOf{it.y},p.maxOf{it.x},p.maxOf{it.y})
    private fun placedBounds(p:NestingPiece):B {
        val poly=translated(transformed(p.polygon.outer,p.rotationDeg,0.0,0.0),p.x,p.y)
        return bounds(poly)
    }
    private fun area(p:List<NestingPoint>):Double {
        var s=0.0
        for(i in p.indices){val a=p[i];val b=p[(i+1)%p.size];s+=a.x*b.y-b.x*a.y}
        return s*0.5
    }
    private fun centroid(p:List<NestingPoint>):NestingPoint {
        val a=area(p)
        if(abs(a)<EPS) return NestingPoint(
            p.sumOf { it.x } / p.size,
            p.sumOf { it.y } / p.size
        )
        var cx=0.0;var cy=0.0
        for(i in p.indices){val q=p[i];val r=p[(i+1)%p.size];val k=q.x*r.y-r.x*q.y;cx+=(q.x+r.x)*k;cy+=(q.y+r.y)*k}
        return NestingPoint(cx/(6*a),cy/(6*a))
    }
}
