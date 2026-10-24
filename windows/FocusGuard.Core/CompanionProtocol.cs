using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace FocusGuard.Core;
public sealed record Approval(int Version, string Action, string PhoneId, string PhoneSpki, string SignerSpki,
    string RequestId, string Nonce, string Phase, string Digest, long Day, long Issued, long Expires,
    long PolicyVersion, Policy? Policy, string Replacement);
public sealed record ApprovalFile(int Version, string Token);
public static class CompanionProtocol {
    public static string Label(string action) => action switch {
        "usb_pair" => "Зарегистрировать флешку восстановления",
        "usb_start_remove" => "Начать ожидание 48 часов для удаления Focus Guard",
        "usb_finish_remove" => "Завершить ожидание и снять управление для удаления",
        "usb_start_replace" => "Начать ожидание 48 часов для замены доверенного человека",
        "usb_finish_replace" => "Завершить ожидание и разрешить привязку нового человека",
        _ => throw new FormatException("Этот запрос не предназначен для USB-восстановления")
    };
    public static Approval Inspect(string token) {
        if (token.Length is < 1 or > Protocol.MaxInput) throw new FormatException("Размер сообщения");
        var parts = token.Split('.');
        if (parts.Length != 3 || Protocol.Decode(parts[2]).Length != 64) throw new FormatException("Подпись ES256");
        string header = Encoding.UTF8.GetString(Protocol.Decode(parts[0])); Protocol.StrictJson(header);
        using var h = JsonDocument.Parse(header);
        if (h.RootElement.EnumerateObject().Count() != 2 || h.RootElement.GetProperty("alg").GetString() != "ES256" || h.RootElement.GetProperty("typ").GetString() != "FG2") throw new FormatException("Нужен новый протокол FG2");
        string payload = Encoding.UTF8.GetString(Protocol.Decode(parts[1])); Protocol.StrictJson(payload);
        using var d = JsonDocument.Parse(payload);
        string[] fields = ["version", "action", "phoneId", "phoneSpki", "signerSpki", "requestId", "nonce", "phase", "digest", "day", "issued", "expires", "policyVersion", "policy", "replacement"];
        if (!d.RootElement.EnumerateObject().Select(p => p.Name).ToHashSet().SetEquals(fields)) throw new FormatException("Поля сообщения");
        var m = JsonSerializer.Deserialize<Approval>(payload, Protocol.Json) ?? throw new FormatException();
        if (m.Version != 2 || m.Phase is not ("request" or "response") || m.PhoneId.Length is < 1 or > 64 || m.RequestId.Length is < 1 or > 64 || Protocol.Decode(m.Nonce).Length != 32 || m.PolicyVersion < 1 || m.Expires <= m.Issued || m.Expires - m.Issued > 86400000) throw new FormatException("Некорректный запрос");
        using var phone = Protocol.PublicKey(m.PhoneSpki); using var signer = Protocol.PublicKey(m.SignerSpki);
        return m;
    }
    public static Approval Verify(string token, string spki) {
        var m = Inspect(token); var p = token.Split('.'); using var key = Protocol.PublicKey(spki);
        if (!key.VerifyData(Encoding.ASCII.GetBytes(p[0] + "." + p[1]), Protocol.Decode(p[2]), HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation)) throw new CryptographicException("Подпись не совпала");
        return m;
    }
    public static string Sign(Approval m, ECDsa key) {
        string body = Protocol.Encode(Encoding.UTF8.GetBytes("{\"alg\":\"ES256\",\"typ\":\"FG2\"}")) + "." + Protocol.Encode(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(m, Protocol.Json)));
        return body + "." + Protocol.Encode(key.SignData(Encoding.ASCII.GetBytes(body), HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation));
    }
    public static string ReadFile(string text) {
        Protocol.StrictJson(text); var f = JsonSerializer.Deserialize<ApprovalFile>(text, Protocol.Json) ?? throw new FormatException();
        if (f.Version != 2) throw new FormatException("Старая версия файла"); return f.Token;
    }
    public static string Respond(string token, string folder, ReadOnlySpan<char> password) {
        var m = Inspect(token); Verify(token, m.PhoneSpki); _ = Label(m.Action);
        if (m.Phase != "request" || m.Digest != "") throw new FormatException("Нужен запрос телефона");
        var metadata = UsbKey.ReadMetadata(folder);
        if (metadata.Role != "main" || m.SignerSpki != metadata.PublicSpki) throw new InvalidOperationException("Выбрана другая флешка. Нужен основной ключ этого телефона");
        string root = Path.GetPathRoot(Path.GetFullPath(folder))!;
        if (!UsbKey.RemovablePresent(root)) throw new IOException("Подключите флешку");
        using var key = UsbKey.Open(folder, password);
        string reply = Sign(m with { Phase = "response", Digest = Protocol.Digest(token) }, key);
        if (!UsbKey.RemovablePresent(root) || UsbKey.ReadMetadata(folder) != metadata) throw new IOException("Флешка извлечена или изменилась");
        return JsonSerializer.Serialize(new ApprovalFile(2, reply), Protocol.Json);
    }
}
