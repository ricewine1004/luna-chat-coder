namespace MyDeskAI.Windows;

internal static class CommonUpdatePayload
{
    // Verified common UI/server snapshot for MyDesk AI 0.4.2.
    internal const string Version = "0.4.2";
    internal const string Sha256 = "a89b30a2d3e788dc213430b83d8e0ce484db131f72a3baa157d3d1e01a896a2d";
    internal static readonly string ZipBase64 =
        CommonPatch042Part01.Value +
        CommonPatch042Part02.Value +
        CommonPatch042Part03A1.Value +
        CommonPatch042Part03A2.Value +
        CommonPatch042Part03A3.Value +
        CommonPatch042Part03A4.Value +
        CommonPatch042Part03B.Value +
        CommonPatch042Part03C1.Value +
        CommonPatch042Part03C2.Value +
        CommonPatch042Part03C3.Value +
        CommonPatch042Part03C4.Value +
        CommonPatch042Part04.Value +
        CommonPatch042Part05.Value;
}
