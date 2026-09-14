namespace MyDeskAI.Windows;

internal static class CommonUpdatePayload
{
    internal const string Version = "0.4.2";
    internal const string Sha256 = "819813fa82cc68fc844b11e8fca271fbd12ce3483a211bf398884e6db5ab40d6";
    internal static readonly string ZipBase64 =
        CommonPatch042Part01.Value +
        CommonPatch042Part02.Value +
        CommonPatch042Part03.Value +
        CommonPatch042Part04.Value +
        CommonPatch042Part05.Value;
}
