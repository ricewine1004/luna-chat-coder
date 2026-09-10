namespace MyDeskAI.Windows;

internal static class CommonUpdatePayload
{
    internal const string Version = "0.3.9";
    internal static readonly string ZipBase64 =
        CommonUpdatePayloadPart01.Value +
        CommonUpdatePayloadPart02.Value +
        CommonUpdatePayloadPart03.Value +
        CommonUpdatePayloadPart04.Value +
        CommonUpdatePayloadPart05.Value +
        CommonUpdatePayloadPart06.Value +
        CommonUpdatePayloadPart07.Value +
        CommonUpdatePayloadPart08.Value +
        CommonUpdatePayloadPart09.Value;
}
