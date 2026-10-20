package com.eve.robot

import java.util.LinkedHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

data class EveHomeMapSnapshot(
    val knownCells: Int,
    val freeCells: Int,
    val obstacleCells: Int,
    val namedRoomCells: Int,
    val currentRoom: EveRoomType,
    val observedAtMs: Long
)

/**
 * Small, local 2-D occupancy/room memory driven by a base-provided fused odometry pose.
 *
 * This is not a full SLAM implementation. It keeps a bounded grid in the current map frame,
 * ray-traces the base's four range sensors, records cautious visual room hypotheses, and chooses
 * a nearby frontier to explore. It deliberately does not infer metric position from phone pixels.
 */
class EveHomeMapMemory(
    private val cellSizeMm: Int = 250,
    private val maxCells: Int = 1_800
) {
    private data class Cell(val x: Int, val y: Int)
    private data class RoomVote(val counts: MutableMap<EveRoomType, Int> = mutableMapOf())

    private val occupancy = LinkedHashMap<Cell, Int>()
    private val roomVotes = LinkedHashMap<Cell, RoomVote>()
    private var activeFrontier: Cell? = null
    private var lastObservedAtMs = 0L
    private var currentRoom = EveRoomType.UNKNOWN

    init {
        require(cellSizeMm in 100..1000)
        require(maxCells in 100..20_000)
    }

    @Synchronized
    fun observe(
        pose: EveRobotBaseProtocol.Pose,
        telemetry: EveRobotBaseProtocol.Telemetry,
        room: EveRoomType,
        nowMs: Long
    ): EveHomeMapSnapshot? {
        if (!pose.isFresh(nowMs) || !telemetry.isFresh(nowMs) || !telemetry.hasCompleteSafetySensors()) return null
        if (nowMs < lastObservedAtMs) return null
        lastObservedAtMs = nowMs

        val position = cellFor(pose.xMm.toFloat(), pose.yMm.toFloat())
        updateOccupancy(position, +1)
        val heading = pose.headingDegrees
        traceRange(pose, heading, telemetry.frontDistanceMm)
        traceRange(pose, heading + 180f, telemetry.rearDistanceMm)
        traceRange(pose, heading + 90f, telemetry.leftDistanceMm)
        traceRange(pose, heading - 90f, telemetry.rightDistanceMm)

        if (room != EveRoomType.UNKNOWN) {
            val votes = roomVotes.getOrPut(position) { RoomVote() }
            votes.counts[room] = ((votes.counts[room] ?: 0) + 1).coerceAtMost(MAX_ROOM_VOTES)
            currentRoom = votes.counts.maxByOrNull { it.value }?.key ?: room
        } else {
            currentRoom = roomVotes[position]?.counts?.maxByOrNull { it.value }?.key ?: EveRoomType.UNKNOWN
        }

        activeFrontier?.let { target ->
            if (occupancy.containsKey(target)) activeFrontier = null
        }
        trimToBound()
        return snapshot(nowMs)
    }

    /** Returns a world-frame heading toward a nearby unexplored frontier, if one is known. */
    @Synchronized
    fun bestFrontierHeadingDegrees(pose: EveRobotBaseProtocol.Pose, nowMs: Long): Float? {
        if (!pose.isFresh(nowMs) || lastObservedAtMs == 0L || nowMs - lastObservedAtMs > MAP_FRESH_MS) {
            return null
        }
        val currentTarget = activeFrontier
        if (currentTarget != null && occupancy[currentTarget] == null) {
            return bearingToCell(pose, currentTarget)
        }
        activeFrontier = null

        val candidates = LinkedHashSet<Cell>()
        for ((cell, evidence) in occupancy) {
            if (evidence <= 0) continue
            for (neighbor in neighbors(cell)) {
                if (occupancy[neighbor] == null) candidates.add(neighbor)
            }
        }
        if (candidates.isEmpty()) return null

        // Prefer nearby frontiers but slightly favor continuing roughly forward to avoid jitter.
        val currentHeading = pose.headingDegrees
        val scored = candidates.mapNotNull { cell ->
            val dx = cell.x * cellSizeMm + cellSizeMm / 2f - pose.xMm
            val dy = cell.y * cellSizeMm + cellSizeMm / 2f - pose.yMm
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (distance < MIN_FRONTIER_DISTANCE_MM || distance > MAX_FRONTIER_DISTANCE_MM) return@mapNotNull null
            val bearing = normalizeDegrees(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
            val angularCost = kotlin.math.abs(signedAngleDelta(bearing, currentHeading)) * ANGLE_COST_MM_PER_DEGREE
            Triple(cell, distance + angularCost, bearing)
        }.sortedBy { it.second }

        val best = scored.firstOrNull() ?: return null
        activeFrontier = best.first
        return best.third
    }

    @Synchronized
    fun snapshot(nowMs: Long = lastObservedAtMs): EveHomeMapSnapshot {
        val free = occupancy.values.count { it > 0 }
        val obstacles = occupancy.values.count { it < 0 }
        val currentCell = occupancy.keys.lastOrNull()
        val room = currentCell?.let { roomVotes[it]?.counts?.maxByOrNull { vote -> vote.value }?.key }
            ?: currentRoom
        return EveHomeMapSnapshot(
            knownCells = occupancy.size,
            freeCells = free,
            obstacleCells = obstacles,
            namedRoomCells = roomVotes.size,
            currentRoom = room,
            observedAtMs = nowMs
        )
    }

    @Synchronized
    fun reset() {
        occupancy.clear()
        roomVotes.clear()
        activeFrontier = null
        lastObservedAtMs = 0L
        currentRoom = EveRoomType.UNKNOWN
    }

    private fun traceRange(
        pose: EveRobotBaseProtocol.Pose,
        bearingDegrees: Float,
        sensorRangeMm: Int?
    ) {
        val range = sensorRangeMm ?: return
        val boundedRange = range.coerceAtMost(MAX_RANGE_TO_MAP_MM)
        val radians = Math.toRadians(bearingDegrees.toDouble())
        var distance = cellSizeMm / 2
        while (distance < boundedRange - cellSizeMm / 2) {
            val x = pose.xMm + cos(radians) * distance
            val y = pose.yMm + sin(radians) * distance
            updateOccupancy(cellFor(x.toFloat(), y.toFloat()), +1)
            distance += (cellSizeMm / 2).coerceAtLeast(50)
        }
        if (range < MAX_RANGE_TO_MAP_MM && range > MIN_OBSTACLE_MAP_RANGE_MM) {
            val obstacleDistance = (range - SENSOR_TO_OBSTACLE_OFFSET_MM).coerceAtLeast(0)
            val x = pose.xMm + cos(radians) * obstacleDistance
            val y = pose.yMm + sin(radians) * obstacleDistance
            val cell = cellFor(x.toFloat(), y.toFloat())
            updateOccupancy(cell, -1)
            if (cell == activeFrontier) activeFrontier = null
        }
    }

    private fun updateOccupancy(cell: Cell, evidence: Int) {
        val previous = occupancy[cell] ?: 0
        // Conflicting samples soften the estimate instead of permanently painting a cell occupied.
        occupancy[cell] = (previous + evidence).coerceIn(-MAX_CELL_EVIDENCE, MAX_CELL_EVIDENCE)
    }

    private fun cellFor(xMm: Float, yMm: Float) = Cell(
        floor(xMm / cellSizeMm).toInt(),
        floor(yMm / cellSizeMm).toInt()
    )

    private fun neighbors(cell: Cell): List<Cell> = listOf(
        Cell(cell.x + 1, cell.y),
        Cell(cell.x - 1, cell.y),
        Cell(cell.x, cell.y + 1),
        Cell(cell.x, cell.y - 1)
    )

    private fun bearingToCell(pose: EveRobotBaseProtocol.Pose, cell: Cell): Float {
        val dx = cell.x * cellSizeMm + cellSizeMm / 2f - pose.xMm
        val dy = cell.y * cellSizeMm + cellSizeMm / 2f - pose.yMm
        return normalizeDegrees(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
    }

    private fun trimToBound() {
        while (occupancy.size > maxCells) {
            val oldest = occupancy.entries.firstOrNull()?.key ?: break
            occupancy.remove(oldest)
            roomVotes.remove(oldest)
            if (activeFrontier == oldest) activeFrontier = null
        }
    }

    private fun normalizeDegrees(angle: Float): Float {
        var value = angle % 360f
        if (value < 0f) value += 360f
        return value
    }

    private fun signedAngleDelta(target: Float, current: Float): Float {
        var delta = (target - current) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    companion object {
        const val MAP_FRESH_MS = 1_500L
        const val MAX_RANGE_TO_MAP_MM = 2_000
        const val MIN_OBSTACLE_MAP_RANGE_MM = 90
        const val SENSOR_TO_OBSTACLE_OFFSET_MM = 70
        const val MIN_FRONTIER_DISTANCE_MM = 120f
        const val MAX_FRONTIER_DISTANCE_MM = 4_000f
        const val ANGLE_COST_MM_PER_DEGREE = 3.2f
        const val MAX_CELL_EVIDENCE = 5
        const val MAX_ROOM_VOTES = 20
    }
}
