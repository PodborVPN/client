import PodborKit
import SwiftUI

/// Каркас приложения: экран встанет поверх общего ViewModel. Пока — строка, доказывающая, что общий код слинкован.
/// В общем Kotlin-коде есть свои `Scene`, `Button`, `Link`: рядом с `import PodborKit` типы SwiftUI с теми же именами —
/// только полным именем (`SwiftUI.Scene`).
@main
struct PodborApp: App {
    var body: some SwiftUI.Scene {
        WindowGroup {
            Text("Podbor · ядро \(KitKt.kitCheck())")
        }
    }
}
