namespace MyDeskAI.Windows;

internal static class CommonUpdatePayload
{
    internal const string Version = "0.4.2";
    internal const string Sha256 = "a89b30a2d3e788dc213430b83d8e0ce484db131f72a3baa157d3d1e01a896a2d";
    internal static readonly string ZipBase64 =
        CommonUpdatePayloadPart01.Value +
        CommonUpdatePayloadPartRest.Value;
}
