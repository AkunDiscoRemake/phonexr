import Foundation

enum BridgeError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        if case .message(let text) = self { return text }
        return "Неизвестная ошибка"
    }
}

final class AndroidBridge: @unchecked Sendable {
    private var adb: URL {
        get throws {
            let home = FileManager.default.homeDirectoryForCurrentUser.path
            let environment = ProcessInfo.processInfo.environment
            let candidates = [
                environment["ANDROID_HOME"].map { "\($0)/platform-tools/adb" },
                environment["ANDROID_SDK_ROOT"].map { "\($0)/platform-tools/adb" },
                "\(home)/Library/Android/sdk/platform-tools/adb",
                "/opt/homebrew/bin/adb",
                "/usr/local/bin/adb"
            ].compactMap { $0 }
            guard let path = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else {
                throw BridgeError.message("ADB не найден. Установите Android Platform Tools.")
            }
            return URL(fileURLWithPath: path)
        }
    }

    func devices() throws -> [Device] {
        _ = try? run(adb, ["start-server"])
        let output = try run(adb, ["devices", "-l"])
        return output.split(separator: "\n").dropFirst().compactMap { row in
            let line = String(row)
            let fields = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
            guard fields.count >= 2 else { return nil }
            let serial = String(fields[0])
            let state = String(fields[1])
            let model = line.range(of: #"model:([^ ]+)"#, options: .regularExpression)
                .map { String(line[$0]).replacingOccurrences(of: "model:", with: "").replacingOccurrences(of: "_", with: " ") }
                ?? "Android"
            return Device(serial: serial, model: model, state: state)
        }
    }

    /** Carries the phone's VR remote-control port (PhoneXR, loopback only) to this Mac over USB. */
    func forwardRemote(serial: String, port: Int) throws {
        let output = try run(adb, ["-s", serial, "forward", "tcp:\(port)", "tcp:\(port)"])
        if output.lowercased().contains("error") { throw BridgeError.message(output) }
    }

    func install(apk: URL, serial: String) throws -> String {
        let output = try run(adb, ["-s", serial, "install", "-r", apk.path])
        guard output.contains("Success") else { throw BridgeError.message(output) }
        return "Готово: \(apk.lastPathComponent) установлен на телефон."
    }

    func installOpenXR(runtime: URL, game: URL, serial: String) throws -> String {
        _ = try install(apk: runtime, serial: serial)
        do {
            _ = try install(apk: game, serial: serial)
        } catch {
            throw BridgeError.message(
                "OpenXR Runtime установлен, но игра не установилась. " +
                "Вероятно, APK требует функции Meta Quest или другую подпись.\n\n\(error.localizedDescription)"
            )
        }
        return "OpenXR Runtime и \(game.lastPathComponent) установлены. Откройте Monado XR один раз, затем запускайте игру."
    }

    func buildAndInstall(source: URL, serial: String) throws -> String {
        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("XRBridge-\(UUID().uuidString).apk")
        defer { try? FileManager.default.removeItem(at: output) }
        try build(source: source, destination: output)
        return try install(apk: output, serial: serial)
    }

    func build(source: URL, destination: URL) throws {
        let prepared = try prepare(source)
        defer { if prepared.isTemporary { try? FileManager.default.removeItem(at: prepared.root) } }

        let apk: URL
        if let wrapper = find(named: "gradlew", under: prepared.root) {
            apk = try buildGradle(wrapper: wrapper)
        } else if find(named: "ProjectVersion.txt", under: prepared.root)?.path.contains("ProjectSettings") == true {
            throw BridgeError.message("Это Unity-проект. Лёгкий режим работает без Unity: сначала экспортируйте проект как Android Gradle или выберите нативный Android/OpenXR-проект.")
        } else {
            throw BridgeError.message("Файл gradlew не найден. Нужен нативный Android/OpenXR Gradle-проект.")
        }
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.copyItem(at: apk, to: destination)
    }

    private func prepare(_ source: URL) throws -> (root: URL, isTemporary: Bool) {
        if source.pathExtension.lowercased() != "zip" { return (source, false) }
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("xrbridge-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
        _ = try run(URL(fileURLWithPath: "/usr/bin/ditto"), ["-x", "-k", source.path, destination.path])
        return (destination, true)
    }

    private func buildGradle(wrapper: URL) throws -> URL {
        _ = try run(URL(fileURLWithPath: "/bin/chmod"), ["+x", wrapper.path])
        _ = try run(wrapper, ["assembleDebug"], directory: wrapper.deletingLastPathComponent())
        guard let apk = newestApk(under: wrapper.deletingLastPathComponent()) else {
            throw BridgeError.message("Gradle завершился, но APK не найден.")
        }
        return apk
    }

    private func newestApk(under root: URL) -> URL? {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .isRegularFileKey]
        let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys)?
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension.lowercased() == "apk" && !$0.path.contains("androidTest") }
        return files?.max {
            let left = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let right = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return left < right
        }
    }

    private func find(named name: String, under root: URL) -> URL? {
        if root.lastPathComponent == name { return root }
        return FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey])?
            .compactMap { $0 as? URL }.first { $0.lastPathComponent == name }
    }

    private func run(_ executable: URL, _ arguments: [String], directory: URL? = nil) throws -> String {
        let process = Process()
        process.executableURL = executable
        process.arguments = arguments
        process.currentDirectoryURL = directory
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let output = String(decoding: data, as: UTF8.self)
        guard process.terminationStatus == 0 else { throw BridgeError.message(output.suffix(4000).description) }
        return output
    }

}
