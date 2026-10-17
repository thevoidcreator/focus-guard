using System.Security.Cryptography;
using System.Text.Json;
using FocusGuard.Core;
using Xunit;

namespace FocusGuard.Tests;
public sealed class ProtocolTests
{
    private static string Spki(ECDsa k) => Protocol.Encode(k.ExportSubjectPublicKeyInfo());
    private static Message Request(ECDsa phone, ECDsa usb, string type = "removal_start", string role = "main") =>
        new(1, type, "unit-phone", Guid.NewGuid().ToString(), Protocol.Encode(RandomNumberGenerator.GetBytes(32)),
            Protocol.KeyId(Spki(usb)), 1, "flow", "request_start", "", role, Protocol.KeyId(Spki(phone)), "", null);
    [Fact] public void ExactBytesSignatureAndDigest() {
        using var phone = ECDsa.Create(ECCurve.NamedCurves.nistP256); using var usb = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var m = Request(phone, usb); var compact = Protocol.Sign(m, phone);
        Assert.Equal(m, Protocol.Verify(compact, Spki(phone)));
        var meta = new KeyMetadata(1, "main", m.KeyId, Spki(usb), "test");
        var response = Protocol.Respond(new Envelope(1, Spki(phone), compact), meta, usb);
        Assert.Equal(m with { Phase = "response", RequestDigest = Protocol.Digest(compact) }, Protocol.Verify(response, Spki(usb)));
        Assert.ThrowsAny<Exception>(() => Protocol.Verify(response, Spki(phone)));
    }
    [Fact] public void WrongRoleAndDeviceKeyRejected() {
        using var phone = ECDsa.Create(ECCurve.NamedCurves.nistP256); using var usb = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var m = Request(phone, usb, "emergency_release", "emergency");
        var e = new Envelope(1, Spki(phone), Protocol.Sign(m, phone));
        Assert.ThrowsAny<Exception>(() => Protocol.Respond(e, new KeyMetadata(1, "main", m.KeyId, Spki(usb), "test"), usb));
    }
    [Theory][InlineData("{\"a\":1,\"a\":2}")][InlineData("{} {}")][InlineData("[[[[[[[[[[]]]]]]]]]]")]
    public void StrictJsonRejects(string text) => Assert.ThrowsAny<Exception>(() => Protocol.StrictJson(text));
    [Fact] public void DisallowedHeadersAndOversize() {
        using var p = ECDsa.Create(ECCurve.NamedCurves.nistP256); var c = Protocol.Sign(Request(p, p), p); var a = c.Split('.');
        foreach (var header in new[] { "{\"alg\":\"none\",\"typ\":\"FG1\"}", "{\"alg\":\"ES256\",\"typ\":\"FG1\",\"crit\":[]}", "{\"alg\":\"ES256\",\"alg\":\"ES256\",\"typ\":\"FG1\"}" })
            Assert.ThrowsAny<Exception>(() => Protocol.Inspect(Protocol.Encode(System.Text.Encoding.UTF8.GetBytes(header)) + "." + a[1] + "." + a[2]));
        Assert.ThrowsAny<Exception>(() => Protocol.Inspect(new string('A', Protocol.MaxInput + 1)));
        Assert.ThrowsAny<Exception>(() => Protocol.Decode("AB"));
    }
    [Fact] public void RealPkcs8WrongPasswordAndUsbRemoval() {
        string dir = Path.Combine(Path.GetTempPath(), "fg-tests-" + Guid.NewGuid()); Directory.CreateDirectory(dir);
        try {
            var folder = UsbKey.Create(dir, "test-password-123".AsSpan(), "main", () => true);
            using (var key = UsbKey.Open(folder, "test-password-123")) Assert.Equal(256, key.KeySize);
            Assert.ThrowsAny<Exception>(() => UsbKey.Open(folder, "wrong-password"));
            using var phone = ECDsa.Create(ECCurve.NamedCurves.nistP256); using var usb = UsbKey.Open(folder, "test-password-123");
            var m = Request(phone, usb); var envelope = new Envelope(1, Spki(phone), Protocol.Sign(m, phone));
            Assert.ThrowsAny<Exception>(() => UsbKey.Sign(folder, "test-password-123", envelope, () => false));
            int probes = 0;
            Assert.ThrowsAny<Exception>(() => UsbKey.Sign(folder, "test-password-123", envelope, () => ++probes < 2));
            Assert.Equal("response", Protocol.Verify(UsbKey.Sign(folder, "test-password-123", envelope, () => true), Spki(usb)).Phase);
            Assert.Equal(2, Directory.GetFiles(folder).Length);
        } finally { Directory.Delete(dir, true); }
    }
    [Fact] public void GoldenVectorsFromPythonAndKotlin() {
        var root = Environment.GetEnvironmentVariable("FOCUSGUARD_REPO") ?? Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../../"));
        foreach (var name in new[] { "python.json", "kotlin.json" }) {
            using var d = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, "protocol/vectors", name)));
            var p = d.RootElement;
            Assert.Equal(p.GetProperty("requestDigest").GetString(), Protocol.Digest(p.GetProperty("compact").GetString()!));
            Assert.Equal("vector-phone", Protocol.Verify(p.GetProperty("compact").GetString()!, p.GetProperty("publicSpki").GetString()!).InstallationId);
        }
    }
}
