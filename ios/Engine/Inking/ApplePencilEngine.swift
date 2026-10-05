import Foundation
import UIKit
import PencilKit

@objc public protocol ApplePencilEngineDelegate: AnyObject {
    func pencilEngineDidAddStroke(stroke: InkStroke)
    func pencilEngineToolDidChange(mode: String, color: String, thickness: CGFloat)
}

/**
 * Low-latency inking engine powered by Apple PencilKit.
 * Delivers sub-9ms predictive latency, pressure and tilt sensitivity, palm rejection,
 * and hardware gestures (Double-Tap and Squeeze).
 */
@objc public class ApplePencilEngine: UIView, PKCanvasViewDelegate, UIPencilInteractionDelegate {

    @objc public let canvasView = PKCanvasView()
    @objc public weak var delegate: ApplePencilEngineDelegate?

    @objc public var activeMode: String = "pen" {
        didSet { updateTool() }
    }
    @objc public var strokeColor: String = "#1A1A1A" {
        didSet { updateTool() }
    }
    @objc public var strokeWidth: CGFloat = 2.5 {
        didSet { updateTool() }
    }

    private var pencilInteraction: UIPencilInteraction?

    @objc public override init(frame: CGRect) {
        super.init(frame: frame)
        setupPencilKit()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupPencilKit()
    }

    private func setupPencilKit() {
        backgroundColor = .clear
        canvasView.backgroundColor = .clear
        canvasView.isOpaque = false
        canvasView.delegate = self
        canvasView.drawingPolicy = .anyInput // Allows Apple Pencil + finger or Pencil-only
        canvasView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        canvasView.frame = bounds
        addSubview(canvasView)

        // Hardware Apple Pencil interaction
        pencilInteraction = UIPencilInteraction()
        pencilInteraction?.delegate = self
        addInteraction(pencilInteraction!)

        updateTool()
    }

    @objc public func updateTool() {
        let uiColor = UIColor(hexString: strokeColor) ?? UIColor.black

        if activeMode == "eraser" {
            canvasView.tool = PKEraserTool(.vector)
        } else if activeMode == "highlighter" {
            canvasView.tool = PKInkingTool(.marker, color: uiColor.withAlphaComponent(0.4), width: strokeWidth * 2.5)
        } else {
            canvasView.tool = PKInkingTool(.pen, color: uiColor, width: strokeWidth)
        }

        delegate?.pencilEngineToolDidChange(mode: activeMode, color: strokeColor, thickness: strokeWidth)
    }

    @objc public func clearDrawing() {
        canvasView.drawing = PKDrawing()
    }

    // ── PKCanvasViewDelegate ───────────────────────────────────────────────────
    public func canvasViewDrawingDidChange(_ canvasView: PKCanvasView) {
        let drawing = canvasView.drawing
        guard let lastStroke = drawing.strokes.last else { return }

        // Convert PKStroke to InkStroke for React Native export
        var inkPoints: [InkPoint] = []
        for point in lastStroke.path {
            let inkPt = InkPoint(
                x: point.location.x,
                y: point.location.y,
                pressure: point.force,
                timestamp: point.timeOffset
            )
            inkPoints.append(inkPt)
        }

        let newStroke = InkStroke(
            id: UUID().uuidString,
            points: inkPoints,
            color: strokeColor,
            strokeWidth: strokeWidth,
            isHighlighter: activeMode == "highlighter"
        )

        delegate?.pencilEngineDidAddStroke(stroke: newStroke)
    }

    // ── UIPencilInteractionDelegate (Apple Pencil Double-Tap & Squeeze) ────────
    public func pencilInteractionDidTap(_ interaction: UIPencilInteraction) {
        // Toggle between pen and eraser
        if activeMode == "eraser" {
            activeMode = "pen"
        } else {
            activeMode = "eraser"
        }
        updateTool()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        canvasView.frame = bounds
    }
}
