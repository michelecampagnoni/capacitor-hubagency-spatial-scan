package it.hubagency.spatialscan

import kotlin.math.*

class WallDetector {

    fun extractWalls(planes: List<PlaneData>): List<Wall> {
        return planes
            .filter { it.isWall() }
            .filter { it.area > 0.2f }
            .filter { it.extentX > 0.3f }
            .mapNotNull { extractWall(it) }
    }

    private fun extractWall(plane: PlaneData): Wall? {
        return try {
            val pose = plane.centerPose
            val centerX = pose.tx()
            val centerY = pose.ty()
            val centerZ = pose.tz()

            val length = plane.extentX.toDouble()
            val height = plane.extentZ.toDouble()

            val localAxisX = floatArrayOf(1f, 0f, 0f, 0f)
            val worldAxisX = pose.rotateVector(localAxisX)

            val halfLen = (length / 2).toFloat()
            val startX = centerX - halfLen * worldAxisX[0]
            val startZ = centerZ - halfLen * worldAxisX[2]
            val endX = centerX + halfLen * worldAxisX[0]
            val endZ = centerZ + halfLen * worldAxisX[2]

            val wallBaseY = centerY - height / 2.0

            val localNormal = floatArrayOf(0f, 0f, 1f, 0f)
            val worldNormal = pose.rotateVector(localNormal)

            val confidence = calculateConfidence(plane)

            Wall(
                id = plane.id.toString(),
                startPoint = Point3D(startX.toDouble(), wallBaseY, startZ.toDouble()),
                endPoint = Point3D(endX.toDouble(), wallBaseY, endZ.toDouble()),
                length = length,
                height = height,
                normal = Vector3D(worldNormal[0].toDouble(), worldNormal[1].toDouble(), worldNormal[2].toDouble()),
                confidence = confidence
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateConfidence(plane: PlaneData): Double {
        val areaScore = (plane.area / 4.0).coerceIn(0.0, 0.6)
        val ratioScore = if (plane.extentX > 0.5f && plane.extentZ > 1.5f) 0.4 else 0.2
        return (areaScore + ratioScore).coerceIn(0.0, 1.0)
    }

    fun buildRoomOutline(walls: List<Wall>): List<Point3D> {
        if (walls.isEmpty()) return emptyList()
        val points = walls.flatMap { wall ->
            listOf(Point2D(wall.startPoint.x, wall.startPoint.z), Point2D(wall.endPoint.x, wall.endPoint.z))
        }
        return convexHull(points).map { p -> Point3D(p.x, 0.0, p.y) }
    }

    fun calculateRoomDimensions(walls: List<Wall>, floorArea: Double): RoomDimensions {
        if (walls.isEmpty()) return RoomDimensions(0.0, 0.0, 0.0, 0.0, 0.0)

        val allX = walls.flatMap { listOf(it.startPoint.x, it.endPoint.x) }
        val allZ = walls.flatMap { listOf(it.startPoint.z, it.endPoint.z) }

        val width = (allX.maxOrNull()!! - allX.minOrNull()!!).coerceAtLeast(0.0)
        val length = (allZ.maxOrNull()!! - allZ.minOrNull()!!).coerceAtLeast(0.0)
        val height = walls.maxOfOrNull { it.height } ?: 2.5
        val area = if (floorArea > 0) floorArea else width * length
        val perimeter = walls.sumOf { it.length } * 0.5

        return RoomDimensions(width, length, height, area, perimeter)
    }

    private data class Point2D(val x: Double, val y: Double)

    private fun convexHull(points: List<Point2D>): List<Point2D> {
        if (points.size <= 2) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = mutableListOf<Point2D>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower.add(p)
        }
        val upper = mutableListOf<Point2D>()
        for (p in sorted.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: Point2D, a: Point2D, b: Point2D) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}

data class Wall(val id: String, val startPoint: Point3D, val endPoint: Point3D, val length: Double, val height: Double, val normal: Vector3D, val confidence: Double)
data class Point3D(val x: Double, val y: Double, val z: Double)
data class Vector3D(val x: Double, val y: Double, val z: Double)
data class RoomDimensions(val width: Double, val length: Double, val height: Double, val area: Double, val perimeter: Double)
