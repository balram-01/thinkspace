import Foundation
import CoreGraphics

/**
 * Encapsulates the 2D infinite canvas camera viewport state and zero-allocation coordinate transforms.
 * Optimized for 120 FPS ProMotion draw and gesture pipelines.
 */
@objc public class CameraTransform: NSObject {
    @objc public var panX: CGFloat
    @objc public var panY: CGFloat
    @objc public var scale: CGFloat
    @objc public let minScale: CGFloat
    @objc public let maxScale: CGFloat

    @objc public init(
        initialPanX: CGFloat = 0,
        initialPanY: CGFloat = 0,
        initialScale: CGFloat = 1.0,
        minScale: CGFloat = 0.1,
        maxScale: CGFloat = 5.0
    ) {
        self.panX = initialPanX
        self.panY = initialPanY
        self.scale = initialScale
        self.minScale = minScale
        self.maxScale = maxScale
        super.init()
    }

    @objc public func screenToWorldX(_ sx: CGFloat) -> CGFloat {
        return (sx - panX) / scale
    }

    @objc public func screenToWorldY(_ sy: CGFloat, canvasTopY: CGFloat = 0) -> CGFloat {
        return (sy - canvasTopY - panY) / scale
    }

    @objc public func worldToScreenX(_ wx: CGFloat) -> CGFloat {
        return wx * scale + panX
    }

    @objc public func worldToScreenY(_ wy: CGFloat, canvasTopY: CGFloat = 0) -> CGFloat {
        return wy * scale + panY + canvasTopY
    }

    @objc public func screenToWorld(_ screenPoint: CGPoint, canvasTopY: CGFloat = 0) -> CGPoint {
        return CGPoint(
            x: screenToWorldX(screenPoint.x),
            y: screenToWorldY(screenPoint.y, canvasTopY: canvasTopY)
        )
    }

    @objc public func worldToScreen(_ worldPoint: CGPoint, canvasTopY: CGFloat = 0) -> CGPoint {
        return CGPoint(
            x: worldToScreenX(worldPoint.x),
            y: worldToScreenY(worldPoint.y, canvasTopY: canvasTopY)
        )
    }

    @objc public func applyPinch(focalPoint: CGPoint, zoomFactor: CGFloat) {
        let newScale = min(maxScale, max(minScale, scale * zoomFactor))
        let ratio = newScale / scale
        panX = focalPoint.x - (focalPoint.x - panX) * ratio
        panY = focalPoint.y - (focalPoint.y - panY) * ratio
        scale = newScale
    }

    @objc public func applyPan(dx: CGFloat, dy: CGFloat) {
        panX += dx
        panY += dy
    }

    @objc public func reset() {
        panX = 0
        panY = 0
        scale = 1.0
    }
}
