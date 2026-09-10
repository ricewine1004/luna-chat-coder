using System.Diagnostics;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace MyDeskAI.Windows;

public sealed class MainForm : Form
{
    private const string AppVersion = "0.3.8";
    private const string AppBaseUrl = "https://mydesk-ai.mydesk-ai.workers.dev";
    private const string AppHost = "mydesk-ai.mydesk-ai.workers.dev";
    private readonly WebView2 webView = new();

    public MainForm()
    {
        Text = "MyDesk AI";
        StartPosition = FormStartPosition.CenterScreen;
        Width = 1280;
        Height = 860;
        MinimumSize = new Size(900, 650);
        BackColor = Color.FromArgb(243, 240, 255);
        AutoScaleMode = AutoScaleMode.Dpi;

        try
        {
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        }
        catch
        {
            // The installer still supplies the application icon.
        }

        webView.Dock = DockStyle.Fill;
        webView.DefaultBackgroundColor = Color.FromArgb(243, 240, 255);
        Controls.Add(webView);
    }

    protected override async void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        await InitializeBrowserAsync();
    }

    private async Task InitializeBrowserAsync()
    {
        try
        {
            string profileDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MyDesk AI", "WebView2");
            Directory.CreateDirectory(profileDir);

            var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: profileDir);
            await webView.EnsureCoreWebView2Async(environment);

            var core = webView.CoreWebView2;
            var settings = core.Settings;
            settings.UserAgent = settings.UserAgent + " MyDeskAI-Windows/" + AppVersion;
            settings.IsStatusBarEnabled = false;
            settings.AreDefaultScriptDialogsEnabled = true;
            settings.AreDefaultContextMenusEnabled = true;
            settings.AreDevToolsEnabled = true;
            settings.IsZoomControlEnabled = true;

            core.NewWindowRequested += (_, args) =>
            {
                args.Handled = true;
                OpenExternal(args.Uri);
            };

            core.NavigationStarting += (_, args) =>
            {
                if (!Uri.TryCreate(args.Uri, UriKind.Absolute, out var uri)) return;
                if (uri.Scheme is not ("http" or "https"))
                {
                    args.Cancel = true;
                    return;
                }

                if (!uri.Host.Equals(AppHost, StringComparison.OrdinalIgnoreCase))
                {
                    args.Cancel = true;
                    OpenExternal(uri.ToString());
                }
            };

            core.NavigationCompleted += async (_, args) =>
            {
                if (!args.IsSuccess) return;
                try
                {
                    await core.ExecuteScriptAsync(
                        "(function(){try{if('serviceWorker' in navigator){navigator.serviceWorker.getRegistrations().then(function(rs){rs.forEach(function(r){try{r.update();}catch(e){}});});}}catch(e){}})();");
                }
                catch
                {
                    // The versioned URL already bypasses stale shell entries.
                }
            };

            core.ProcessFailed += (_, _) => BeginInvoke(new Action(async () =>
            {
                try { await webView.EnsureCoreWebView2Async(); webView.Reload(); } catch { }
            }));

            webView.Source = new Uri($"{AppBaseUrl}/?client=windows&v={AppVersion}");
        }
        catch (WebView2RuntimeNotFoundException)
        {
            MessageBox.Show(
                "MyDesk AI 실행에 Microsoft Edge WebView2 Runtime이 필요합니다.\n\n" +
                "Windows Update 또는 Microsoft Edge 업데이트 후 다시 실행해 주세요.",
                "MyDesk AI - WebView2 필요",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            Close();
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "MyDesk AI를 시작하지 못했습니다.\n\n" + ex.Message,
                "MyDesk AI",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            Close();
        }
    }

    private static void OpenExternal(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return;
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch
        {
            // Ignore external link launch failures; the app itself remains usable.
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) webView.Dispose();
        base.Dispose(disposing);
    }
}
