import AppKit
let w = 1200, h = 900
let cs = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
// sky gradient + ground + sun + houses + tree (sepia tones)
let sky = CGGradient(colorsSpace: cs, colors: [CGColor(red: 0.85, green: 0.75, blue: 0.58, alpha: 1), CGColor(red: 0.70, green: 0.58, blue: 0.42, alpha: 1)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(sky, start: CGPoint(x: 0, y: CGFloat(h)), end: CGPoint(x: 0, y: 300), options: [])
ctx.setFillColor(CGColor(red: 0.45, green: 0.36, blue: 0.25, alpha: 1)); ctx.fill(CGRect(x: 0, y: 0, width: w, height: 300))
ctx.setFillColor(CGColor(red: 0.95, green: 0.88, blue: 0.7, alpha: 1)); ctx.fillEllipse(in: CGRect(x: 880, y: 640, width: 140, height: 140))
for (i, x) in [150, 420, 650].enumerated() {
    ctx.setFillColor(CGColor(red: 0.55 - Double(i) * 0.05, green: 0.42, blue: 0.3, alpha: 1)); ctx.fill(CGRect(x: x, y: 300, width: 180, height: 200))
    ctx.setFillColor(CGColor(red: 0.35, green: 0.25, blue: 0.18, alpha: 1))
    ctx.move(to: CGPoint(x: x - 20, y: 500)); ctx.addLine(to: CGPoint(x: x + 90, y: 590)); ctx.addLine(to: CGPoint(x: x + 200, y: 500)); ctx.fillPath()
    ctx.setFillColor(CGColor(red: 0.25, green: 0.18, blue: 0.12, alpha: 1)); ctx.fill(CGRect(x: x + 70, y: 300, width: 40, height: 80))
}
// noise
var rng = SystemRandomNumberGenerator()
for _ in 0..<60000 {
    let v = CGFloat.random(in: 0...1, using: &rng)
    ctx.setFillColor(CGColor(gray: v, alpha: 0.18))
    ctx.fill(CGRect(x: Int.random(in: 0..<w, using: &rng), y: Int.random(in: 0..<h, using: &rng), width: 2, height: 2))
}
// scratches
ctx.setStrokeColor(CGColor(gray: 0.97, alpha: 1)); ctx.setLineWidth(3)
ctx.move(to: CGPoint(x: 100, y: 850)); ctx.addLine(to: CGPoint(x: 700, y: 120)); ctx.strokePath()
ctx.setLineWidth(2); ctx.move(to: CGPoint(x: 950, y: 880)); ctx.addLine(to: CGPoint(x: 1050, y: 60)); ctx.strokePath()
ctx.setStrokeColor(CGColor(gray: 0.1, alpha: 1)); ctx.setLineWidth(2)
ctx.move(to: CGPoint(x: 300, y: 50)); ctx.addLine(to: CGPoint(x: 1150, y: 400)); ctx.strokePath()
let img = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: img)
try! rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85])!.write(to: URL(fileURLWithPath: "old_photo.jpg"))
print("ok")
