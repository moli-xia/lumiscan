// Photos of an open booklet / clipped pages for the page-outline tests (androidTest assets).
//
//   swift tools/testdata/make_book_photos.swift app/src/androidTest/assets
//
// book_clipped.jpg  spread whose right page runs off the frame (light floor, like a real photo)
// book_spread.jpg   the whole spread in view
// page_rule.jpg     one page running off the frame, with a long vertical rule inside its margins
import AppKit
import CoreImage

let out = CommandLine.arguments[1]

func render(_ w: Int, _ h: Int, _ draw: (CGContext) -> Void) -> CGImage {
    let cs = CGColorSpaceCreateDeviceRGB()
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.translateBy(x: 0, y: CGFloat(h)); ctx.scaleBy(x: 1, y: -1)   // top-left origin
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: true)
    draw(ctx)
    NSGraphicsContext.restoreGraphicsState()
    return ctx.makeImage()!
}

func text(_ s: String, _ x: CGFloat, _ y: CGFloat, _ size: CGFloat, bold: Bool = false) {
    let f = bold ? NSFont.boldSystemFont(ofSize: size) : NSFont.systemFont(ofSize: size)
    (s as NSString).draw(at: NSPoint(x: x, y: y), withAttributes: [.font: f, .foregroundColor: NSColor(white: 0.15, alpha: 1)])
}

func save(_ img: CGImage, _ name: String) {
    let rep = NSBitmapImageRep(cgImage: img)
    try! rep.representation(using: .jpeg, properties: [.compressionFactor: 0.88])!.write(to: URL(fileURLWithPath: out + "/" + name))
}

let lines = [
    "各县（市、区）人民政府指定的部门具体负责本辖区内",
    "停靠站点属地排查、现场巡查、隐患上报、宣传引导以及",
    "行业监督管理等工作，落实属地管理责任。各级发展改革、",
    "交通运输、自然资源、文广体旅、水利、生态环境、公安、",
    "应急管理、消防、农业农村、住房城乡建设、城市管理、",
    "市场监管、海事等部门，按照各自职责，做好相关工作。",
]

/// Text page of [w]x[h]; [rule] draws a long vertical rule at 80% width inside the margins.
func pageContent(_ ctx: CGContext, _ x0: CGFloat, _ w: CGFloat, _ h: CGFloat, title: String, rule: Bool = false) {
    text(title, x0 + w * 0.36, h * 0.07, 30, bold: true)
    var y = h * 0.14
    for block in 0..<3 {
        for (i, l) in lines.enumerated() where block < 2 || i < 3 {
            text(l, x0 + w * 0.08 + (i == 0 ? 56 : 0), y, 27)
            y += 46
        }
        y += 40
    }
    text("— \(Int(x0 / w) + 2) —", x0 + w * 0.45, h * 0.93, 24)
    if rule {
        ctx.setStrokeColor(NSColor(white: 0.2, alpha: 1).cgColor); ctx.setLineWidth(3)
        ctx.move(to: CGPoint(x: x0 + w * 0.8, y: h * 0.09)); ctx.addLine(to: CGPoint(x: x0 + w * 0.8, y: h * 0.91)); ctx.strokePath()
    }
}

// Two pages side by side with the fold shadow between them.
let pw: CGFloat = 840, ph: CGFloat = 1190
let spread = render(Int(pw * 2), Int(ph)) { ctx in
    NSColor(red: 0.95, green: 0.96, blue: 0.99, alpha: 1).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: pw * 2, height: ph))
    pageContent(ctx, 0, pw, ph, title: "第二章  建设规定")
    pageContent(ctx, pw, pw, ph, title: "第三章  管理要求")
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [NSColor(white: 0, alpha: 0).cgColor, NSColor(white: 0, alpha: 0.16).cgColor, NSColor(white: 0, alpha: 0).cgColor] as CFArray, locations: [0, 0.5, 1])!
    ctx.drawLinearGradient(g, start: CGPoint(x: pw - 40, y: 0), end: CGPoint(x: pw + 40, y: 0), options: [])
    ctx.setStrokeColor(NSColor(white: 0.55, alpha: 1).cgColor); ctx.setLineWidth(2)
    ctx.move(to: CGPoint(x: pw, y: 0)); ctx.addLine(to: CGPoint(x: pw, y: ph)); ctx.strokePath()
}

let single = render(Int(pw), Int(ph)) { ctx in
    NSColor(red: 0.96, green: 0.96, blue: 0.98, alpha: 1).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: pw, height: ph))
    pageContent(ctx, 0, pw, ph, title: "附件  登记表", rule: true)
}

func floor(_ w: Int, _ h: Int) -> CIImage {
    let img = render(w, h) { ctx in
        let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [NSColor(red: 0.80, green: 0.77, blue: 0.71, alpha: 1).cgColor, NSColor(red: 0.70, green: 0.67, blue: 0.62, alpha: 1).cgColor] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: w, y: h), options: [])
        // Tile joints on the floor.
        ctx.setStrokeColor(NSColor(white: 0.55, alpha: 0.5).cgColor); ctx.setLineWidth(2)
        ctx.move(to: CGPoint(x: 0, y: CGFloat(h) * 0.12)); ctx.addLine(to: CGPoint(x: CGFloat(w) * 0.35, y: 0)); ctx.strokePath()
    }
    return CIImage(cgImage: img)
}

/// Places [src] with its corners (top-left origin, TL TR BR BL) on a floor of [w]x[h].
func photo(_ src: CGImage, _ c: [CGPoint], _ w: Int, _ h: Int, _ name: String) {
    let p = CIFilter(name: "CIPerspectiveTransform")!
    p.setValue(CIImage(cgImage: src), forKey: kCIInputImageKey)
    let H = CGFloat(h)
    p.setValue(CIVector(x: c[0].x, y: H - c[0].y), forKey: "inputTopLeft")
    p.setValue(CIVector(x: c[1].x, y: H - c[1].y), forKey: "inputTopRight")
    p.setValue(CIVector(x: c[2].x, y: H - c[2].y), forKey: "inputBottomRight")
    p.setValue(CIVector(x: c[3].x, y: H - c[3].y), forKey: "inputBottomLeft")
    let composed = p.outputImage!.composited(over: floor(w, h))
    save(CIContext().createCGImage(composed, from: CGRect(x: 0, y: 0, width: w, height: h))!, name)
}

// Corners are repeated in PipelineTest (the fold is found there by projecting the spread's centre line).
photo(spread, [CGPoint(x: 110, y: 330), CGPoint(x: 1890, y: 420), CGPoint(x: 1860, y: 1470), CGPoint(x: 40, y: 1420)], 1200, 1600, "book_clipped.jpg")
photo(spread, [CGPoint(x: 120, y: 190), CGPoint(x: 1500, y: 240), CGPoint(x: 1470, y: 1060), CGPoint(x: 90, y: 1010)], 1600, 1200, "book_spread.jpg")
photo(single, [CGPoint(x: 160, y: 200), CGPoint(x: 1330, y: 260), CGPoint(x: 1300, y: 1560), CGPoint(x: 110, y: 1500)], 1200, 1600, "page_rule.jpg")
