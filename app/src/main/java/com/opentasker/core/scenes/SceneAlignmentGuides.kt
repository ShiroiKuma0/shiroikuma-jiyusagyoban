package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement

data class AlignmentGuide(
    val orientation: GuideOrientation,
    val position: Float,
    val snappedPosition: Int,
)

enum class GuideOrientation { HORIZONTAL, VERTICAL }

object SceneAlignmentGuides {
    private const val SNAP_THRESHOLD_DP = 6

    fun findGuides(
        scene: Scene,
        movingIndex: Int,
        candidateX: Int,
        candidateY: Int,
        candidateW: Int,
        candidateH: Int,
    ): AlignmentResult {
        val anchors = mutableListOf<AnchorLine>()
        anchors += AnchorLine(GuideOrientation.VERTICAL, 0)
        anchors += AnchorLine(GuideOrientation.VERTICAL, scene.widthDp / 2)
        anchors += AnchorLine(GuideOrientation.VERTICAL, scene.widthDp)
        anchors += AnchorLine(GuideOrientation.HORIZONTAL, 0)
        anchors += AnchorLine(GuideOrientation.HORIZONTAL, scene.heightDp / 2)
        anchors += AnchorLine(GuideOrientation.HORIZONTAL, scene.heightDp)

        scene.elements.forEachIndexed { index, element ->
            if (index == movingIndex) return@forEachIndexed
            anchors += AnchorLine(GuideOrientation.VERTICAL, element.xDp)
            anchors += AnchorLine(GuideOrientation.VERTICAL, element.xDp + element.widthDp / 2)
            anchors += AnchorLine(GuideOrientation.VERTICAL, element.xDp + element.widthDp)
            anchors += AnchorLine(GuideOrientation.HORIZONTAL, element.yDp)
            anchors += AnchorLine(GuideOrientation.HORIZONTAL, element.yDp + element.heightDp / 2)
            anchors += AnchorLine(GuideOrientation.HORIZONTAL, element.yDp + element.heightDp)
        }

        val guides = mutableListOf<AlignmentGuide>()
        var snappedX = candidateX
        var snappedY = candidateY

        val movingEdgesX = listOf(candidateX, candidateX + candidateW / 2, candidateX + candidateW)
        val movingEdgesY = listOf(candidateY, candidateY + candidateH / 2, candidateY + candidateH)

        var bestXDist = SNAP_THRESHOLD_DP + 1
        var bestYDist = SNAP_THRESHOLD_DP + 1

        anchors.filter { it.orientation == GuideOrientation.VERTICAL }.forEach { anchor ->
            movingEdgesX.forEachIndexed { edgeIdx, edgePos ->
                val dist = kotlin.math.abs(edgePos - anchor.position)
                if (dist <= SNAP_THRESHOLD_DP && dist < bestXDist) {
                    bestXDist = dist
                    val offset = when (edgeIdx) {
                        0 -> 0
                        1 -> candidateW / 2
                        else -> candidateW
                    }
                    snappedX = anchor.position - offset
                    guides.removeAll { it.orientation == GuideOrientation.VERTICAL }
                    guides += AlignmentGuide(GuideOrientation.VERTICAL, anchor.position.toFloat(), anchor.position)
                }
            }
        }

        anchors.filter { it.orientation == GuideOrientation.HORIZONTAL }.forEach { anchor ->
            movingEdgesY.forEachIndexed { edgeIdx, edgePos ->
                val dist = kotlin.math.abs(edgePos - anchor.position)
                if (dist <= SNAP_THRESHOLD_DP && dist < bestYDist) {
                    bestYDist = dist
                    val offset = when (edgeIdx) {
                        0 -> 0
                        1 -> candidateH / 2
                        else -> candidateH
                    }
                    snappedY = anchor.position - offset
                    guides.removeAll { it.orientation == GuideOrientation.HORIZONTAL }
                    guides += AlignmentGuide(GuideOrientation.HORIZONTAL, anchor.position.toFloat(), anchor.position)
                }
            }
        }

        val maxX = (scene.widthDp - candidateW).coerceAtLeast(0)
        val maxY = (scene.heightDp - candidateH).coerceAtLeast(0)
        return AlignmentResult(
            snappedX = snappedX.coerceIn(0, maxX),
            snappedY = snappedY.coerceIn(0, maxY),
            guides = guides,
        )
    }

    private data class AnchorLine(
        val orientation: GuideOrientation,
        val position: Int,
    )
}

data class AlignmentResult(
    val snappedX: Int,
    val snappedY: Int,
    val guides: List<AlignmentGuide>,
)
