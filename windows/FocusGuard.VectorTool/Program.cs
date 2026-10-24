using System.Security.Cryptography;
using System.Text.Json;
using FocusGuard.Core;
if (args.Length != 2) throw new ArgumentException("vector <output.json> | container <test-directory>");
if (args[0] == "vector") {
    using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
    string spki = Protocol.Encode(key.ExportSubjectPublicKeyInfo()); string id = Protocol.KeyId(spki);
    var m = new Message(1, "removal_start", "vector-phone", "dotnet-vector", Protocol.Encode(Enumerable.Range(0,32).Select(i => (byte)i).ToArray()), id,
        1, "vector-flow", "request_start", "", "main", id, "", null);
    string compact = Protocol.Sign(m, key);
    File.WriteAllText(args[1], JsonSerializer.Serialize(new { publicSpki = spki, compact, requestDigest = Protocol.Digest(compact) }, Protocol.Json));
} else if (args[0] == "fg2") {
    using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
    string spki = Protocol.Encode(key.ExportSubjectPublicKeyInfo());
    var m = new Approval(2, "usb_start_remove", "interop-phone", spki, spki, "interop-request", Protocol.Encode(RandomNumberGenerator.GetBytes(32)), "request", "", 1, 1000, 3601000, 1, null, "");
    File.WriteAllText(args[1], JsonSerializer.Serialize(new { publicSpki = spki, token = CompanionProtocol.Sign(m, key) }, Protocol.Json));
} else if (args[0] == "container") {
    Directory.CreateDirectory(args[1]);
    string folder = UsbKey.Create(args[1], "interop-test-password".AsSpan(), "main", () => true);
    File.WriteAllText(Path.Combine(args[1], "test-folder.txt"), folder);
} else throw new ArgumentException("Unknown command");
