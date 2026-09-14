namespace MyDeskAI.Windows;

internal static class CommonUpdatePayload
{
    internal const string Version = "0.4.1";
    internal static readonly string ZipBase64 =
        CommonUpdatePayloadPart01.Value +
        CommonUpdatePayloadPartRest.Value;
}
