import UIKit

public extension UIColor {
    convenience init?(hexString: String) {
        var formatted = hexString.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        if formatted.hasPrefix("#") {
            formatted.remove(at: formatted.startIndex)
        } else if formatted.hasPrefix("0X") {
            formatted.removeSubrange(formatted.startIndex..<formatted.index(formatted.startIndex, offsetBy: 2))
        }

        var rgbValue: UInt64 = 0
        guard Scanner(string: formatted).scanHexInt64(&rgbValue) else {
            return nil
        }

        let length = formatted.count
        switch length {
        case 3: // RGB (12-bit)
            let r = CGFloat((rgbValue >> 8) & 0xF) / 15.0
            let g = CGFloat((rgbValue >> 4) & 0xF) / 15.0
            let b = CGFloat(rgbValue & 0xF) / 15.0
            self.init(red: r, green: g, blue: b, alpha: 1.0)
        case 4: // RGBA (16-bit)
            let r = CGFloat((rgbValue >> 12) & 0xF) / 15.0
            let g = CGFloat((rgbValue >> 8) & 0xF) / 15.0
            let b = CGFloat((rgbValue >> 4) & 0xF) / 15.0
            let a = CGFloat(rgbValue & 0xF) / 15.0
            self.init(red: r, green: g, blue: b, alpha: a)
        case 6: // RRGGBB (24-bit)
            let r = CGFloat((rgbValue >> 16) & 0xFF) / 255.0
            let g = CGFloat((rgbValue >> 8) & 0xFF) / 255.0
            let b = CGFloat(rgbValue & 0xFF) / 255.0
            self.init(red: r, green: g, blue: b, alpha: 1.0)
        case 8: // RRGGBBAA (32-bit)
            let r = CGFloat((rgbValue >> 24) & 0xFF) / 255.0
            let g = CGFloat((rgbValue >> 16) & 0xFF) / 255.0
            let b = CGFloat((rgbValue >> 8) & 0xFF) / 255.0
            let a = CGFloat(rgbValue & 0xFF) / 255.0
            self.init(red: r, green: g, blue: b, alpha: a)
        default:
            return nil
        }
    }
}
