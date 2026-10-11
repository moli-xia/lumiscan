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

func text(_ s: String, _ x: CGFloat, _ y: CGFloat, _ size: CGFloat, bold: Bool = false, color: NSColor = .black) {
    let f = bold ? NSFont.boldSystemFont(ofSize: size) : NSFont.systemFont(ofSize: size)
    (s as NSString).draw(at: NSPoint(x: x, y: y), withAttributes: [.font: f, .foregroundColor: color])
}

func save(_ img: CGImage, _ name: String) {
    let rep = NSBitmapImageRep(cgImage: img)
    try! rep.representation(using: .jpeg, properties: [.compressionFactor: 0.9])!.write(to: URL(fileURLWithPath: out + "/" + name))
}

// ---------- Document page (A4-ish 1240x1754)
let page = render(1240, 1754) { ctx in
    NSColor.white.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1240, height: 1754))
    text("2026年度项目进度报告", 380, 90, 46, bold: true)
    let body = [
        "本报告总结了光点项目在第三季度的主要进展。团队完成了文档扫描、",
        "文字识别与表格还原等核心模块的开发，并在多种机型上完成了测试。",
        "下一阶段将重点优化识别准确率与导出速度，同时完善 PDF 工具箱。",
        "The scanner now supports OCR, Word and Excel export on device.",
    ]
    for (i, l) in body.enumerated() { text(l, 100, 200 + CGFloat(i) * 52, 30) }
    // Table 4 rows x 4 cols
    let x0: CGFloat = 100, y0: CGFloat = 470, cw: CGFloat = 260, rh: CGFloat = 80
    ctx.setStrokeColor(NSColor.black.cgColor); ctx.setLineWidth(3)
    for r in 0...4 { ctx.move(to: CGPoint(x: x0, y: y0 + CGFloat(r) * rh)); ctx.addLine(to: CGPoint(x: x0 + 4 * cw, y: y0 + CGFloat(r) * rh)) }
    for c in 0...4 { ctx.move(to: CGPoint(x: x0 + CGFloat(c) * cw, y: y0)); ctx.addLine(to: CGPoint(x: x0 + CGFloat(c) * cw, y: y0 + 4 * rh)) }
    ctx.strokePath()
    let cells = [["模块", "负责人", "进度", "备注"], ["文档扫描", "张三", "100%", "已上线"], ["文字识别", "李四", "95%", "优化中"], ["表格还原", "王五", "80%", "测试中"]]
    for (r, row) in cells.enumerated() { for (c, v) in row.enumerated() { text(v, x0 + CGFloat(c) * cw + 30, y0 + CGFloat(r) * rh + 22, 30, bold: r == 0) } }
    let more = ["总结：各模块按计划推进，预计第四季度完成全部功能。", "如有疑问请联系项目组。"]
    for (i, l) in more.enumerated() { text(l, 100, 860 + CGFloat(i) * 52, 30) }
    // Blue handwriting strokes
    ctx.setStrokeColor(NSColor(red: 0.1, green: 0.25, blue: 0.85, alpha: 1).cgColor); ctx.setLineWidth(6); ctx.setLineCap(.round)
    ctx.move(to: CGPoint(x: 120, y: 1080)); ctx.addCurve(to: CGPoint(x: 600, y: 1100), control1: CGPoint(x: 250, y: 1000), control2: CGPoint(x: 420, y: 1200)); ctx.strokePath()
    text("已审阅 ✓ 同意", 650, 1060, 44, color: NSColor(red: 0.1, green: 0.25, blue: 0.85, alpha: 1))
    text("请补充数据", 700, 1180, 40, color: NSColor(red: 0.85, green: 0.1, blue: 0.1, alpha: 1))
    // Light watermark
    ctx.saveGState(); ctx.translateBy(x: 620, y: 1450); ctx.rotate(by: -0.4)
    text("内部资料 请勿外传", -330, -40, 80, bold: true, color: NSColor(white: 0.82, alpha: 1))
    ctx.restoreGState()
}
save(page, "doc_flat.jpg")

// ---------- Photo of the page: perspective on a dark desk with a soft shadow
let ci = CIImage(cgImage: page)
let persp = CIFilter(name: "CIPerspectiveTransform")!
persp.setValue(ci, forKey: kCIInputImageKey)
// Corners are a true pinhole projection (f=1700px, principal point at the image centre) of an
// A4 sheet tilted by (0.32, -0.18, 0.06) rad at 0.36m, so perspective aspect recovery applies.
// Top-left-origin corners: TL(253,157) TR(1362.5,315.5) BR(1095.5,1585.9) BL(223.4,1588.4).
// CoreImage origin is bottom-left; output canvas 1500x2000.
persp.setValue(CIVector(x: 253, y: 1843), forKey: "inputTopLeft")
persp.setValue(CIVector(x: 1362.5, y: 1684.5), forKey: "inputTopRight")
persp.setValue(CIVector(x: 1095.5, y: 414.1), forKey: "inputBottomRight")
persp.setValue(CIVector(x: 223.4, y: 411.6), forKey: "inputBottomLeft")
let warped = persp.outputImage!
let desk = render(1500, 2000) { ctx in
    let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [NSColor(red: 0.36, green: 0.25, blue: 0.17, alpha: 1).cgColor, NSColor(red: 0.22, green: 0.15, blue: 0.10, alpha: 1).cgColor] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: 1500, y: 2000), options: [])
}
var composed = warped.composited(over: CIImage(cgImage: desk))
// Uneven lighting: darken one side
let shade = CIFilter(name: "CILinearGradient")!
shade.setValue(CIVector(x: 0, y: 0), forKey: "inputPoint0"); shade.setValue(CIColor(red: 0, green: 0, blue: 0, alpha: 0.35), forKey: "inputColor0")
shade.setValue(CIVector(x: 900, y: 900), forKey: "inputPoint1"); shade.setValue(CIColor(red: 0, green: 0, blue: 0, alpha: 0), forKey: "inputColor1")
composed = shade.outputImage!.cropped(to: composed.extent).composited(over: composed)
let cictx = CIContext()
save(cictx.createCGImage(composed, from: CGRect(x: 0, y: 0, width: 1500, height: 2000))!, "doc_photo.jpg")

// ---------- Objects to count: 37 pills on a gray tray
let counts = render(1200, 1200) { ctx in
    NSColor(white: 0.25, alpha: 1).setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1200, height: 1200))
    var rng = SystemRandomNumberGenerator()
    var placed: [CGPoint] = []
    while placed.count < 37 {
        let p = CGPoint(x: CGFloat.random(in: 90...1110, using: &rng), y: CGFloat.random(in: 90...1110, using: &rng))
        if placed.allSatisfy({ hypot($0.x - p.x, $0.y - p.y) > 110 }) { placed.append(p) }
    }
    for p in placed {
        NSColor(red: 0.95, green: 0.9, blue: 0.75, alpha: 1).setFill()
        ctx.fillEllipse(in: CGRect(x: p.x - 45, y: p.y - 45, width: 90, height: 90))
        NSColor(red: 0.8, green: 0.75, blue: 0.6, alpha: 1).setStroke(); ctx.setLineWidth(3)
        ctx.strokeEllipse(in: CGRect(x: p.x - 45, y: p.y - 45, width: 90, height: 90))
    }
}
save(counts, "count_37.jpg")
print("ok")
