import Cocoa
import InputMethodKit

// `KurmanciKlavye --register`: registers and enables the installed bundle with the system (build.sh install runs
// this), so it shows up under Input Sources without logging out.
if CommandLine.arguments.contains("--register") {
    InputSourceRegistration.registerAndEnable()
    exit(0)
}

// `KurmanciKlavye --selftest`: checks the built bundle without a login session (used by CI).
if CommandLine.arguments.contains("--selftest") {
    exit(SelfTest.run() ? 0 : 1)
}

let connectionName = Bundle.main.object(forInfoDictionaryKey: "InputMethodConnectionName") as! String
let server = IMKServer(name: connectionName, bundleIdentifier: Bundle.main.bundleIdentifier)
Shared.loadDictionary()
NSApplication.shared.run()
