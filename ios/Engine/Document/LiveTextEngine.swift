import Foundation
import UIKit
import Vision

@objc public class LiveTextEngine: NSObject {

    /**
     * Recognizes text from a UIImage using the on-device Apple Neural Engine (ANE).
     */
    @objc public static func extractText(
        from image: UIImage,
        completion: @escaping ([String], [BoundingBox]) -> Void
    ) {
        guard let cgImage = image.cgImage else {
            completion([], [])
            return
        }

        let request = VNRecognizeTextRequest { request, error in
            guard error == nil, let observations = request.results as? [VNRecognizedTextObservation] else {
                completion([], [])
                return
            }

            var extractedStrings: [String] = []
            var boundingBoxes: [BoundingBox] = []

            let imageWidth = CGFloat(cgImage.width)
            let imageHeight = CGFloat(cgImage.height)

            for observation in observations {
                guard let topCandidate = observation.topCandidates(1).first else { continue }
                extractedStrings.append(topCandidate.string)

                // VNRecognizedTextObservation uses normalized bottom-left coordinates (0.0 to 1.0)
                let box = observation.boundingBox
                let convertedY = (1.0 - box.origin.y - box.height) * imageHeight
                let nativeBox = BoundingBox(
                    x: box.origin.x * imageWidth,
                    y: convertedY,
                    width: box.width * imageWidth,
                    height: box.height * imageHeight
                )
                boundingBoxes.append(nativeBox)
            }

            DispatchQueue.main.async {
                completion(extractedStrings, boundingBoxes)
            }
        }

        request.recognitionLevel = .accurate

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        DispatchQueue.global(qos: .userInitiated).async {
            try? handler.perform([request])
        }
    }
}
