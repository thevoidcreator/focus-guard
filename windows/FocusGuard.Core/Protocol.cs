using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.IdentityModel.Tokens.Jwt;
using Microsoft.IdentityModel.Tokens;

namespace FocusGuard.Core;

public sealed record AppPin(string PackageId, string CertificateSha256);
public sealed record Policy(string Zone = "Europe/Moscow", long SessionMs = 7200000, AppPin[]? Apps = null, string MainSpki = "", string EmergencySpki = "");
public sealed record Message(int ProtocolVersion, string MessageType, string InstallationId, string RequestId,
    string Nonce, string KeyId, long PolicyVersion, string RemovalFlowId, string Phase, string RequestDigest,
    string Role, string PhoneKeyId, string PublicKey, Policy? Proposal);
public sealed record Envelope(int EnvelopeVersion, string PhoneSpki, string Request);
public static class Protocol
{
    public const int MaxInput = 16384;
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) {
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow, MaxDepth = 8,
        PropertyNameCaseInsensitive = false
    };
    public static string Encode(ReadOnlySpan<byte> bytes) => Base64UrlEncoder.Encode(bytes.ToArray());
    public static byte[] Decode(string text) {
        if (text.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '_' or '-'))) throw new FormatException("Неверный base64url");
        var b = Base64UrlEncoder.DecodeBytes(text);
        if (Encode(b) != text) throw new FormatException("Неканонический base64url");
        return b;
    }
    public static string Digest(string exact) => Encode(SHA256.HashData(Encoding.UTF8.GetBytes(exact)));
    public static string KeyId(string spki) { using var key = PublicKey(spki); return Encode(SHA256.HashData(Decode(spki))); }
    public static ECDsa PublicKey(string spki) {
        if (spki.Length > 256) throw new FormatException("Слишком длинный ключ");
        var key = ECDsa.Create();
        try {
            var bytes = Decode(spki); key.ImportSubjectPublicKeyInfo(bytes, out int read);
            if (read != bytes.Length || key.ExportParameters(false).Curve.Oid.Value != "1.2.840.10045.3.1.7") throw new FormatException("Требуется P-256");
            return key;
        } catch { key.Dispose(); throw; }
    }
    public static void StrictJson(string text) {
        if (Encoding.UTF8.GetByteCount(text) > MaxInput) throw new FormatException("Слишком большой JSON");
        using var doc = JsonDocument.Parse(text, new JsonDocumentOptions { MaxDepth = 8 });
        void Check(JsonElement e) {
            if (e.ValueKind == JsonValueKind.Object) {
                var names = new HashSet<string>(StringComparer.Ordinal);
                foreach (var p in e.EnumerateObject()) { if (!names.Add(p.Name)) throw new FormatException("Повторное поле JSON"); Check(p.Value); }
            } else if (e.ValueKind == JsonValueKind.Array) foreach (var v in e.EnumerateArray()) Check(v);
        }
        Check(doc.RootElement);
    }
    public static Message Inspect(string compact) {
        if (compact.Length is < 1 or > MaxInput || compact.Any(c => c < 33 || c > 126)) throw new FormatException("Неверный JWS");
        var parts = compact.Split('.');
        if (parts.Length != 3 || Decode(parts[2]).Length != 64) throw new FormatException("JWS ES256 R||S");
        string header = Encoding.UTF8.GetString(Decode(parts[0])); StrictJson(header);
        using var h = JsonDocument.Parse(header);
        if (h.RootElement.EnumerateObject().Count() != 2 || h.RootElement.GetProperty("alg").GetString() != "ES256" || h.RootElement.GetProperty("typ").GetString() != "FG1") throw new FormatException("Неизвестный заголовок/alg");
        string payload = Encoding.UTF8.GetString(Decode(parts[1])); StrictJson(payload);
        using var payloadDoc = JsonDocument.Parse(payload);
        string[] required = ["protocolVersion", "messageType", "installationId", "requestId", "nonce", "keyId", "phoneKeyId", "policyVersion", "removalFlowId", "phase", "requestDigest", "role", "publicKey", "proposal"];
        if (!payloadDoc.RootElement.EnumerateObject().Select(p => p.Name).ToHashSet(StringComparer.Ordinal).SetEquals(required)) throw new FormatException("Неверный набор полей");
        var m = JsonSerializer.Deserialize<Message>(payload, Json) ?? throw new FormatException();
        if (m.ProtocolVersion != 1 || m.PolicyVersion < 1 || Decode(m.Nonce).Length != 32 ||
            m.MessageType is not ("enrollment" or "removal_start" or "removal_finish" or "emergency_release" or "policy_change") ||
            m.Phase is not ("request_start" or "request_finish" or "response") || m.Role is not ("main" or "emergency") ||
            m.InstallationId.Length is < 1 or > 64 || m.RequestId.Length is < 1 or > 64 || m.RemovalFlowId.Length > 64 ||
            Decode(m.KeyId).Length != 32 || Decode(m.PhoneKeyId).Length != 32 ||
            (m.RequestDigest.Length != 0 && Decode(m.RequestDigest).Length != 32)) throw new FormatException("Неверные поля протокола");
        return m;
    }
    public static string Sign(Message message, ECDsa key) {
        var credentials = new SigningCredentials(new ECDsaSecurityKey(key) { CryptoProviderFactory = new CryptoProviderFactory { CacheSignatureProviders = false } }, SecurityAlgorithms.EcdsaSha256);
        var header = new JwtHeader(credentials); header["typ"] = "FG1";
        var payload = JwtPayload.Deserialize(JsonSerializer.Serialize(message, Json));
        return new JwtSecurityTokenHandler().WriteToken(new JwtSecurityToken(header, payload));
    }
    public static Message Verify(string compact, string publicSpki) {
        var m = Inspect(compact);
        using var key = PublicKey(publicSpki);
        new JwtSecurityTokenHandler { MapInboundClaims = false, MaximumTokenSizeInBytes = MaxInput }.ValidateToken(compact,
            new TokenValidationParameters { ValidateIssuer = false, ValidateAudience = false, ValidateLifetime = false,
                RequireExpirationTime = false, RequireSignedTokens = true, ValidateIssuerSigningKey = true,
                IssuerSigningKey = new ECDsaSecurityKey(key) { CryptoProviderFactory = new CryptoProviderFactory { CacheSignatureProviders = false } },
                CryptoProviderFactory = new CryptoProviderFactory { CacheSignatureProviders = false },
                ValidAlgorithms = [SecurityAlgorithms.EcdsaSha256], ValidTypes = ["FG1"] }, out _);
        return m;
    }
    public static Envelope ParseEnvelope(string text) {
        StrictJson(text); var e = JsonSerializer.Deserialize<Envelope>(text, Json) ?? throw new FormatException();
        if (e.EnvelopeVersion != 1) throw new FormatException("Версия envelope");
        var m = Verify(e.Request, e.PhoneSpki);
        if (m.PhoneKeyId != KeyId(e.PhoneSpki) || m.Phase == "response" || m.RequestDigest != "") throw new FormatException("Не запрос телефона");
        return e;
    }
    public static string Respond(Envelope e, KeyMetadata metadata, ECDsa key) {
        var m = Verify(e.Request, e.PhoneSpki);
        var actualId = KeyId(Encode(key.ExportSubjectPublicKeyInfo()));
        if (m.Phase == "response" || m.KeyId != metadata.KeyId || actualId != metadata.KeyId || m.Role != metadata.Role)
            throw new InvalidOperationException("Другой ключ или роль");
        if ((m.MessageType == "emergency_release") != (metadata.Role == "emergency") && m.MessageType != "enrollment")
            throw new InvalidOperationException("Аварийный и основной пути независимы");
        if (m.MessageType == "enrollment" && m.PublicKey != metadata.PublicSpki) throw new InvalidOperationException("Подмена ключа регистрации");
        return Sign(m with { Phase = "response", RequestDigest = Digest(e.Request) }, key);
    }
}
