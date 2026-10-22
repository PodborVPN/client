import LibXray
import NetworkExtension

/// Тонкое расширение: Xray с готовым конфигом от приложения. Пока каркас — туннель не поднимается.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let reason = "туннель ещё не реализован; \(xrayVersion())"
        completionHandler(NSError(domain: "podbor.tunnel", code: 1, userInfo: [NSLocalizedDescriptionKey: reason]))
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    /// Версия Xray из libXray (ответ Invoke — JSON).
    private func xrayVersion() -> String {
        LibXrayInvoke(#"{"apiVersion":3,"method":"xrayVersion"}"#)
    }
}
