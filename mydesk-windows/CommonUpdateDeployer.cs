using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Text;
using System.Text.RegularExpressions;

namespace MyDeskAI.Windows;

internal sealed record CommonDeployResult(bool Attempted, bool Success, string Message, string LogPath);

internal static class CommonUpdateDeployer
{
    private const string WorkerHealthUrl = "https://mydesk-ai.mydesk-ai.workers.dev/api/health";
    private static readonly string StateDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MyDesk AI");
    private static readonly string MarkerPath = Path.Combine(StateDir, $"common-deploy-{CommonUpdatePayload.Version}.ok");
    private static readonly string ProjectPathCache = Path.Combine(StateDir, "cloudflare-project-path.txt");
    private static readonly string LogPath = Path.Combine(StateDir, $"common-deploy-{CommonUpdatePayload.Version}.log");

    internal static async Task<CommonDeployResult> TryDeployAsync()
    {
        Directory.CreateDirectory(StateDir);

        if (File.Exists(MarkerPath))
            return new CommonDeployResult(false, true, "이미 적용됨", LogPath);

        if (await IsServerVersionCurrentAsync())
        {
            File.WriteAllText(MarkerPath, DateTimeOffset.Now.ToString("O"), Encoding.UTF8);
            return new CommonDeployResult(false, true, "서버가 이미 최신 버전입니다.", LogPath);
        }

        string? projectDir = FindProjectDirectory();
        if (string.IsNullOrWhiteSpace(projectDir))
            return new CommonDeployResult(false, false, "이 PC에서는 기존 MyDesk AI Cloudflare 프로젝트를 찾지 못했습니다.", LogPath);

        string? npx = FindNpx();
        if (string.IsNullOrWhiteSpace(npx))
            return new CommonDeployResult(true, false, "Node.js / npx를 찾지 못해 Cloudflare 자동 배포를 실행하지 못했습니다.", LogPath);

        var log = new StringBuilder();
        log.AppendLine($"[{DateTimeOffset.Now:O}] MyDesk AI common deploy {CommonUpdatePayload.Version}");
        log.AppendLine($"Project: {projectDir}");
        log.AppendLine($"npx: {npx}");

        try
        {
            ApplyPayload(projectDir, log);
            PatchWranglerVersion(projectDir, log);
            File.WriteAllText(ProjectPathCache, projectDir, Encoding.UTF8);

            var who = await RunHiddenAsync(npx, "--yes wrangler whoami", projectDir, 45_000);
            log.AppendLine("--- wrangler whoami ---");
            log.AppendLine(who.Output);
            if (who.ExitCode != 0)
            {
                WriteLog(log);
                return new CommonDeployResult(true, false,
                    "Cloudflare 로그인 정보를 확인하지 못했습니다. 회사 PC에서 이전 Cloudflare 로그인이 남아 있는지 확인이 필요합니다.", LogPath);
            }

            var deploy = await RunHiddenAsync(npx, "--yes wrangler deploy", projectDir, 180_000);
            log.AppendLine("--- wrangler deploy ---");
            log.AppendLine(deploy.Output);
            if (deploy.ExitCode != 0)
            {
                WriteLog(log);
                return new CommonDeployResult(true, false,
                    "Cloudflare 배포가 완료되지 않았습니다. PC 프로그램은 업데이트되었고 서버 배포 로그를 저장했습니다.", LogPath);
            }

            for (int i = 0; i < 6; i++)
            {
                if (await IsServerVersionCurrentAsync())
                {
                    File.WriteAllText(MarkerPath, DateTimeOffset.Now.ToString("O"), Encoding.UTF8);
                    log.AppendLine("Health check: PASS");
                    WriteLog(log);
                    return new CommonDeployResult(true, true,
                        $"PC 업데이트와 Cloudflare 공통 업데이트 {CommonUpdatePayload.Version} 적용을 완료했습니다.", LogPath);
                }
                await Task.Delay(2_000);
            }

            File.WriteAllText(MarkerPath, DateTimeOffset.Now.ToString("O"), Encoding.UTF8);
            log.AppendLine("Health check: delayed, wrangler deploy succeeded");
            WriteLog(log);
            return new CommonDeployResult(true, true,
                "Cloudflare 배포는 완료되었습니다. 서버 반영 확인이 조금 늦어질 수 있습니다.", LogPath);
        }
        catch (Exception ex)
        {
            log.AppendLine("ERROR: " + ex);
            WriteLog(log);
            return new CommonDeployResult(true, false,
                "공통 업데이트 자동 적용 중 오류가 발생했습니다. 기존 서버 파일은 백업해 두었습니다.", LogPath);
        }
    }

    private static void ApplyPayload(string projectDir, StringBuilder log)
    {
        string stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
        string backupRoot = Path.Combine(projectDir, ".mydesk-backup", $"{CommonUpdatePayload.Version}-{stamp}");
        Directory.CreateDirectory(backupRoot);

        byte[] bytes = Convert.FromBase64String(CommonUpdatePayload.ZipBase64);
        using var ms = new MemoryStream(bytes);
        using var archive = new ZipArchive(ms, ZipArchiveMode.Read);

        foreach (var entry in archive.Entries)
        {
            if (string.IsNullOrEmpty(entry.Name)) continue;
            string relative = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
            string destination = Path.GetFullPath(Path.Combine(projectDir, relative));
            string projectFull = Path.GetFullPath(projectDir) + Path.DirectorySeparatorChar;
            if (!destination.StartsWith(projectFull, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Invalid update payload path.");

            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            if (File.Exists(destination))
            {
                string backup = Path.Combine(backupRoot, relative);
                Directory.CreateDirectory(Path.GetDirectoryName(backup)!);
                File.Copy(destination, backup, true);
            }

            using var input = entry.Open();
            using var output = File.Create(destination);
            input.CopyTo(output);
            log.AppendLine("Updated: " + relative);
        }

        string[] required =
        {
            Path.Combine(projectDir, "src", "index.js"),
            Path.Combine(projectDir, "public", "index.html"),
            Path.Combine(projectDir, "public", "app.js"),
            Path.Combine(projectDir, "public", "styles.css"),
            Path.Combine(projectDir, "public", "sw.js")
        };
        if (required.Any(path => !File.Exists(path)))
            throw new InvalidOperationException("Required MyDesk AI project files are missing after update.");
    }

    private static void PatchWranglerVersion(string projectDir, StringBuilder log)
    {
        string path = Path.Combine(projectDir, "wrangler.jsonc");
        if (!File.Exists(path)) throw new FileNotFoundException("wrangler.jsonc not found", path);

        string text = File.ReadAllText(path, Encoding.UTF8);
        string updated;
        var versionRegex = new Regex("\\\"APP_VERSION\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", RegexOptions.IgnoreCase);
        if (versionRegex.IsMatch(text))
        {
            updated = versionRegex.Replace(text, $"\"APP_VERSION\": \"{CommonUpdatePayload.Version}\"", 1);
        }
        else
        {
            var varsRegex = new Regex("\\\"vars\\\"\\s*:\\s*\\{", RegexOptions.IgnoreCase);
            updated = varsRegex.IsMatch(text)
                ? varsRegex.Replace(text, $"\"vars\": {{\n    \"APP_VERSION\": \"{CommonUpdatePayload.Version}\",", 1)
                : text;
        }

        if (!string.Equals(updated, text, StringComparison.Ordinal))
        {
            File.WriteAllText(path, updated, new UTF8Encoding(false));
            log.AppendLine("Updated wrangler APP_VERSION.");
        }
    }

    private static string? FindProjectDirectory()
    {
        if (File.Exists(ProjectPathCache))
        {
            string cached = File.ReadAllText(ProjectPathCache).Trim();
            if (IsMyDeskProject(cached)) return cached;
        }

        var directCandidates = new List<string>();
        string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        string documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        string downloads = Path.Combine(user, "Downloads");
        string? oneDrive = Environment.GetEnvironmentVariable("OneDrive");

        foreach (var root in new[] { desktop, documents, downloads, user, oneDrive, AppContext.BaseDirectory })
        {
            if (string.IsNullOrWhiteSpace(root) || !Directory.Exists(root)) continue;
            directCandidates.Add(root);
            directCandidates.Add(Path.Combine(root, "mydesk-ai-predeploy"));
            directCandidates.Add(Path.Combine(root, "mydesk-ai"));
            directCandidates.Add(Path.Combine(root, "MyDesk AI"));
        }

        foreach (string candidate in directCandidates.Distinct(StringComparer.OrdinalIgnoreCase))
            if (IsMyDeskProject(candidate)) return Path.GetFullPath(candidate);

        foreach (var root in new[] { desktop, documents, downloads, oneDrive })
        {
            if (string.IsNullOrWhiteSpace(root) || !Directory.Exists(root)) continue;
            string? found = SearchProject(root, 4);
            if (found != null) return found;
        }

        return null;
    }

    private static string? SearchProject(string root, int maxDepth)
    {
        var queue = new Queue<(string Path, int Depth)>();
        queue.Enqueue((root, 0));
        while (queue.Count > 0)
        {
            var (dir, depth) = queue.Dequeue();
            if (IsMyDeskProject(dir)) return Path.GetFullPath(dir);
            if (depth >= maxDepth) continue;

            try
            {
                foreach (string sub in Directory.EnumerateDirectories(dir))
                {
                    string name = Path.GetFileName(sub);
                    if (name.Equals("node_modules", StringComparison.OrdinalIgnoreCase)
                        || name.Equals(".git", StringComparison.OrdinalIgnoreCase)
                        || name.Equals("AppData", StringComparison.OrdinalIgnoreCase)
                        || name.StartsWith(".", StringComparison.Ordinal)) continue;
                    queue.Enqueue((sub, depth + 1));
                }
            }
            catch { }
        }
        return null;
    }

    private static bool IsMyDeskProject(string? dir)
    {
        if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir)) return false;
        string wrangler = Path.Combine(dir, "wrangler.jsonc");
        string index = Path.Combine(dir, "src", "index.js");
        string app = Path.Combine(dir, "public", "app.js");
        if (!File.Exists(wrangler) || !File.Exists(index) || !File.Exists(app)) return false;
        try
        {
            string config = File.ReadAllText(wrangler);
            return config.Contains("mydesk-ai", StringComparison.OrdinalIgnoreCase)
                && (config.Contains("mydesk-ai-db", StringComparison.OrdinalIgnoreCase)
                    || config.Contains("a3bd9b1e-537d-4abe-82d8-8abb668d5453", StringComparison.OrdinalIgnoreCase));
        }
        catch { return false; }
    }

    private static string? FindNpx()
    {
        var candidates = new List<string>();
        string? programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        string? appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        if (!string.IsNullOrWhiteSpace(programFiles)) candidates.Add(Path.Combine(programFiles, "nodejs", "npx.cmd"));
        if (!string.IsNullOrWhiteSpace(appData)) candidates.Add(Path.Combine(appData, "npm", "npx.cmd"));

        string path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (string part in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try { candidates.Add(Path.Combine(part.Trim().Trim('"'), "npx.cmd")); } catch { }
        }

        return candidates.FirstOrDefault(File.Exists);
    }

    private static async Task<(int ExitCode, string Output)> RunHiddenAsync(string npxPath, string arguments, string workDir, int timeoutMs)
    {
        string comspec = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe";
        string command = $"\"\"{npxPath}\" {arguments}\"";
        var psi = new ProcessStartInfo
        {
            FileName = comspec,
            Arguments = $"/d /s /c {command}",
            WorkingDirectory = workDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };

        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Unable to start deployment process.");
        Task<string> stdout = process.StandardOutput.ReadToEndAsync();
        Task<string> stderr = process.StandardError.ReadToEndAsync();
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            await process.WaitForExitAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(true); } catch { }
            return (-1, "Deployment command timed out.");
        }

        string output = (await stdout) + Environment.NewLine + (await stderr);
        return (process.ExitCode, output.Trim());
    }

    private static async Task<bool> IsServerVersionCurrentAsync()
    {
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
            string body = await client.GetStringAsync(WorkerHealthUrl + "?cb=" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            return body.Contains($"\"version\":\"{CommonUpdatePayload.Version}\"", StringComparison.OrdinalIgnoreCase)
                || body.Contains($"\"version\": \"{CommonUpdatePayload.Version}\"", StringComparison.OrdinalIgnoreCase);
        }
        catch { return false; }
    }

    private static void WriteLog(StringBuilder log)
    {
        try { File.WriteAllText(LogPath, log.ToString(), Encoding.UTF8); } catch { }
    }
}
