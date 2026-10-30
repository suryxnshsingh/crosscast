// Renders Crosscast's raster images with the bundled Inter font:
//
//   swift tools/brand/render.swift banner app/src/main/res/drawable-xhdpi/banner.png 1   # TV launcher, 320×180
//   swift tools/brand/render.swift banner docs/images/banner.png 4                       # README, 1280×720
//   swift tools/brand/render.swift logo docs/images/logo.png 512                         # app-icon tile
//
// The mark matches tools/brand/brand.py: three waves cast along each arm of an X.
import AppKit
import CoreText

let args = CommandLine.arguments
let mode = args[1], out = args[2], size = CGFloat(Double(args[3])!)
let fonts = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../app/src/main/res/font")
for f in ["inter_display_semibold.ttf", "inter_medium.ttf"] {
    CTFontManagerRegisterFontsForURL(fonts.appendingPathComponent(f) as CFURL, .process, nil)
}

let (w, h) = mode == "logo" ? (size, size) : (320 * size, 180 * size)
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(w), pixelsHigh: Int(h), bitsPerSample: 8, samplesPerPixel: 4,
                           hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
let ctx = NSGraphicsContext(bitmapImageRep: rep)!
NSGraphicsContext.current = ctx
ctx.cgContext.translateBy(x: 0, y: h)
ctx.cgContext.scaleBy(x: 1, y: -1) // y down, like the Android drawables

func color(_ hex: UInt32) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
}
// Violet at the bottom left to coral at the top right (angle measured in the flipped context).
let gradient = NSGradient(colors: [color(0x6A4CF5), color(0xB24ACB), color(0xFF7A59)])!

/// Apple's continuous corners: the same Bézier fit as ContinuousRoundedShape.kt.
func squircle(_ rect: NSRect, radius: CGFloat) -> NSBezierPath {
    let r = min(radius, min(rect.width, rect.height) / 2 / 1.52866483)
    let path = NSBezierPath()
    let corners: [(NSPoint, NSPoint, NSPoint)] = [
        (NSPoint(x: rect.maxX, y: rect.minY), NSPoint(x: 1, y: 0), NSPoint(x: 0, y: 1)),
        (NSPoint(x: rect.maxX, y: rect.maxY), NSPoint(x: 0, y: 1), NSPoint(x: -1, y: 0)),
        (NSPoint(x: rect.minX, y: rect.maxY), NSPoint(x: -1, y: 0), NSPoint(x: 0, y: -1)),
        (NSPoint(x: rect.minX, y: rect.minY), NSPoint(x: 0, y: -1), NSPoint(x: 1, y: 0)),
    ]
    path.move(to: NSPoint(x: rect.minX + 1.52866483 * r, y: rect.minY))
    for (at, u, v) in corners {
        func p(_ a: CGFloat, _ b: CGFloat) -> NSPoint { NSPoint(x: at.x - u.x * a * r + v.x * b * r, y: at.y - u.y * a * r + v.y * b * r) }
        path.line(to: p(1.52866483, 0))
        path.curve(to: p(0.66993427, 0.06549600), controlPoint1: p(1.08849323, 0), controlPoint2: p(0.86840689, 0))
        path.line(to: p(0.63149399, 0.07491100))
        path.curve(to: p(0.07491176, 0.63149399), controlPoint1: p(0.37282392, 0.16905899), controlPoint2: p(0.16906013, 0.37282401))
        path.line(to: p(0.06549569, 0.66993493))
        path.curve(to: p(0, 1.52866483), controlPoint1: p(0, 0.86840701), controlPoint2: p(0, 1.08849299))
    }
    path.close()
    return path
}

/// The logo mark, centred at `c`, `unit` points per unit of its 100-unit tile.
func drawMark(at c: NSPoint, unit: CGFloat) {
    NSColor.white.setStroke()
    NSColor.white.setFill()
    for (radius, half) in [(13.0, 26.0), (23.5, 20.0), (34.0, 16.0)] as [(CGFloat, CGFloat)] {
        for k in 0..<4 {
            let mid = 45 + CGFloat(k) * 90
            let arc = NSBezierPath()
            arc.appendArc(withCenter: c, radius: radius * unit, startAngle: mid - half, endAngle: mid + half, clockwise: false)
            arc.lineWidth = 6.5 * unit
            arc.lineCapStyle = .round
            arc.stroke()
        }
    }
    let r = 6.5 * unit
    NSBezierPath(ovalIn: NSRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)).fill()
}

func text(_ s: String, font: String, size: CGFloat, alpha: CGFloat, x: CGFloat, baseline: CGFloat) {
    let f = NSFont(name: font, size: size)!
    let a = NSAttributedString(string: s, attributes: [.font: f, .foregroundColor: NSColor.white.withAlphaComponent(alpha), .kern: -0.015 * size])
    NSGraphicsContext.saveGraphicsState()
    let t = NSAffineTransform()
    t.translateX(by: x, yBy: baseline)
    t.scaleX(by: 1, yBy: -1) // text stays upright in the flipped context
    t.concat()
    a.draw(at: NSPoint(x: 0, y: -f.descender))
    NSGraphicsContext.restoreGraphicsState()
}

if mode == "logo" {
    let tile = squircle(NSRect(x: 0, y: 0, width: w, height: h), radius: w * 0.225)
    tile.addClip()
    gradient.draw(in: NSRect(x: 0, y: 0, width: w, height: h), angle: -45)
    drawMark(at: NSPoint(x: w / 2, y: h / 2), unit: w / 100)
} else {
    let s = size
    gradient.draw(in: NSRect(x: 0, y: 0, width: w, height: h), angle: -30)
    // Darken towards the text a touch so the wordmark sits on an even tone.
    NSGradient(colors: [NSColor.black.withAlphaComponent(0), NSColor.black.withAlphaComponent(0.18)])!
        .draw(in: NSRect(x: 0, y: 0, width: w, height: h), angle: 0)
    drawMark(at: NSPoint(x: 72 * s, y: 90 * s), unit: 0.92 * s)
    text("Crosscast", font: "InterDisplay-SemiBold", size: 40 * s, alpha: 1, x: 126 * s, baseline: 98 * s)
    text("Speaker · Now Playing", font: "Inter-Medium", size: 15 * s, alpha: 0.72, x: 128 * s, baseline: 124 * s)
}

NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
