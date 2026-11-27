import Cocoa
import KurmanciCore

/// The floating candidate list under the text being typed. A plain non-activating panel, so clicking a row never
/// takes focus from the app the user is typing in.
final class CandidatePanel: NSPanel {

    var onSelect: ((Int) -> Void)?

    private let effect = NSVisualEffectView()
    private let stack = NSStackView()

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: 200, height: 40),
                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        level = .popUpMenu
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        hidesOnDeactivate = false
        becomesKeyOnlyIfNeeded = true
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]

        effect.material = .popover
        effect.state = .active
        effect.blendingMode = .behindWindow
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 8
        effect.layer?.masksToBounds = true

        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 0
        stack.edgeInsets = NSEdgeInsets(top: 4, left: 4, bottom: 4, right: 4)
        stack.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: effect.trailingAnchor),
            stack.topAnchor.constraint(equalTo: effect.topAnchor),
            stack.bottomAnchor.constraint(equalTo: effect.bottomAnchor),
        ])
        contentView = effect
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }

    /// [caret] is the screen rect of the text line (from the app); the list goes below it, or above near the
    /// bottom of the screen.
    func show(_ candidates: [Candidate], highlighted: Int?, below caret: NSRect) {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, c) in candidates.enumerated() {
            let row = CandidateRow(index: i, candidate: c, highlighted: i == highlighted)
            row.onClick = { [weak self] in self?.onSelect?(i) }
            stack.addArrangedSubview(row)
            row.widthAnchor.constraint(equalTo: stack.widthAnchor, constant: -8).isActive = true
        }
        stack.layoutSubtreeIfNeeded()
        let size = stack.fittingSize

        var anchor = caret
        if anchor == .zero {
            let m = NSEvent.mouseLocation
            anchor = NSRect(x: m.x, y: m.y, width: 0, height: 0)
        }
        var origin = NSPoint(x: anchor.minX, y: anchor.minY - size.height - 4)
        if let screen = NSScreen.screens.first(where: { $0.frame.contains(anchor.origin) }) ?? NSScreen.main {
            let vis = screen.visibleFrame
            if origin.y < vis.minY { origin.y = anchor.maxY + 4 }
            origin.x = min(max(origin.x, vis.minX), vis.maxX - size.width)
        }
        setFrame(NSRect(origin: origin, size: size), display: true)
        orderFront(nil)
    }

    func hide() {
        orderOut(nil)
    }
}

private final class CandidateRow: NSView {

    var onClick: (() -> Void)?

    init(index: Int, candidate: Candidate, highlighted: Bool) {
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = 5
        if highlighted { layer?.backgroundColor = NSColor.selectedContentBackgroundColor.cgColor }
        let primaryColor: NSColor = highlighted ? .white : .labelColor
        let secondaryColor: NSColor = highlighted ? NSColor.white.withAlphaComponent(0.75) : .secondaryLabelColor

        let number = label("\(index + 1)", size: 12, color: secondaryColor)
        let main = label(candidate.isSentence ? "⇄ \(candidate.kurmanji)" : candidate.kurmanji,
                         size: 15, color: primaryColor, bold: candidate.isSentence)
        // Long sentences keep their end visible: that's what was just typed.
        main.lineBreakMode = .byTruncatingHead
        main.widthAnchor.constraint(lessThanOrEqualToConstant: 520).isActive = true
        main.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let hint = label(candidate.isSentence ? "cümle çevirisi" : candidate.suggestion.turkish, size: 11, color: secondaryColor)

        let row = NSStackView(views: [number, main, hint])
        row.orientation = .horizontal
        row.alignment = .firstBaseline
        row.spacing = 8
        row.edgeInsets = NSEdgeInsets(top: 3, left: 8, bottom: 3, right: 10)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor),
            row.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor),
            row.topAnchor.constraint(equalTo: topAnchor),
            row.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) { onClick?() }

    private func label(_ text: String, size: CGFloat, color: NSColor, bold: Bool = false) -> NSTextField {
        let l = NSTextField(labelWithString: text)
        l.font = bold ? .boldSystemFont(ofSize: size) : .systemFont(ofSize: size)
        l.textColor = color
        l.maximumNumberOfLines = 1
        return l
    }
}
