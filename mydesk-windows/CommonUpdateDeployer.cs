using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace MyDeskAI.Windows;

internal sealed record CommonDeployResult(bool Attempted, bool Success, string Message, string LogPath);
internal sealed record OwnerAuthState(string Code, string Hash);

internal static class CommonUpdateDeployer
{
    private const string WorkerBaseUrl = "https://mydesk-ai.mydesk-ai.workers.dev";
    private const string WorkerHealthUrl = WorkerBaseUrl + "/api/health";
    private static readonly string StateDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MyDesk AI");
    private static readonly string MarkerPath = Path.Combine(StateDir, $"common-deploy-{CommonUpdatePayload.Version}.ok");
    private static readonly string ProjectPathCache = Path.Combine(StateDir, "cloudflare-project-path.txt");
    private static readonly string LogPath = Path.Combine(StateDir, $"common-deploy-{CommonUpdatePayload.Version}.log");

    internal static async Task<CommonDeployResult> TryDeployAsync()
    {
        Directory.CreateDirectory(StateDir);

        string? projectDir = FindProjectDirectory();
        if (string.IsNullOrWhiteSpace(projectDir))
            return new CommonDeployResult(false, false,
                "기존 mydesk-ai-predeploy 프로젝트를 찾지 못했습니다. 새 프로젝트는 만들지 않았습니다.", LogPath);

        var log = new StringBuilder();
        log.AppendLine($"[{DateTimeOffset.Now:O}] MyDesk AI common deploy {CommonUpdatePayload.Version}");
        log.AppendLine($"Project: {projectDir}");

        OwnerAuthState auth;
        try
        {
            auth = CaptureOwnerAuth(projectDir, log);
        }
        catch (Exception ex)
        {
            log.AppendLine("AUTH PRECHECK ERROR: " + ex.Message);
            WriteLog(log);
            return new CommonDeployResult(false, false,
                "기존 접속 키를 안전하게 확인하지 못해 배포를 중단했습니다. 서버 키는 변경하지 않았습니다.", LogPath);
        }

        if (File.Exists(MarkerPath)
            && await IsServerVersionCurrentAsync()
            && await VerifyRemoteAuthAsync(auth.Code))
        {
            return new CommonDeployResult(false, true, "이미 적용되어 있고 기존 접속 키도 정상입니다.", LogPath);
        }

        string? npx = FindNpx();
        if (string.IsNullOrWhiteSpace(npx))
            return new CommonDeployResult(true, false,
                "Node.js / npx를 찾지 못해 Cloudflare 자동 배포를 실행하지 못했습니다.", LogPath);

        log.AppendLine($"npx: {npx}");

        try
        {
            ApplyPayload(projectDir, log);
            PatchWranglerVersionAndOwnerHash(projectDir, auth.Hash, log);
            PreserveOwnerFiles(projectDir, auth, log);
            File.WriteAllText(ProjectPathCache, projectDir, Encoding.UTF8);

            var who = await RunHiddenAsync(npx, "--yes wrangler whoami", projectDir, 45_000);
            log.AppendLine("--- wrangler whoami ---");
            log.AppendLine(who.Output);
            if (who.ExitCode != 0)
            {
                WriteLog(log);
                return new CommonDeployResult(true, false,
                    "Cloudflare 로그인 정보를 확인하지 못했습니다. 기존 접속 키와 서버 파일은 그대로 보존했습니다.", LogPath);
            }

            var deploy = await RunHiddenAsync(npx, "--yes wrangler deploy", projectDir, 180_000);
            log.AppendLine("--- wrangler deploy ---");
            log.AppendLine(deploy.Output);
            if (deploy.ExitCode != 0)
            {
                WriteLog(log);
                return new CommonDeployResult(true, false,
                    "Cloudflare 배포가 완료되지 않았습니다. 기존 접속 키는 변경하지 않았습니다.", LogPath);
            }

            for (int i = 0; i < 10; i++)
            {
                bool versionOk = await IsServerVersionCurrentAsync();
                bool authOk = await VerifyRemoteAuthAsync(auth.Code);
                if (versionOk && authOk)
                {
                    File.WriteAllText(MarkerPath, DateTimeOffset.Now.ToString("O"), Encoding.UTF8);
                    log.AppendLine("Health check: PASS");
                    log.AppendLine("Existing owner key verification: PASS");
                    WriteLog(log);
                    return new CommonDeployResult(true, true,
                        $"공통 업데이트 {CommonUpdatePayload.Version} 적용과 기존 접속 키 복구를 완료했습니다.", LogPath);
                }
                await Task.Delay(2_000);
            }

            log.AppendLine("POST DEPLOY VERIFY FAILED");
            WriteLog(log);
            return new CommonDeployResult(true, false,
                "배포는 실행됐지만 기존 접속 키 확인이 완료되지 않았습니다. 새 키는 만들지 않았습니다.", LogPath);
        }
        catch (Exception ex)
        {
            log.AppendLine("ERROR: " + ex);
            WriteLog(log);
            return new CommonDeployResult(true, false,
                "공통 업데이트 중 오류가 발생했습니다. 기존 접속 키는 보존했고 서버 파일은 백업해 두었습니다.", LogPath);
        }
    }

    private static OwnerAuthState CaptureOwnerAuth(string projectDir, StringBuilder log)
    {
        string codePath = Path.Combine(projectDir, "PRIVATE_OWNER_CODE.txt");
        string wranglerPath = Path.Combine(projectDir, "wrangler.jsonc");
        if (!File.Exists(codePath))
            throw new FileNotFoundException("PRIVATE_OWNER_CODE.txt not found", codePath);
        if (!File.Exists(wranglerPath))
            throw new FileNotFoundException("wrangler.jsonc not found", wranglerPath);

        string code = File.ReadAllText(codePath, Encoding.UTF8).Trim().Trim('\uFEFF');
        if (string.IsNullOrWhiteSpace(code) || code.Length < 12)
            throw new InvalidOperationException("Existing owner code is invalid.");

        string hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(code))).ToLowerInvariant();
        string config = File.ReadAllText(wranglerPath, Encoding.UTF8);
        string? configuredHash = ExtractOwnerHash(config);

        if (string.Equals(hash, configuredHash, StringComparison.OrdinalIgnoreCase))
            log.AppendLine("Existing owner key/hash pair: VERIFIED");
        else
            log.AppendLine("Existing owner hash mismatch detected: will restore hash from PRIVATE_OWNER_CODE.txt without changing the code.");

        return new OwnerAuthState(code, hash);
    }

    private static string? ExtractOwnerHash(string config)
    {
        var match = Regex.Match(config,
            "\\\"OWNER_KEY_HASH\\\"\\s*:\\s*\\\"(?<hash>[0-9a-fA-F]{64})\\\"",
            RegexOptions.IgnoreCase);
        return match.Success ? match.Groups["hash"].Value : null;
    }

    private static void PreserveOwnerFiles(string projectDir, OwnerAuthState auth, StringBuilder log)
    {
        string codePath = Path.Combine(projectDir, "PRIVATE_OWNER_CODE.txt");
        string hashPath = Path.Combine(projectDir, "OWNER_KEY_HASH.txt");

        // Never generate or rotate the code during an update. Re-write the same value only so BOM/whitespace cannot alter its digest.
        File.WriteAllText(codePath, auth.Code, new UTF8Encoding(false));
        File.WriteAllText(hashPath, auth.Hash, Encoding.ASCII);
        log.AppendLine("Owner code preserved; owner hash synchronized.");
    }

    private static void ApplyPayload(string projectDir, StringBuilder log)
    {
        string stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
        string backupRoot = Path.Combine(projectDir, ".mydesk-backup", $"{CommonUpdatePayload.Version}-{stamp}");
        Directory.CreateDirectory(backupRoot);

        foreach (string important in new[] { "wrangler.jsonc", "PRIVATE_OWNER_CODE.txt", "OWNER_KEY_HASH.txt" })
        {
            string source = Path.Combine(projectDir, important);
            if (!File.Exists(source)) continue;
            string backup = Path.Combine(backupRoot, important);
            Directory.CreateDirectory(Path.GetDirectoryName(backup)!);
            File.Copy(source, backup, true);
        }

        byte[] bytes = Convert.FromBase64String(CommonUpdatePayload.ZipBase64);
        using var ms = new MemoryStream(bytes);
        using var archive = new ZipArchive(ms, ZipArchiveMode.Read);

        foreach (var entry in archive.Entries)
        {
            if (string.IsNullOrEmpty(entry.Name)) continue;
            string relative = entry.FullName.Replace('/', Path.DirectorySeparatorChar);

            // Auth/config files are never supplied by the common payload.
            if (relative.Equals("wrangler.jsonc", StringComparison.OrdinalIgnoreCase)
                || relative.Equals("PRIVATE_OWNER_CODE.txt", StringComparison.OrdinalIgnoreCase)
                || relative.Equals("OWNER_KEY_HASH.txt", StringComparison.OrdinalIgnoreCase))
                continue;

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

    private static void PatchWranglerVersionAndOwnerHash(string projectDir, string ownerHash, StringBuilder log)
    {
        string path = Path.Combine(projectDir, "wrangler.jsonc");
        if (!File.Exists(path)) throw new FileNotFoundException("wrangler.jsonc not found", path);

        string text = File.ReadAllText(path, Encoding.UTF8);
        string updated = text;

        var versionRegex = new Regex("\\\"APP_VERSION\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", RegexOptions.IgnoreCase);
        if (versionRegex.IsMatch(updated))
            updated = versionRegex.Replace(updated, $"\"APP_VERSION\": \"{CommonUpdatePayload.Version}\"", 1);
        else
        {
            var varsRegex = new Regex("\\\"vars\\\"\\s*:\\s*\\{", RegexOptions.IgnoreCase);
            if (!varsRegex.IsMatch(updated))
                throw new InvalidOperationException("wrangler vars section not found.");
            updated = varsRegex.Replace(updated,
                $"\"vars\": {{\n    \"APP_VERSION\": \"{CommonUpdatePayload.Version}\",", 1);
        }

        var ownerRegex = new Regex("\\\"OWNER_KEY_HASH\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", RegexOptions.IgnoreCase);
        if (ownerRegex.IsMatch(updated))
            updated = ownerRegex.Replace(updated, $"\"OWNER_KEY_HASH\": \"{ownerHash}\"", 1);
        else
        {
            var varsRegex = new Regex("\\\"vars\\\"\\s*:\\s*\\{", RegexOptions.IgnoreCase);
            updated = varsRegex.Replace(updated,
                $"\"vars\": {{\n    \"OWNER_KEY_HASH\": \"{ownerHash}\",", 1);
        }

        File.WriteAllText(path, updated, new UTF8Encoding(false));
        log.AppendLine("Updated APP_VERSION; preserved OWNER_KEY_HASH from existing PRIVATE_OWNER_CODE.txt.");
    }

    private static string? FindProjectDirectory()
    {
        var candidates = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        void AddCandidate(string? path)
        {
            if (string.IsNullOrWhiteSpace(path)) return;
            try
            {
                string full = Path.GetFullPath(path);
                if (IsMyDeskProject(full)) candidates.Add(full);
            }
            catch { }
        }

        if (File.Exists(ProjectPathCache))
        {
            try { AddCandidate(File.ReadAllText(ProjectPathCache).Trim()); } catch { }
        }

        string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        string documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        string downloads = Path.Combine(user, "Downloads");
        string? oneDrive = Environment.GetEnvironmentVariable("OneDrive");

        foreach (var root in new[] { desktop, documents, downloads, user, oneDrive, AppContext.BaseDirectory })
        {
            if (string.IsNullOrWhiteSpace(root) || !Directory.Exists(root)) continue;
            AddCandidate(root);
            AddCandidate(Path.Combine(root, "mydesk-ai-predeploy"));
            AddCandidate(Path.Combine(root, "mydesk-ai"));
            FindProjects(root, 4, candidates);
        }

        // The original company deployment may live on D:\ or another data drive.
        foreach (var drive in DriveInfo.GetDrives())
        {
            try
            {
                if (!drive.IsReady) continue;
                string root = drive.RootDirectory.FullName;
                AddCandidate(Path.Combine(root, "mydesk-ai-predeploy"));

                foreach (string top in Directory.EnumerateDirectories(root))
                {
                    string name = Path.GetFileName(top);
                    if (!name.Contains("mydesk", StringComparison.OrdinalIgnoreCase)
                        && !name.Contains("predeploy", StringComparison.OrdinalIgnoreCase))
                        continue;

                    AddCandidate(top);
                    AddCandidate(Path.Combine(top, "mydesk-ai-predeploy"));
                    AddCandidate(Path.Combine(top, "mydesk-ai"));
                }
            }
            catch { }
        }

        return candidates
            .OrderByDescending(ProjectScore)
            .ThenBy(path => path.Length)
            .FirstOrDefault();
    }

    private static void FindProjects(string root, int maxDepth, HashSet<string> results)
    {
        var queue = new Queue<(string Path, int Depth)>();
        queue.Enqueue((root, 0));
        while (queue.Count > 0)
        {
            var (dir, depth) = queue.Dequeue();
            if (IsMyDeskProject(dir)) results.Add(Path.GetFullPath(dir));
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
    }

    private static int ProjectScore(string dir)
    {
        int score = 0;
        string name = Path.GetFileName(dir.TrimEnd(Path.DirectorySeparatorChar));
        if (name.Equals("mydesk-ai-predeploy", StringComparison.OrdinalIgnoreCase)) score += 2000;
        if (dir.Contains("predeploy", StringComparison.OrdinalIgnoreCase)) score += 500;
        if (name.Equals("MyDesk AI", StringComparison.OrdinalIgnoreCase)) score -= 500;

        string codePath = Path.Combine(dir, "PRIVATE_OWNER_CODE.txt");
        string hashPath = Path.Combine(dir, "OWNER_KEY_HASH.txt");
        if (File.Exists(codePath)) score += 900;
        if (File.Exists(hashPath)) score += 250;

        try
        {
            if (File.Exists(codePath))
            {
                string code = File.ReadAllText(codePath, Encoding.UTF8).Trim().Trim('\uFEFF');
                string computed = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(code))).ToLowerInvariant();
                string config = File.ReadAllText(Path.Combine(dir, "wrangler.jsonc"), Encoding.UTF8);
                string? configured = ExtractOwnerHash(config);
                if (string.Equals(computed, configured, StringComparison.OrdinalIgnoreCase)) score += 2500;

                if (File.Exists(hashPath))
                {
                    string fileHash = File.ReadAllText(hashPath, Encoding.ASCII).Trim();
                    if (string.Equals(computed, fileHash, StringComparison.OrdinalIgnoreCase)) score += 750;
                }
            }
        }
        catch { }

        return score;
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
                && (config.Contains("\"binding\": \"DB\"", StringComparison.OrdinalIgnoreCase)
                    || config.Contains("mydesk-ai-db", StringComparison.OrdinalIgnoreCase)
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

    private static async Task<bool> VerifyRemoteAuthAsync(string ownerCode)
    {
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            using var request = new HttpRequestMessage(HttpMethod.Get,
                WorkerBaseUrl + "/api/sync?since=1970-01-01T00%3A00%3A00.000Z&verify=1&cb=" +
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + ownerCode);
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
            return (int)response.StatusCode >= 200 && (int)response.StatusCode < 300;
        }
        catch { return false; }
    }

    private static void WriteLog(StringBuilder log)
    {
        try { File.WriteAllText(LogPath, log.ToString(), Encoding.UTF8); } catch { }
    }
}
