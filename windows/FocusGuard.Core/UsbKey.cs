using System.Security.Cryptography;
using System.Text.Json;

namespace FocusGuard.Core;
public sealed record KeyMetadata(int ContainerVersion, string Role, string KeyId, string PublicSpki, string CreatedUtc);
public static class UsbKey
{
    public const string Folder = "FocusGuardKeys";
    public static KeyMetadata ReadMetadata(string folder) {
        var text = ReadBounded(Path.Combine(folder, "public.json"), 4096);
        Protocol.StrictJson(text);
        var m = JsonSerializer.Deserialize<KeyMetadata>(text, Protocol.Json) ?? throw new FormatException();
        if (m.ContainerVersion != 1 || m.Role is not ("main" or "emergency") || m.KeyId != Protocol.KeyId(m.PublicSpki)) throw new FormatException("Неверные метаданные");
        return m;
    }
    public static string ReadBounded(string path, int limit) {
        using var f = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        if (f.Length > limit) throw new FormatException("Слишком большой файл");
        using var reader = new StreamReader(f); return reader.ReadToEnd();
    }
    public static bool RemovablePresent(string root) {
        try { var d = new DriveInfo(root); return d.IsReady && d.DriveType == DriveType.Removable; } catch { return false; }
    }
    public static string Create(string root, ReadOnlySpan<char> password, string role, Func<bool>? probe = null) {
        if (role is not ("main" or "emergency") || password.Length < 12) throw new ArgumentException("Пароль: минимум 12 символов; роль main/emergency");
        probe ??= () => RemovablePresent(root);
        if (!probe()) throw new IOException("Съёмный носитель отсутствует");
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var spki = Protocol.Encode(key.ExportSubjectPublicKeyInfo());
        var m = new KeyMetadata(1, role, Protocol.KeyId(spki), spki, DateTimeOffset.UtcNow.ToString("O"));
        string parent = Path.Combine(root, Folder);
        if (File.Exists(parent) || (Directory.Exists(parent) && (File.GetAttributes(parent) & FileAttributes.ReparsePoint) != 0)) throw new IOException("Недопустимый каталог ключей");
        Directory.CreateDirectory(parent);
        string folder = Path.Combine(parent, $"{role}-{Guid.NewGuid():N}"); Directory.CreateDirectory(folder);
        byte[] encrypted = key.ExportEncryptedPkcs8PrivateKey(password, new PbeParameters(PbeEncryptionAlgorithm.Aes256Cbc, HashAlgorithmName.SHA256, 600000));
        try {
            using (var f = new FileStream(Path.Combine(folder, "private.p8"), FileMode.CreateNew, FileAccess.Write, FileShare.None)) { f.Write(encrypted); f.Flush(true); }
            using (var f = new FileStream(Path.Combine(folder, "public.json"), FileMode.CreateNew, FileAccess.Write, FileShare.None)) { JsonSerializer.Serialize(f, m, Protocol.Json); f.Flush(true); }
            if (!probe()) throw new IOException("Носитель извлечён");
            using var reread = Open(folder, password);
            byte[] challenge = RandomNumberGenerator.GetBytes(32);
            var signature = reread.SignData(challenge, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
            if (!key.VerifyData(challenge, signature, HashAlgorithmName.SHA256, DSASignatureFormat.IeeeP1363FixedFieldConcatenation) || !probe()) throw new CryptographicException("Пробная подпись не прошла");
            return folder;
        } finally { CryptographicOperations.ZeroMemory(encrypted); }
    }
    public static ECDsa Open(string folder, ReadOnlySpan<char> password) {
        RejectReparsePoints(Path.GetPathRoot(Path.GetFullPath(folder))!, folder);
        var meta = ReadMetadata(folder);
        var path = Path.Combine(folder, "private.p8");
        if (new FileInfo(path).Length > 8192) throw new FormatException("Слишком большой контейнер");
        byte[] bytes = File.ReadAllBytes(path);
        var key = ECDsa.Create();
        try {
            key.ImportEncryptedPkcs8PrivateKey(password, bytes, out int read);
            if (read != bytes.Length || Protocol.KeyId(Protocol.Encode(key.ExportSubjectPublicKeyInfo())) != meta.KeyId) throw new CryptographicException("Контейнер не соответствует метаданным");
            return key;
        } catch { key.Dispose(); throw; } finally { CryptographicOperations.ZeroMemory(bytes); }
    }
    public static string Sign(string folder, ReadOnlySpan<char> password, Envelope envelope, Func<bool>? probe = null) {
        string root = Path.GetPathRoot(Path.GetFullPath(folder))!;
        probe ??= () => RemovablePresent(root);
        if (!probe()) throw new IOException("Съёмный носитель отсутствует");
        RejectReparsePoints(root, folder);
        var meta = ReadMetadata(folder);
        using var key = Open(folder, password);
        if (!probe()) throw new IOException("USB извлечён до подписи");
        var result = Protocol.Respond(envelope, meta, key);
        // Re-read after crypto: no cached private key and no publication after an observed removal.
        if (!probe() || ReadMetadata(folder) != meta) throw new IOException("Носитель изменился/извлечён");
        using var check = new FileStream(Path.Combine(folder, "private.p8"), FileMode.Open, FileAccess.Read, FileShare.Read);
        if (check.Length == 0 || !probe()) throw new IOException("USB извлечён");
        return result;
    }
    public static void Backup(string source, string targetRoot, Func<bool>? targetProbe = null) {
        targetProbe ??= () => RemovablePresent(targetRoot);
        if (!RemovablePresent(Path.GetPathRoot(Path.GetFullPath(source))!) || !targetProbe()) throw new IOException("Нужны два съёмных носителя");
        if (Path.GetPathRoot(source) == Path.GetPathRoot(targetRoot)) throw new IOException("Выберите другой носитель");
        RejectReparsePoints(Path.GetPathRoot(source)!, source);
        var meta = ReadMetadata(source);
        string parent = Path.Combine(targetRoot, Folder);
        if (File.Exists(parent) || Directory.Exists(parent) && (File.GetAttributes(parent) & FileAttributes.ReparsePoint) != 0) throw new IOException("Недопустимый каталог назначения");
        string dest = Path.Combine(parent, $"backup-{Guid.NewGuid():N}"); Directory.CreateDirectory(dest);
        foreach (var name in new[] { "private.p8", "public.json" }) {
            byte[] bytes = File.ReadAllBytes(Path.Combine(source, name));
            try {
                using (var f = new FileStream(Path.Combine(dest, name), FileMode.CreateNew, FileAccess.Write, FileShare.None)) { f.Write(bytes); f.Flush(true); }
                if (!targetProbe() || !SHA256.HashData(bytes).SequenceEqual(SHA256.HashData(File.ReadAllBytes(Path.Combine(dest, name))))) throw new IOException("Проверка резервного носителя не прошла");
            } finally { CryptographicOperations.ZeroMemory(bytes); }
        }
        if (ReadMetadata(dest) != meta) throw new IOException("Метаданные копии не совпали");
    }
    private static void RejectReparsePoints(string root, string folder) {
        string full = Path.GetFullPath(folder);
        if (!full.StartsWith(Path.GetFullPath(root), StringComparison.OrdinalIgnoreCase)) throw new IOException("Ключ вне носителя");
        var current = new DirectoryInfo(full);
        while (current != null) { if ((current.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("Ссылки в пути ключа запрещены"); current = current.Parent; }
        foreach (var name in new[] { "private.p8", "public.json" }) if ((File.GetAttributes(Path.Combine(full, name)) & FileAttributes.ReparsePoint) != 0) throw new IOException("Файл ключа является ссылкой");
    }
}
