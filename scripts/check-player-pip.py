"""Проверяет исходные Swift-ветки PiP без UIKit и сборки приложения."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / 'ios/Video/Features/RCTPlayerObserver.swift').read_text()
methods = source[source.index('    /** Сохраняет fullscreen controller'):source.index('    #if !os(tvOS)', source.index('    /** Сохраняет fullscreen controller'))]
setter = source[source.index('    func setRestoreUserInterfaceForPIPStopCompletionHandler('):]
setter = setter[:setter.index('\n    }') + len('\n    }')]
program = '''import Foundation
class UIViewController {}
class AVPlayerViewController {
    var presentingViewController: UIViewController?
    weak var delegate: Check?
}
// Требуем точные Swift-сигнатуры delegate, включая внешний label первого параметра.
protocol PipDelegate {
    func playerViewControllerShouldAutomaticallyDismissAtPictureInPictureStart(_: AVPlayerViewController) -> Bool
    func playerViewControllerDidStartPictureInPicture(_: AVPlayerViewController)
    func playerViewControllerDidStopPictureInPicture(_: AVPlayerViewController)
    func playerViewController(_: AVPlayerViewController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: @escaping (Bool) -> Void)
}
class Handlers {
    var active = false
    var events: [Bool] = []
    var restores = 0
    /** Имитирует получение события запуска PiP. */
    func handlePictureInPictureEnter() { active = true; events.append(true) }
    /** Имитирует получение события завершения PiP. */
    func handlePictureInPictureExit() { active = false; events.append(false) }
    /** Считает запросы внешнего восстановления inline UI. */
    func handleRestoreUserInterfaceForPictureInPictureStop() { restores += 1 }
}
class Check: PipDelegate {
    var _handlers: Handlers? = Handlers()
    var _restoreUserInterfaceForPIPStopCompletionHandler: ((Bool) -> Void)?
''' + methods + setter + '''
}
let observer = Check()
let controller = AVPlayerViewController()
controller.presentingViewController = UIViewController()
controller.delegate = observer
// Политика до DidStart сохраняет окно/delegate; ошибка запуска также не закрывает окно.
assert(!observer.playerViewControllerShouldAutomaticallyDismissAtPictureInPictureStart(controller))
assert(observer._handlers?.active == false)
assert(controller.delegate === observer)
// Полный цикл fullscreen → PiP → восстановление → stop доставляет оба события.
controller.delegate?.playerViewControllerDidStartPictureInPicture(controller)
var completions: [Bool] = []
controller.delegate?.playerViewController(controller, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: { completions.append($0) })
assert(completions == [true])
assert(observer._handlers?.restores == 0)
controller.delegate?.playerViewControllerDidStopPictureInPicture(controller)
assert(observer._handlers?.events == [true, false])
assert(observer._handlers?.active == false)
assert(controller.delegate === observer)
// Inline UI сохраняет прежний внешний callback и однократное подтверждение.
controller.presentingViewController = nil
observer.playerViewController(controller, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: { completions.append($0) })
assert(observer._handlers?.restores == 1)
assert(completions == [true])
observer.setRestoreUserInterfaceForPIPStopCompletionHandler(false)
observer.setRestoreUserInterfaceForPIPStopCompletionHandler(true)
assert(completions == [true, false])
print("Swift PiP policy, restoration and delegate checks passed")
'''
with tempfile.TemporaryDirectory() as directory:
    script = Path(directory) / 'check.swift'
    script.write_text(program)
    subprocess.run(['swift', '-module-cache-path', str(Path(directory) / 'cache'), str(script)], check=True)
