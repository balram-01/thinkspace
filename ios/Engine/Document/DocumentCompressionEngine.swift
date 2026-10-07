import Foundation
import UIKit
import CoreGraphics

@objc public class PageCompressionState: NSObject {
    @objc public let pageIndex: Int
    @objc public var compressionFactor: CGFloat = 1.0
    @objc public var targetCompressionFactor: CGFloat = 1.0
    @objc public var hasSearchMatch: Bool = false
    @objc public var matchCount: Int = 0
    @objc public var hasAnnotations: Bool = false
    @objc public var annotationColors: [String] = []

    @objc public init(pageIndex: Int) {
        self.pageIndex = pageIndex
        super.init()
    }
}

/**
 * Real PDF Document Compression Engine (LiquidText style).
 * Dynamically calculates displayed page heights and positions for all PDF pages.
 * Supports Search-driven auto-compression, highlight-preserving pinch, and two-finger manual pinch compression.
 */
@objc public class DocumentCompressionEngine: NSObject {

    @objc public let minCompressionFactor: CGFloat = 0.08
    @objc public let maxCompressionFactor: CGFloat = 1.0

    private var pageStates: [PageCompressionState] = []
    private var displayLink: CADisplayLink?
    private var animStartTime: TimeInterval = 0
    private var animDuration: TimeInterval = 0.28
    private var startFactors: [CGFloat] = []
    private var targetFactors: [CGFloat] = []
    private var animUpdateCallback: (() -> Void)?

    // Manual two-finger pinch compression
    @objc public private(set) var isManualPinching: Bool = false
    private var pinchAnchorTopPageIndex: Int = -1
    private var pinchAnchorBottomPageIndex: Int = -1
    private var isDocumentWideHighlightPinch: Bool = false
    private var pinchInitialFingerDistance: CGFloat = 0
    private var pinchInitialFactors: [Int: CGFloat] = [:]

    @objc public func ensurePageCount(_ count: Int) {
        if pageStates.count == count { return }
        pageStates.removeAll()
        for i in 0..<count {
            pageStates.append(PageCompressionState(pageIndex: i))
        }
    }

    @objc public func getPageCount() -> Int {
        pageStates.count
    }

    @objc public func getCompressionFactor(pageIndex: Int) -> CGFloat {
        guard pageIndex >= 0 && pageIndex < pageStates.count else { return 1.0 }
        return pageStates[pageIndex].compressionFactor
    }

    @objc public func isPageCompressed(pageIndex: Int) -> Bool {
        return getCompressionFactor(pageIndex: pageIndex) < 0.55
    }

    @objc public func hasSearchMatch(pageIndex: Int) -> Bool {
        guard pageIndex >= 0 && pageIndex < pageStates.count else { return false }
        return pageStates[pageIndex].hasSearchMatch
    }

    @objc public func hasAnnotations(pageIndex: Int) -> Bool {
        guard pageIndex >= 0 && pageIndex < pageStates.count else { return false }
        return pageStates[pageIndex].hasAnnotations
    }

    @objc public func isAnyPageCompressed() -> Bool {
        return pageStates.contains(where: { $0.compressionFactor < 0.85 })
    }

    @objc public func updateAnnotationData(annotatedPageIndices: Set<Int>, colorsByPage: [Int: [String]] = [:]) {
        for state in pageStates {
            let isAnnotated = annotatedPageIndices.contains(state.pageIndex)
            state.hasAnnotations = isAnnotated
            state.annotationColors = colorsByPage[state.pageIndex] ?? []
        }
    }

    @objc public func getDisplayedPageHeight(pageIndex: Int, standardPageH: CGFloat) -> CGFloat {
        let factor = getCompressionFactor(pageIndex: pageIndex)
        return max(34.0, standardPageH * factor)
    }

    @objc public func getPageGap(pageIndex: Int, standardGap: CGFloat) -> CGFloat {
        let factor = getCompressionFactor(pageIndex: pageIndex)
        return max(4.0, standardGap * factor)
    }

    @objc public func getPageTopDocY(pageIndex: Int, standardPageH: CGFloat, standardGap: CGFloat) -> CGFloat {
        var accumY: CGFloat = 0
        let limit = min(pageIndex, pageStates.count)
        for i in 0..<limit {
            accumY += getDisplayedPageHeight(pageIndex: i, standardPageH: standardPageH) + getPageGap(pageIndex: i, standardGap: standardGap)
        }
        return accumY
    }

    @objc public func getTotalDocHeight(standardPageH: CGFloat, standardGap: CGFloat) -> CGFloat {
        return getPageTopDocY(pageIndex: pageStates.count, standardPageH: standardPageH, standardGap: standardGap)
    }

    // ── Search Mode: Automatic Page Compression ────────────────────────────────
    @objc public func applySearchMatches(
        matchingPageIndices: Set<Int>,
        animate: Bool = true,
        onUpdate: @escaping () -> Void
    ) {
        guard !pageStates.isEmpty else { return }

        for state in pageStates {
            let isMatch = matchingPageIndices.contains(state.pageIndex)
            state.hasSearchMatch = isMatch
            state.targetCompressionFactor = isMatch ? maxCompressionFactor : minCompressionFactor
        }

        if animate {
            animateCompressionTransition(onUpdate: onUpdate)
        } else {
            for state in pageStates {
                state.compressionFactor = state.targetCompressionFactor
            }
            onUpdate()
        }
    }

    // ── Collapse Unannotated Pages (LiquidText signature highlight squeeze) ────
    @objc public func collapseUnhighlightedPages(
        annotatedPageIndices: Set<Int>,
        animate: Bool = true,
        onUpdate: @escaping () -> Void
    ) {
        guard !pageStates.isEmpty else { return }

        for state in pageStates {
            let keepExpanded = annotatedPageIndices.contains(state.pageIndex) || state.hasSearchMatch
            state.targetCompressionFactor = keepExpanded ? maxCompressionFactor : minCompressionFactor
        }

        if animate {
            animateCompressionTransition(onUpdate: onUpdate)
        } else {
            for state in pageStates {
                state.compressionFactor = state.targetCompressionFactor
            }
            onUpdate()
        }
    }

    @objc public func resetAllToNormal(animate: Bool = true, onUpdate: @escaping () -> Void) {
        guard !pageStates.isEmpty else { return }

        for state in pageStates {
            state.hasSearchMatch = false
            state.matchCount = 0
            state.targetCompressionFactor = maxCompressionFactor
        }

        if animate {
            animateCompressionTransition(onUpdate: onUpdate)
        } else {
            for state in pageStates {
                state.compressionFactor = maxCompressionFactor
            }
            onUpdate()
        }
    }

    @objc public func expandPage(pageIndex: Int, animate: Bool = true, onUpdate: @escaping () -> Void) {
        guard pageIndex >= 0 && pageIndex < pageStates.count else { return }
        pageStates[pageIndex].targetCompressionFactor = maxCompressionFactor
        if animate {
            animateCompressionTransition(onUpdate: onUpdate)
        } else {
            pageStates[pageIndex].compressionFactor = maxCompressionFactor
            onUpdate()
        }
    }

    private func animateCompressionTransition(onUpdate: @escaping () -> Void) {
        displayLink?.invalidate()
        startFactors = pageStates.map { $0.compressionFactor }
        targetFactors = pageStates.map { $0.targetCompressionFactor }
        animStartTime = CACurrentMediaTime()
        animUpdateCallback = onUpdate

        displayLink = CADisplayLink(target: self, selector: #selector(handleDisplayLinkTick))
        displayLink?.add(to: .main, forMode: .common)
    }

    @objc private func handleDisplayLinkTick() {
        let elapsed = CACurrentMediaTime() - animStartTime
        let fraction = min(1.0, CGFloat(elapsed / animDuration))
        let eased = 1.0 - pow(1.0 - fraction, 2.0) // Deceleration curve

        for i in 0..<pageStates.count {
            pageStates[i].compressionFactor = startFactors[i] + (targetFactors[i] - startFactors[i]) * eased
        }

        animUpdateCallback?()

        if fraction >= 1.0 {
            displayLink?.invalidate()
            displayLink = nil
            for i in 0..<pageStates.count {
                pageStates[i].compressionFactor = targetFactors[i]
            }
            animUpdateCallback?()
        }
    }

    // ── Manual Two-Finger Pinch Compression ────────────────────────────────────
    @objc public func onManualPinchBegin(
        screenY1: CGFloat,
        screenY2: CGFloat,
        pageBounds: [CGRect],
        pageIndices: [Int],
        annotatedPageIndices: Set<Int>
    ) -> Bool {
        let topScreenY = min(screenY1, screenY2)
        let bottomScreenY = max(screenY1, screenY2)
        let fingerDist = max(30.0, bottomScreenY - topScreenY)

        var topPage = -1
        var bottomPage = -1

        for idx in 0..<pageBounds.count {
            let r = pageBounds[idx]
            let pIdx = pageIndices.indices.contains(idx) ? pageIndices[idx] : idx
            if topPage == -1 && topScreenY <= r.maxY {
                topPage = pIdx
            }
            if bottomScreenY <= r.maxY {
                bottomPage = pIdx
                break
            }
        }

        if bottomPage == -1 && !pageIndices.isEmpty {
            bottomPage = pageIndices.last ?? 0
        }

        pinchInitialFingerDistance = fingerDist
        pinchInitialFactors.removeAll()

        if topPage != -1 && bottomPage != -1 && bottomPage > topPage + 1 {
            isDocumentWideHighlightPinch = false
            pinchAnchorTopPageIndex = topPage
            pinchAnchorBottomPageIndex = bottomPage
            for i in (topPage + 1)..<bottomPage {
                pinchInitialFactors[i] = pageStates.indices.contains(i) ? pageStates[i].compressionFactor : 1.0
            }
        } else {
            isDocumentWideHighlightPinch = true
            pinchAnchorTopPageIndex = -1
            pinchAnchorBottomPageIndex = -1
            for i in 0..<pageStates.count {
                pinchInitialFactors[i] = pageStates[i].compressionFactor
            }
        }

        isManualPinching = true
        return true
    }

    @objc public func onManualPinchMove(screenY1: CGFloat, screenY2: CGFloat) -> Bool {
        guard isManualPinching, pinchInitialFingerDistance > 0 else { return false }

        let currentDist = abs(screenY1 - screenY2)
        let distanceRatio = min(1.4, max(0.06, currentDist / pinchInitialFingerDistance))

        if isDocumentWideHighlightPinch {
            for state in pageStates {
                let hasContentToRead = state.hasAnnotations || state.hasSearchMatch
                if hasContentToRead {
                    state.compressionFactor = maxCompressionFactor
                    state.targetCompressionFactor = maxCompressionFactor
                } else {
                    let initial = pinchInitialFactors[state.pageIndex] ?? 1.0
                    let newFactor = min(maxCompressionFactor, max(minCompressionFactor, initial * distanceRatio))
                    state.compressionFactor = newFactor
                    state.targetCompressionFactor = newFactor
                }
            }
        } else {
            for i in (pinchAnchorTopPageIndex + 1)..<pinchAnchorBottomPageIndex {
                guard i >= 0 && i < pageStates.count else { continue }
                let state = pageStates[i]
                let hasContentToRead = state.hasAnnotations || state.hasSearchMatch
                if hasContentToRead {
                    state.compressionFactor = maxCompressionFactor
                    state.targetCompressionFactor = maxCompressionFactor
                } else {
                    let initial = pinchInitialFactors[i] ?? 1.0
                    let newFactor = min(maxCompressionFactor, max(minCompressionFactor, initial * distanceRatio))
                    state.compressionFactor = newFactor
                    state.targetCompressionFactor = newFactor
                }
            }
        }

        return true
    }

    @objc public func onManualPinchEnd(onUpdate: @escaping () -> Void, onHaptic: @escaping () -> Void) {
        guard isManualPinching else { return }

        let anyCompressed = pageStates.contains(where: { $0.compressionFactor < 0.85 })
        if anyCompressed {
            onHaptic()
        }

        isManualPinching = false
        pinchAnchorTopPageIndex = -1
        pinchAnchorBottomPageIndex = -1
        isDocumentWideHighlightPinch = false
        pinchInitialFingerDistance = 0
        pinchInitialFactors.removeAll()

        onUpdate()
    }
}
