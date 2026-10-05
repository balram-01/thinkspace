import Foundation
import UIKit
import PDFKit

@objc public protocol SqueezeAccordionDelegate: AnyObject {
    func accordionDidSqueeze(squeezeRatio: CGFloat, isFolded: Bool)
}

/**
 * Accordion Squeeze Engine (LiquidText signature margin pinch).
 * Folds unannotated pages during a vertical 2-finger pinch on the document gutter,
 * bringing distant excerpts and highlights side-by-side.
 */
@objc public class SqueezeAccordionEngine: NSObject {

    @objc public weak var delegate: SqueezeAccordionDelegate?
    @objc public var isSqueezed: Bool = false
    @objc public var currentSqueezeRatio: CGFloat = 1.0

    private var initialPinchDistance: CGFloat = 0.0

    @objc public func handlePinch(gesture: UIPinchGestureRecognizer, in view: UIView) {
        switch gesture.state {
        case .began:
            initialPinchDistance = gesture.scale
        case .changed:
            let scale = gesture.scale
            currentSqueezeRatio = min(1.0, max(0.2, scale))
            let folded = currentSqueezeRatio < 0.65
            if isSqueezed != folded {
                isSqueezed = folded
            }
            delegate?.accordionDidSqueeze(squeezeRatio: currentSqueezeRatio, isFolded: isSqueezed)
        case .ended, .cancelled:
            if currentSqueezeRatio < 0.5 {
                isSqueezed = true
                currentSqueezeRatio = 0.35
            } else {
                isSqueezed = false
                currentSqueezeRatio = 1.0
            }
            delegate?.accordionDidSqueeze(squeezeRatio: currentSqueezeRatio, isFolded: isSqueezed)
        default:
            break
        }
    }
}
