"""Проверяет исходные Swift-ветки pinch и геометрию без сборки приложения."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'ios/Video/RCTVideo.swift').read_text()
pinch = source[source.index('        @objc private func handlePinchGesture'):source.index('        /** Выбирает боковую')].replace('@objc ', '')
geometry = source[source.index('        private func seekGestureDelta'):source.index('        /** Передаёт двойной тап')]
program = '''import Foundation
class UIPinchGestureRecognizer {
    enum State { case ended, cancelled, changed }
    var state = State.ended
    var scale = 1.0
}
class Check {
    var onVideoZoom: (([String: Any]) -> Void)?
''' + pinch + geometry + '''
    /** Проверяет границы зон в обеих ориентациях и неуспешные pinch. */
    func run() {
        for size in [CGSize(width: 900, height: 600), CGSize(width: 600, height: 900)] {
            let edge = size.width / 3 - 50
            assert(seekGestureDelta(at: CGPoint(x: 0, y: 100), size: size) == -20)
            assert(seekGestureDelta(at: CGPoint(x: edge, y: size.height - 80), size: size) == -20)
            assert(seekGestureDelta(at: CGPoint(x: size.width - edge, y: 100), size: size) == 20)
            assert(seekGestureDelta(at: CGPoint(x: size.width, y: 100), size: size) == 20)
            for point in [CGPoint(x: -1, y: 100), CGPoint(x: size.width + 1, y: 100),
                          CGPoint(x: 0, y: 99), CGPoint(x: 0, y: size.height - 79),
                          CGPoint(x: size.width / 2, y: 100)] {
                assert(seekGestureDelta(at: point, size: size) == nil)
            }
        }
        assert(seekGestureDelta(at: CGPoint(x: 0, y: 100), size: CGSize(width: 120, height: 600)) == nil)
        var events: [Bool] = []
        onVideoZoom = { events.append($0["zoomed"] as! Bool) }
        let gesture = UIPinchGestureRecognizer()
        for scale in [1.0, 0, -1, Double.nan, Double.infinity] {
            gesture.scale = scale
            handlePinchGesture(gesture)
        }
        gesture.scale = 2
        gesture.state = .cancelled
        handlePinchGesture(gesture)
        gesture.state = .changed
        handlePinchGesture(gesture)
        assert(events.isEmpty)
        gesture.state = .ended
        handlePinchGesture(gesture)
        gesture.scale = 0.5
        handlePinchGesture(gesture)
        assert(events == [true, false])
    }
}
Check().run()
print("Swift gesture checks passed")
'''
with tempfile.TemporaryDirectory() as directory:
    script = Path(directory) / 'check.swift'
    script.write_text(program)
    subprocess.run(['swift', '-module-cache-path', str(Path(directory) / 'cache'), str(script)], check=True)
