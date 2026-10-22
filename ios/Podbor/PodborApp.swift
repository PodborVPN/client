import PodborKit
import SwiftUI

/// Каркас приложения: экран встанет поверх общего ViewModel. Пока — строка, доказывающая, что общий код слинкован.
@main
struct PodborApp: App {
    var body: some Scene {
        WindowGroup {
            Text("Podbor · ядро \(String(LineKt.sha256Hex(der: KotlinByteArray(size: 0)).prefix(8)))")
        }
    }
}
