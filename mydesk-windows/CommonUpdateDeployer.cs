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
        var log = new StringBuilder();
        log.AppendLine($"[{DateTimeOffset.Now:O}] MyDesk AI common deploy {CommonUpdatePayload.Version}");

        string? projectDir = FindProjectDirectory(log);
        if (string.IsNullOrWhiteSpace(projectDir))
        {
            log.AppendLine("Project selection: FAILED");
            WriteLog(log);
            return new CommonDeployResult(false, false,
                "기존 mydesk-ai-predeploy 프로젝트를 찾지 못했습니다. 새 프로젝트나 새 접속 키는 만들지 않았습니다.", LogPath);
        }
        log.AppendLine($"Project: {projectDir}");

        OwnerAuthState auth;
        try
        {
            auth = CaptureOwnerAuth(projectDir, log);
        }
        catch (Exception ex)
        {
            log.AppendLine("AUTH PRECHECK ERROR: " + ex);
            WriteLog(log);
            return new CommonDeployResult(false, false,
                "기존 접속 키를 안전하게 확인하지 못해 업데이트를 중단했습니다. 서버 키는 변경하지 않았습니다.", LogPath);
        }

        if (File.Exists(MarkerPath)
            && await IsServerVersionCurrentAsync()
            && await VerifyRemoteAuthAsync(auth.Code))
        {
            log.AppendLine("Already current + existing owner key verified.");
            WriteLog(log);
            return new CommonDeployResult(false, true,
                "이미 최신 공통 업데이트가 적용되어 있고 기존 접속 키도 정상입니다.", LogPath);
        }

        string? node = FindExecutable("node.exe", Path.Combine("nodejs", "node.exe"));
        string? npx = FindExecutable("npx.cmd", Path.Combine("nodejs", "npx.cmd"), Path.Combine("npm", "npx.cmd"));
        if (string.IsNullOrWhiteSpace(node) || string.IsNullOrWhiteSpace(npx))
        {
            log.AppendLine($"node={node ?? "NOT_FOUND"}");
            log.AppendLine($"npx={npx ?? "NOT_FOUND"}");
            WriteLog(log);
            return new CommonDeployResult(true, false,
                "Node.js / npx를 찾지 못해 Cloudflare 자동 업데이트를 실행하지 못했습니다. 기존 파일과 접속 키는 변경하지 않았습니다.", LogPath);
        }

        string stageRoot = Path.Combine(StateDir, "update-stage", CommonUpdatePayload.Version + "-" + Guid.NewGuid().ToString("N"));
        string payloadRoot = Path.Combine(stageRoot, "payload");
        string dryRunDir = Path.Combine(stageRoot, "wrangler-dryrun");
        string? backupRoot = null;
        bool payloadInstalled = false;
        bool deployed = false;

        try
        {
            log.AppendLine($"node: {node}");
            log.AppendLine($"npx: {npx}");

            ValidateAndStagePayload(stageRoot, payloadRoot, log);
            await ValidateJavaScriptAsync(node, payloadRoot, log);
            ValidatePayloadContract(payloadRoot, log);

            backupRoot = BackupProjectFiles(projectDir, payloadRoot, log);
            InstallPayload(projectDir, payloadRoot, log);
            payloadInstalled = true;

            PatchWranglerVersionAndOwnerHash(projectDir, auth.Hash, log);
            PreserveOwnerFiles(projectDir, auth, log);
            File.WriteAllText(ProjectPathCache, projectDir, new UTF8Encoding(false));

            await ValidateJavaScriptAsync(node, projectDir, log);

            var dryRun = await RunCmdAsync(
                npx,
                $"--yes wrangler deploy --dry-run --outdir \"{dryRunDir}\"",
                projectDir,
                120_000);
            log.AppendLine("--- wrangler dry-run ---");
            log.AppendLine(dryRun.Output);
            if (dryRun.ExitCode != 0)
            {
                RestoreBackup(projectDir, backupRoot, payloadRoot, log);
                WriteLog(log);
                return new CommonDeployResult(true, false,
                    "배포 전 검증에서 오류가 발견되어 자동으로 원상복구했습니다. 기존 접속 키와 기존 서버는 그대로입니다.", LogPath);
            }

            var who = await RunCmdAsync(npx, "--yes wrangler whoami", projectDir, 45_000);
            log.AppendLine("--- wrangler whoami ---");
            log.AppendLine(who.Output);
            if (who.ExitCode != 0)
            {
                RestoreBackup(projectDir, backupRoot, payloadRoot, log);
                WriteLog(log);
                return new CommonDeployResult(true, false,
                    "Cloudflare 로그인 정보를 확인하지 못해 자동으로 원상복구했습니다. 기존 접속 키와 서버는 그대로입니다.", LogPath);
            }

            (int ExitCode, string Output) deploy = (-1, "not started");
            for (int attempt = 1; attempt <= 2; attempt++)
            {
                deploy = await RunCmdAsync(npx, "--yes wrangler deploy", projectDir, 180_000);
                log.AppendLine($"--- wrangler deploy attempt {attempt} ---");
                log.AppendLine(deploy.Output);
                if (deploy.ExitCode == 0) break;
                if (attempt < 2) await Task.Delay(3_000);
            }

            if (deploy.ExitCode != 0)
            {
                RestoreBackup(projectDir, backupRoot, payloadRoot, log);
                WriteLog(log);
                return new CommonDeployResult(true, false,
                    "Cloudflare 배포에 실패해 PC의 서버 프로젝트 파일을 자동으로 원상복구했습니다. 기존 접속 키와 현재 서버는 그대로입니다.", LogPath);
            }
            deployed = true;

            for (int i = 0; i < 15; i++)
            {
                bool versionOk = await IsServerVersionCurrentAsync();
                bool authOk = await VerifyRemoteAuthAsync(auth.Code);
                if (versionOk && authOk)
                {
                    File.WriteAllText(MarkerPath, DateTimeOffset.Now.ToString("O"), new UTF8Encoding(false));
                    log.AppendLine("Health check: PASS");
                    log.AppendLine("Existing owner key verification: PASS");
                    WriteLog(log);
                    return new CommonDeployResult(true, true,
                        $"공통 업데이트 {CommonUpdatePayload.Version} 적용과 기존 접속 키 검증을 완료했습니다.", LogPath);
                }
                await Task.Delay(2_000);
            }

            log.AppendLine("POST DEPLOY VERIFY: delayed/failed after successful deploy");
            WriteLog(log);
            return new CommonDeployResult(true, false,
                "Cloudflare 배포 명령은 성공했지만 서버 버전 또는 기존 접속 키의 최종 확인이 지연되고 있습니다. 새 키는 만들지 않았습니다. 프로그램을 다시 실행하면 자동으로 재확인합니다.", LogPath);
        }
        catch (Exception ex)
        {
            log.AppendLine("ERROR: " + ex);
            if (payloadInstalled && !deployed && !string.IsNullOrWhiteSpace(backupRoot))
            {
                try { RestoreBackup(projectDir, backupRoot, payloadRoot, log); }
                catch (Exception rollbackEx) { log.AppendLine("ROLLBACK ERROR: " + rollbackEx); }
            }
            WriteLog(log);
            return new CommonDeployResult(true, false,
                "공통 업데이트 중 오류가 발생했습니다. 배포 전 오류라면 자동 원상복구했고, 기존 접속 키는 변경하지 않았습니다.", LogPath);
        }
        finally
        {
            try { if (Directory.Exists(stageRoot)) Directory.Delete(stageRoot, true); } catch { }
        }
    }

    private static void ValidateAndStagePayload(string stageRoot, string payloadRoot, StringBuilder log)
    {
        Directory.CreateDirectory(payloadRoot);

        byte[] bytes = Convert.FromBase64String(CommonUpdatePayload.ZipBase64);
        string actualHash = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        if (!string.Equals(actualHash, CommonUpdatePayload.Sha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException($"Embedded payload SHA-256 mismatch: {actualHash}");
        log.AppendLine("Embedded payload SHA-256: PASS");

        using var ms = new MemoryStream(bytes);
        using var archive = new ZipArchive(ms, ZipArchiveMode.Read);
        foreach (ZipArchiveEntry entry in archive.Entries)
        {
            if (string.IsNullOrEmpty(entry.Name)) continue;
            string relative = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
            if (Path.IsPathRooted(relative)
                || relative.Split(Path.DirectorySeparatorChar).Any(part => part == ".."))
                throw new InvalidOperationException("Unsafe payload path: " + entry.FullName);

            if (relative.Equals("wrangler.jsonc", StringComparison.OrdinalIgnoreCase)
                || relative.Equals("PRIVATE_OWNER_CODE.txt", StringComparison.OrdinalIgnoreCase)
                || relative.Equals("OWNER_KEY_HASH.txt", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Auth/config file must not exist in payload: " + relative);

            string destination = Path.GetFullPath(Path.Combine(payloadRoot, relative));
            string rootFull = Path.GetFullPath(payloadRoot) + Path.DirectorySeparatorChar;
            if (!destination.StartsWith(rootFull, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Payload path traversal detected.");

            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            entry.ExtractToFile(destination, true);
        }

        string[] required =
        {
            Path.Combine(payloadRoot, "src", "index.js"),
            Path.Combine(payloadRoot, "public", "index.html"),
            Path.Combine(payloadRoot, "public", "app.js"),
            Path.Combine(payloadRoot, "public", "styles.css"),
            Path.Combine(payloadRoot, "public", "sw.js")
        };
        if (required.Any(path => !File.Exists(path)))
            throw new InvalidOperationException("Required common-update files are missing from payload.");

        log.AppendLine("Payload ZIP structure: PASS");
    }

    private static async Task ValidateJavaScriptAsync(string nodePath, string root, StringBuilder log)
    {
        foreach (string relative in new[] { Path.Combine("src", "index.js"), Path.Combine("public", "app.js"), Path.Combine("public", "sw.js") })
        {
            string file = Path.Combine(root, relative);
            var result = await RunExeAsync(nodePath, new[] { "--check", file }, root, 30_000);
            log.AppendLine($"node --check {relative}: {(result.ExitCode == 0 ? "PASS" : "FAIL")}");
            if (result.ExitCode != 0)
            {
                log.AppendLine(result.Output);
                throw new InvalidOperationException("JavaScript syntax check failed: " + relative);
            }
        }
    }

    private static void ValidatePayloadContract(string payloadRoot, StringBuilder log)
    {
        string html = File.ReadAllText(Path.Combine(payloadRoot, "public", "index.html"), Encoding.UTF8);
        string app = File.ReadAllText(Path.Combine(payloadRoot, "public", "app.js"), Encoding.UTF8);
        string worker = File.ReadAllText(Path.Combine(payloadRoot, "src", "index.js"), Encoding.UTF8);
        string css = File.ReadAllText(Path.Combine(payloadRoot, "public", "styles.css"), Encoding.UTF8);

        string[] htmlRequired = { "taskRepeat", "scheduleRepeat", "meetingRepeat", "scheduleDate", "meetingFollowUpDate", "data-picker-target", "일정·미팅" };
        if (htmlRequired.Any(token => !html.Contains(token, StringComparison.Ordinal)))
            throw new InvalidOperationException("Schedule/meeting UI contract is incomplete.");

        if (!app.Contains("DAILY_LIMITED", StringComparison.Ordinal)
            || !app.Contains("openNativePicker", StringComparison.Ordinal)
            || !app.Contains("setScheduleMobileView", StringComparison.Ordinal))
            throw new InvalidOperationException("Web repeat/calendar behavior contract is incomplete.");

        if (!worker.Contains("DAILY_LIMITED", StringComparison.Ordinal)
            || !worker.Contains("kinds", StringComparison.Ordinal))
            throw new InvalidOperationException("Server repeat/sync contract is incomplete.");

        if (!css.Contains("grid-template-columns:repeat(6", StringComparison.Ordinal)
            || !css.Contains("picker-control", StringComparison.Ordinal))
            throw new InvalidOperationException("Mobile layout contract is incomplete.");

        log.AppendLine("Feature contract checks: PASS");
    }

    private static OwnerAuthState CaptureOwnerAuth(string projectDir, StringBuilder log)
    {
        string codePath = Path.Combine(projectDir, "PRIVATE_OWNER_CODE.txt");
        string wranglerPath = Path.Combine(projectDir, "wrangler.jsonc");
        if (!File.Exists(codePath)) throw new FileNotFoundException("PRIVATE_OWNER_CODE.txt not found", codePath);
        if (!File.Exists(wranglerPath)) throw new FileNotFoundException("wrangler.jsonc not found", wranglerPath);

        string code = File.ReadAllText(codePath, Encoding.UTF8).Trim().Trim('\uFEFF');
        if (string.IsNullOrWhiteSpace(code) || code.Length < 12)
            throw new InvalidOperationException("Existing owner code is invalid.");

        string hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(code))).ToLowerInvariant();
        string config = File.ReadAllText(wranglerPath, Encoding.UTF8);
        string? configuredHash = ExtractOwnerHash(config);
        if (string.Equals(hash, configuredHash, StringComparison.OrdinalIgnoreCase))
            log.AppendLine("Existing owner key/hash pair: VERIFIED");
        else
            log.AppendLine("Existing owner hash mismatch detected: updater will restore hash from PRIVATE_OWNER_CODE.txt without changing the code.");

        return new OwnerAuthState(code, hash);
    }

    private static string? ExtractOwnerHash(string config)
    {
        Match match = Regex.Match(config,
            "\\\"OWNER_KEY_HASH\\\"\\s*:\\s*\\\"(?<hash>[0-9a-fA-F]{64})\\\"",
            RegexOptions.IgnoreCase);
        return match.Success ? match.Groups["hash"].Value : null;
    }

    private static string BackupProjectFiles(string projectDir, string payloadRoot, StringBuilder log)
    {
        string stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
        string backupRoot = Path.Combine(projectDir, ".mydesk-backup", $"{CommonUpdatePayload.Version}-{stamp}");
        Directory.CreateDirectory(backupRoot);

        foreach (string relative in EnumeratePayloadFiles(payloadRoot).Concat(new[] { "wrangler.jsonc", "PRIVATE_OWNER_CODE.txt", "OWNER_KEY_HASH.txt" }))
        {
            string source = Path.Combine(projectDir, relative);
            if (!File.Exists(source)) continue;
            string backup = Path.Combine(backupRoot, relative);
            Directory.CreateDirectory(Path.GetDirectoryName(backup)!);
            File.Copy(source, backup, true);
        }
        log.AppendLine("Project backup: " + backupRoot);
        return backupRoot;
    }

    private static IEnumerable<string> EnumeratePayloadFiles(string payloadRoot)
    {
        foreach (string file in Directory.EnumerateFiles(payloadRoot, "*", SearchOption.AllDirectories))
            yield return Path.GetRelativePath(payloadRoot, file);
    }

    private static void InstallPayload(string projectDir, string payloadRoot, StringBuilder log)
    {
        foreach (string relative in EnumeratePayloadFiles(payloadRoot))
        {
            string source = Path.Combine(payloadRoot, relative);
            string destination = Path.Combine(projectDir, relative);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            File.Copy(source, destination, true);
            log.AppendLine("Updated: " + relative);
        }
    }

    private static void RestoreBackup(string projectDir, string backupRoot, string payloadRoot, StringBuilder log)
    {
        log.AppendLine("ROLLBACK: start");
        foreach (string relative in EnumeratePayloadFiles(payloadRoot).Concat(new[] { "wrangler.jsonc", "PRIVATE_OWNER_CODE.txt", "OWNER_KEY_HASH.txt" }))
        {
            string backup = Path.Combine(backupRoot, relative);
            string destination = Path.Combine(projectDir, relative);
            if (File.Exists(backup))
            {
                Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
                File.Copy(backup, destination, true);
            }
            else if (File.Exists(destination) && !relative.Equals("PRIVATE_OWNER_CODE.txt", StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(destination);
            }
        }
        log.AppendLine("ROLLBACK: complete");
    }

    private static void PatchWranglerVersionAndOwnerHash(string projectDir, string ownerHash, StringBuilder log)
    {
        string path = Path.Combine(projectDir, "wrangler.jsonc");
        string text = File.ReadAllText(path, Encoding.UTF8);
        string updated = text;

        Regex versionRegex = new("\\\"APP_VERSION\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", RegexOptions.IgnoreCase);
        Regex varsRegex = new("\\\"vars\\\"\\s*:\\s*\\{", RegexOptions.IgnoreCase);
        if (versionRegex.IsMatch(updated))
            updated = versionRegex.Replace(updated, $"\"APP_VERSION\": \"{CommonUpdatePayload.Version}\"", 1);
        else if (varsRegex.IsMatch(updated))
            updated = varsRegex.Replace(updated, $"\"vars\": {{\n    \"APP_VERSION\": \"{CommonUpdatePayload.Version}\",", 1);
        else
            throw new InvalidOperationException("wrangler vars section not found.");

        Regex ownerRegex = new("\\\"OWNER_KEY_HASH\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", RegexOptions.IgnoreCase);
        if (ownerRegex.IsMatch(updated))
            updated = ownerRegex.Replace(updated, $"\"OWNER_KEY_HASH\": \"{ownerHash}\"", 1);
        else
            updated = varsRegex.Replace(updated, $"\"vars\": {{\n    \"OWNER_KEY_HASH\": \"{ownerHash}\",", 1);

        File.WriteAllText(path, updated, new UTF8Encoding(false));
        log.AppendLine("Updated APP_VERSION; OWNER_KEY_HASH preserved from existing PRIVATE_OWNER_CODE.txt.");
    }

    private static void PreserveOwnerFiles(string projectDir, OwnerAuthState auth, StringBuilder log)
    {
        File.WriteAllText(Path.Combine(projectDir, "PRIVATE_OWNER_CODE.txt"), auth.Code, new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(projectDir, "OWNER_KEY_HASH.txt"), auth.Hash, Encoding.ASCII);
        log.AppendLine("Owner code preserved; owner hash synchronized.");
    }

    private static string? FindProjectDirectory(StringBuilder log)
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
            try { AddCandidate(File.ReadAllText(ProjectPathCache, Encoding.UTF8).Trim()); } catch { }
        }

        string user = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
        string documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        string downloads = Path.Combine(user, "Downloads");
        string? oneDrive = Environment.GetEnvironmentVariable("OneDrive");

        foreach (string? root in new[] { desktop, documents, downloads, user, oneDrive, AppContext.BaseDirectory })
        {
            if (string.IsNullOrWhiteSpace(root) || !Directory.Exists(root)) continue;
            AddCandidate(root);
            AddCandidate(Path.Combine(root, "mydesk-ai-predeploy"));
            AddCandidate(Path.Combine(root, "mydesk-ai"));
            FindProjects(root, 4, candidates);
        }

        foreach (DriveInfo drive in DriveInfo.GetDrives())
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
                        && !name.Contains("predeploy", StringComparison.OrdinalIgnoreCase)) continue;
                    AddCandidate(top);
                    AddCandidate(Path.Combine(top, "mydesk-ai-predeploy"));
                    AddCandidate(Path.Combine(top, "mydesk-ai"));
                    FindProjects(top, 3, candidates);
                }
            }
            catch { }
        }

        foreach (string candidate in candidates.OrderByDescending(ProjectScore).ThenBy(path => path.Length))
            log.AppendLine($"Candidate score {ProjectScore(candidate)}: {candidate}");

        return candidates.OrderByDescending(ProjectScore).ThenBy(path => path.Length).FirstOrDefault();
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
        if (name.Equals("mydesk-ai-predeploy", StringComparison.OrdinalIgnoreCase)) score += 3000;
        if (dir.Contains("predeploy", StringComparison.OrdinalIgnoreCase)) score += 800;
        if (name.Equals("MyDesk AI", StringComparison.OrdinalIgnoreCase)) score -= 1500;

        string codePath = Path.Combine(dir, "PRIVATE_OWNER_CODE.txt");
        string hashPath = Path.Combine(dir, "OWNER_KEY_HASH.txt");
        if (File.Exists(codePath)) score += 1200;
        if (File.Exists(hashPath)) score += 300;

        try
        {
            if (File.Exists(codePath))
            {
                string code = File.ReadAllText(codePath, Encoding.UTF8).Trim().Trim('\uFEFF');
                string computed = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(code))).ToLowerInvariant();
                string config = File.ReadAllText(Path.Combine(dir, "wrangler.jsonc"), Encoding.UTF8);
                string? configured = ExtractOwnerHash(config);
                if (string.Equals(computed, configured, StringComparison.OrdinalIgnoreCase)) score += 1200;
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
            string config = File.ReadAllText(wrangler, Encoding.UTF8);
            return config.Contains("mydesk-ai", StringComparison.OrdinalIgnoreCase)
                && (config.Contains("mydesk-ai-db", StringComparison.OrdinalIgnoreCase)
                    || config.Contains("a3bd9b1e-537d-4abe-82d8-8abb668d5453", StringComparison.OrdinalIgnoreCase));
        }
        catch { return false; }
    }

    private static string? FindExecutable(string fileName, params string[] knownSuffixes)
    {
        var candidates = new List<string>();
        string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        string appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        foreach (string suffix in knownSuffixes)
        {
            if (!string.IsNullOrWhiteSpace(programFiles)) candidates.Add(Path.Combine(programFiles, suffix));
            if (!string.IsNullOrWhiteSpace(appData)) candidates.Add(Path.Combine(appData, suffix));
        }
        string path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (string part in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try { candidates.Add(Path.Combine(part.Trim().Trim('"'), fileName)); } catch { }
        }
        return candidates.FirstOrDefault(File.Exists);
    }

    private static async Task<(int ExitCode, string Output)> RunExeAsync(string fileName, IEnumerable<string> args, string workDir, int timeoutMs)
    {
        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            WorkingDirectory = workDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        foreach (string arg in args) psi.ArgumentList.Add(arg);
        return await RunProcessAsync(psi, timeoutMs);
    }

    private static async Task<(int ExitCode, string Output)> RunCmdAsync(string commandPath, string arguments, string workDir, int timeoutMs)
    {
        string comspec = Environment.GetEnvironmentVariable("ComSpec") ?? "cmd.exe";
        var psi = new ProcessStartInfo
        {
            FileName = comspec,
            WorkingDirectory = workDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        psi.ArgumentList.Add("/d");
        psi.ArgumentList.Add("/s");
        psi.ArgumentList.Add("/c");
        psi.ArgumentList.Add($"\"{commandPath}\" {arguments}");
        return await RunProcessAsync(psi, timeoutMs);
    }

    private static async Task<(int ExitCode, string Output)> RunProcessAsync(ProcessStartInfo psi, int timeoutMs)
    {
        using Process process = Process.Start(psi) ?? throw new InvalidOperationException("Unable to start update process.");
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
            return (-1, "Process timed out.");
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

    private static async Task<bool> VerifyRemoteAuthAsync(string code)
    {
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(7) };
            using var request = new HttpRequestMessage(HttpMethod.Post, WorkerBaseUrl + "/api/auth/check?cb=" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + code);
            request.Content = new StringContent("{}", Encoding.UTF8, "application/json");
            using HttpResponseMessage response = await client.SendAsync(request);
            return response.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    private static void WriteLog(StringBuilder log)
    {
        try { File.WriteAllText(LogPath, log.ToString(), Encoding.UTF8); } catch { }
    }
}
