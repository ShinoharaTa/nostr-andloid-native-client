import SwiftUI
import ComposeApp

/// Compose Multiplatform の UIViewController（commonMain の App()）を SwiftUI に載せる薄いブリッジ。
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            // Android のエッジToエッジと揃える: Compose に全面を任せ、セーフエリア/キーボードは
            // Compose 側の WindowInsets(systemBars/ime) で扱う（SwiftUI 側で二重に inset しない）。
            .ignoresSafeArea()
            // [#200] nostr: ディープリンク。開かれた URL を Kotlin の handleDeepLink へ委譲し、
            // Android の ACTION_VIEW と同じく ExternalIntents に流す（consume は App 側）。
            .onOpenURL { url in
                MainViewControllerKt.handleDeepLink(url: url.absoluteString)
            }
    }
}
