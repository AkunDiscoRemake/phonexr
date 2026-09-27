import SwiftUI
import AppKit

/// A window of the VR home, as the phone reports it.
struct RemoteWindow: Identifiable, Hashable, Decodable {
    let id: String
    let title: String
    let minimized: Bool
    let width: Int
    let height: Int
    let bar: Bool
}

struct RemoteApp: Identifiable, Hashable, Decodable {
    let id: String
    let label: String
}

private struct RemoteState: Decodable {
    let windows: [RemoteWindow]
    let apps: [RemoteApp]
}

/// Talks to PhoneXR's remote control on the phone (http://127.0.0.1:8766 through `adb forward`).
@MainActor
final class VrRemote: ObservableObject {
    nonisolated static let port = 8766
    @Published var windows: [RemoteWindow] = []
    @Published var apps: [RemoteApp] = []
    @Published var selected: String?
    @Published var frame: NSImage?
    @Published var status = "Подключите телефон по USB и откройте VR в PhoneXR."
    @Published var connected = false
    private var polling: Task<Void, Never>?
    private let base = URL(string: "http://127.0.0.1:\(VrRemote.port)")!
    private let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 3
        config.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: config)
    }()

    var current: RemoteWindow? { windows.first { $0.id == selected } }

    func connect(bridge: AndroidBridge, serial: String?) {
        guard let serial else { status = "Телефон не выбран."; return }
        status = "Подключение…"
        Task.detached {
            let result = Result { try bridge.forwardRemote(serial: serial, port: VrRemote.port) }
            await MainActor.run {
                switch result {
                case .success: self.start()
                case .failure(let error): self.status = error.localizedDescription
                }
            }
        }
    }

    func stop() {
        polling?.cancel()
        polling = nil
        connected = false
    }

    private func start() {
        polling?.cancel()
        polling = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                guard let self else { return }
                if tick % 10 == 0 { await self.refreshState() }
                if self.connected, let id = self.selected { await self.refreshFrame(id) }
                tick += 1
                try? await Task.sleep(nanoseconds: 80_000_000)
            }
        }
    }

    private func refreshState() async {
        do {
            let (data, _) = try await session.data(from: base.appendingPathComponent("state"))
            let state = try JSONDecoder().decode(RemoteState.self, from: data)
            windows = state.windows
            apps = state.apps
            connected = true
            status = state.windows.isEmpty ? "VR открыт. Окон пока нет — откройте приложение ниже." : "Подключено к VR"
            if selected == nil || !windows.contains(where: { $0.id == selected }) { selected = windows.first { !$0.minimized }?.id ?? windows.first?.id }
        } catch {
            connected = false
            status = "Нет связи с VR. Откройте «Войти в VR» в PhoneXR на телефоне."
        }
    }

    private func refreshFrame(_ id: String) async {
        var parts = URLComponents(url: base.appendingPathComponent("frame"), resolvingAgainstBaseURL: false)!
        parts.queryItems = [URLQueryItem(name: "id", value: id), URLQueryItem(name: "width", value: "1280")]
        guard let url = parts.url, let (data, response) = try? await session.data(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200, let image = NSImage(data: data) else { return }
        frame = image
    }

    func post(_ path: String, _ items: [String: String]) {
        var parts = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        parts.queryItems = items.map { URLQueryItem(name: $0.key, value: $0.value) }
        guard let url = parts.url else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        session.dataTask(with: request) { _, _, _ in }.resume()
    }

    func touch(_ action: String, _ point: CGPoint) {
        guard let id = selected else { return }
        post("touch", ["id": id, "action": action, "u": String(format: "%.4f", point.x), "v": String(format: "%.4f", point.y)])
    }

    func type(_ text: String) {
        guard let id = selected else { return }
        post("type", ["id": id, "text": text])
    }

    func key(_ name: String) {
        guard let id = selected else { return }
        post("type", ["id": id, "key": name])
    }

    func bar(_ action: String) {
        guard let id = selected else { return }
        post("bar", ["id": id, "action": action])
    }

    func window(_ action: String) {
        guard let id = selected else { return }
        post("window", ["id": id, "action": action])
    }

    func open(_ app: RemoteApp) { post("open", ["entry": app.id]) }

    func openUrl(_ url: String) {
        let text = url.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty { post("url", ["url": text]) }
    }
}

/// The page of PhoneXR Share where the VR home is used from the Mac.
struct VrRemoteView: View {
    @EnvironmentObject var model: ShareModel
    @StateObject private var remote = VrRemote()
    @State private var address = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Label(remote.status, systemImage: remote.connected ? "visionpro.fill" : "visionpro")
                    .foregroundStyle(remote.connected ? .primary : .secondary)
                Spacer()
                Button(remote.connected ? "Переподключить" : "Подключиться") { remote.connect(bridge: model.bridge, serial: model.selectedSerial) }
                    .buttonStyle(.borderedProminent)
            }
            HStack(spacing: 8) {
                TextField("Адрес сайта или поиск — откроется в браузере VR", text: $address)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { remote.openUrl(address) }
                Button("Открыть") { remote.openUrl(address) }
            }
            if !remote.windows.isEmpty {
                Picker("Окно", selection: $remote.selected) {
                    ForEach(remote.windows) { window in
                        Text(window.title + (window.minimized ? " (свёрнуто)" : "")).tag(window.id as String?)
                    }
                }.pickerStyle(.segmented)
                HStack(spacing: 8) {
                    if remote.current?.bar == true {
                        Button { remote.bar("back") } label: { Image(systemName: "chevron.backward") }
                        Button { remote.bar("forward") } label: { Image(systemName: "chevron.forward") }
                        Button { remote.bar("reload") } label: { Image(systemName: "arrow.clockwise") }
                    }
                    Spacer()
                    Button("Показать в VR") { remote.window("focus") }
                    Button("Свернуть") { remote.window("minimize") }
                    Button("Закрыть", role: .destructive) { remote.window("close") }
                }
            }
            ZStack {
                RoundedRectangle(cornerRadius: 18).fill(Color.black.opacity(0.85))
                if let frame = remote.frame, remote.current != nil {
                    RemoteSurface(image: frame, remote: remote)
                        .aspectRatio(CGFloat(remote.current!.width) / CGFloat(max(remote.current!.height, 1)), contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                        .padding(8)
                } else {
                    Text(remote.connected ? "Выберите или откройте окно" : "Нет картинки").foregroundStyle(.secondary)
                }
            }
            .frame(minHeight: 360)
            Text("Мышь работает как палец в VR: клик — нажатие, перетаскивание — прокрутка. Печатайте с клавиатуры Mac, когда картинка в фокусе.")
                .font(.callout).foregroundStyle(.secondary)
            if !remote.apps.isEmpty {
                Text("Приложения VR").font(.headline)
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(remote.apps) { app in Button(app.label) { remote.open(app) } }
                    }
                }
            }
        }
        .onDisappear { remote.stop() }
        .onAppear { if model.selectedSerial != nil { remote.connect(bridge: model.bridge, serial: model.selectedSerial) } }
    }
}

/// The window's picture; mouse and keyboard go to it as touches and typing.
private struct RemoteSurface: NSViewRepresentable {
    let image: NSImage
    let remote: VrRemote

    func makeNSView(context: Context) -> SurfaceView {
        let view = SurfaceView()
        view.remote = remote
        return view
    }

    func updateNSView(_ view: SurfaceView, context: Context) {
        view.image = image
        view.remote = remote
        view.needsDisplay = true
    }

    final class SurfaceView: NSView {
        var image: NSImage?
        weak var remote: VrRemote?
        override var acceptsFirstResponder: Bool { true }
        override var isFlipped: Bool { true }

        override func draw(_ dirtyRect: NSRect) {
            image?.draw(in: bounds)
        }

        private func point(_ event: NSEvent) -> CGPoint {
            let local = convert(event.locationInWindow, from: nil)
            return CGPoint(x: min(max(local.x / bounds.width, 0), 1), y: min(max(local.y / bounds.height, 0), 1))
        }

        override func mouseDown(with event: NSEvent) {
            window?.makeFirstResponder(self)
            let p = point(event)
            MainActor.assumeIsolated { remote?.touch("down", p) }
        }

        override func mouseDragged(with event: NSEvent) {
            let p = point(event)
            MainActor.assumeIsolated { remote?.touch("move", p) }
        }

        override func mouseUp(with event: NSEvent) {
            let p = point(event)
            MainActor.assumeIsolated { remote?.touch("up", p) }
        }

        override func scrollWheel(with event: NSEvent) {
            // A two-finger scroll becomes a short drag in the window.
            let p = point(event)
            let dy = event.scrollingDeltaY / max(bounds.height, 1) * (event.hasPreciseScrollingDeltas ? 1 : 10)
            MainActor.assumeIsolated {
                remote?.touch("down", p)
                remote?.touch("move", CGPoint(x: p.x, y: min(max(p.y + dy, 0), 1)))
                remote?.touch("up", CGPoint(x: p.x, y: min(max(p.y + dy, 0), 1)))
            }
        }

        override func keyDown(with event: NSEvent) {
            MainActor.assumeIsolated {
                switch event.keyCode {
                case 51: remote?.key("backspace")
                case 36, 76: remote?.key("enter")
                default:
                    if let text = event.characters, !text.isEmpty { remote?.type(text) }
                }
            }
        }
    }
}
