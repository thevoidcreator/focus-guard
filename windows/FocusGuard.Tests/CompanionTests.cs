using System.Security.Cryptography;
using System.Text.Json;
using FocusGuard.Core;
using Xunit;

public class CompanionTests {
    private static Approval Message(ECDsa phone, ECDsa usb) => new(2, "usb_start_remove", "phone", Protocol.Encode(phone.ExportSubjectPublicKeyInfo()), Protocol.Encode(usb.ExportSubjectPublicKeyInfo()), Guid.NewGuid().ToString(), Protocol.Encode(RandomNumberGenerator.GetBytes(32)), "request", "", 1, 1000, 3601000, 1, null, "");
    [Fact] public void RoundTripAndTampering() {
        using var phone = ECDsa.Create(ECCurve.NamedCurves.nistP256); using var usb = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var m = Message(phone, usb); string t = CompanionProtocol.Sign(m, phone);
        Assert.Equal(m, CompanionProtocol.Verify(t, m.PhoneSpki));
        Assert.Throws<CryptographicException>(() => CompanionProtocol.Verify(t, m.SignerSpki));
        string f = JsonSerializer.Serialize(new ApprovalFile(2, t), Protocol.Json);
        Assert.Equal(t, CompanionProtocol.ReadFile(f));
        Assert.ThrowsAny<Exception>(() => CompanionProtocol.Inspect(t + "x"));
        Assert.ThrowsAny<Exception>(() => CompanionProtocol.ReadFile("{\"version\":1,\"token\":\"x\"}"));
        Assert.ThrowsAny<Exception>(() => CompanionProtocol.ReadFile("{\"version\":2,\"token\":\"x\",\"token\":\"y\"}"));
    }
    [Fact] public void UsbDoesNotAcceptGuardianAction() {
        Assert.Throws<FormatException>(() => CompanionProtocol.Label("hour"));
        Assert.Throws<FormatException>(() => CompanionProtocol.Label("remove"));
        Assert.Contains("48", CompanionProtocol.Label("usb_start_replace"));
    }
    [Fact] public void KotlinFg2Signatures() {
        string root = Environment.GetEnvironmentVariable("FOCUSGUARD_REPO") ?? Path.GetFullPath("../../../../../", AppContext.BaseDirectory);
        using var doc = JsonDocument.Parse(File.ReadAllText(Path.Combine(root, "protocol/vectors/fg2-kotlin.json")));
        var d = doc.RootElement;
        string request = d.GetProperty("request").GetString()!, response = d.GetProperty("response").GetString()!;
        var m = CompanionProtocol.Verify(request, d.GetProperty("phoneSpki").GetString()!);
        var r = CompanionProtocol.Verify(response, d.GetProperty("usbSpki").GetString()!);
        Assert.Equal(m with { Phase = "response", Digest = Protocol.Digest(request) }, r);
    }
}
