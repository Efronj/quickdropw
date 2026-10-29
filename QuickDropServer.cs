using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("QuickDrop")]
[assembly: AssemblyDescription("QuickDrop Direct File Sharing for Windows")]
[assembly: AssemblyCompany("QuickDrop")]
[assembly: AssemblyProduct("QuickDrop")]
[assembly: AssemblyCopyright("Copyright © 2026 QuickDrop")]
[assembly: AssemblyVersion("2.1.0.0")]
[assembly: AssemblyFileVersion("2.1.0.0")]

namespace QuickDrop
{
    static class Program
    {
        private static NotifyIcon trayIcon;
        private static HttpServer httpServer;
        private static UdpEngine udpEngine;
        private const int DEFAULT_PORT = 52223;
        private static int httpPort = DEFAULT_PORT;
        private static string appUrl;

        public static long NowMs()
        {
            return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        public static List<string> GetLocalIPs()
        {
            List<string> ips = new List<string>();
            try
            {
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus == OperationalStatus.Up && ni.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                    {
                        foreach (UnicastIPAddressInformation ip in ni.GetIPProperties().UnicastAddresses)
                        {
                            if (ip.Address.AddressFamily == AddressFamily.InterNetwork)
                            {
                                ips.Add(ip.Address.ToString());
                            }
                        }
                    }
                }
            }
            catch {}
            if (ips.Count == 0) ips.Add("127.0.0.1");
            return ips;
        }

        [STAThread]
        static void Main()
        {
            bool createdNew;
            using (Mutex mutex = new Mutex(true, "QuickDrop_SingleInstance_App_Mutex_2026", out createdNew))
            {
                if (!createdNew)
                {
                    LaunchBrowser("http://localhost:" + DEFAULT_PORT);
                    return;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                try
                {
                    httpServer = new HttpServer(DEFAULT_PORT);
                    httpPort = httpServer.Start();
                    appUrl = "http://localhost:" + httpPort;

                    // UDP Discovery
                    udpEngine = new UdpEngine(httpServer.Config.Id, httpServer.Config.Name, httpPort);
                    udpEngine.Start();
                    httpServer.SetUdpEngine(udpEngine);

                    // Tray icon
                    SetupTray();

                    // Open App Window
                    LaunchBrowser(appUrl);

                    // Background message loop
                    Application.Run();

                    udpEngine.Stop();
                    httpServer.Stop();
                    if (trayIcon != null)
                    {
                        trayIcon.Visible = false;
                        trayIcon.Dispose();
                    }
                }
                catch (Exception ex)
                {
                    MessageBox.Show("QuickDrop could not start: " + ex.Message, "QuickDrop", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }

        private static void SetupTray()
        {
            ContextMenu menu = new ContextMenu();
            menu.MenuItems.Add("Open QuickDrop", delegate(object s, EventArgs e) { LaunchBrowser(appUrl); });
            menu.MenuItems.Add("Open Downloads Folder", delegate(object s, EventArgs e) { httpServer.OpenDownloadsFolder(); });
            menu.MenuItems.Add("-");
            menu.MenuItems.Add("Exit", delegate(object s, EventArgs e) {
                if (trayIcon != null) { trayIcon.Visible = false; trayIcon.Dispose(); }
                Application.Exit();
            });

            trayIcon = new NotifyIcon
            {
                Icon = SystemIcons.Application,
                ContextMenu = menu,
                Text = "QuickDrop — Direct File Sharing",
                Visible = true
            };

            trayIcon.DoubleClick += delegate(object s, EventArgs e) { LaunchBrowser(appUrl); };
        }

        public static void LaunchBrowser(string url)
        {
            try
            {
                string edge = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Microsoft", "Edge", "Application", "msedge.exe");
                if (!File.Exists(edge))
                {
                    edge = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Microsoft", "Edge", "Application", "msedge.exe");
                }
                if (!File.Exists(edge))
                {
                    edge = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "Edge", "Application", "msedge.exe");
                }

                string chrome = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Google", "Chrome", "Application", "chrome.exe");
                if (!File.Exists(chrome))
                {
                    chrome = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), "Google", "Chrome", "Application", "chrome.exe");
                }

                string browser = File.Exists(edge) ? edge : (File.Exists(chrome) ? chrome : null);

                if (!string.IsNullOrEmpty(browser))
                {
                    string userDir = Path.Combine(Path.GetTempPath(), "QuickDropProfile");
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = browser,
                        Arguments = "--app=" + url + " --user-data-dir=\"" + userDir + "\" --window-size=1020,720 --window-position=120,80",
                        UseShellExecute = true
                    });
                }
                else
                {
                    Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
                }
            }
            catch
            {
                try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch {}
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Data Models
    // ─────────────────────────────────────────────────────────────

    public class AppConfig
    {
        public string Id { get; set; }
        public string Name { get; set; }
        public bool AutoAccept { get; set; }

        public AppConfig()
        {
            Id = Guid.NewGuid().ToString("N").Substring(0, 8);
            Name = Environment.MachineName + " (PC)";
            AutoAccept = true;
        }
    }

    public class PeerInfo
    {
        public string id { get; set; }
        public string name { get; set; }
        public string deviceType { get; set; }
        public string os { get; set; }
        public string ip { get; set; }
        public int port { get; set; }
        public long lastSeen { get; set; }
    }

    public class TransferInfo
    {
        public string id { get; set; }
        public string name { get; set; }
        public long total { get; set; }
        public long done { get; set; }
        public bool outgoing { get; set; }
        public string peer { get; set; }
        public int state { get; set; }
        public double speed { get; set; }
        public string path { get; set; }
        public long startTime { get; set; }
        public long lastUpdate { get; set; }
    }

    public class HistoryItem
    {
        public string id { get; set; }
        public string name { get; set; }
        public long size { get; set; }
        public string mime { get; set; }
        public bool outgoing { get; set; }
        public string peer { get; set; }
        public long time { get; set; }
        public string path { get; set; }
        public string text { get; set; }
    }

    // ─────────────────────────────────────────────────────────────
    // Safe UDP Discovery Engine (Thread Loop)
    // ─────────────────────────────────────────────────────────────

    public class UdpEngine
    {
        private const int UDP_PORT = 52222;
        private readonly string myId;
        private readonly string myName;
        private readonly int myHttpPort;
        private UdpClient listener;
        private Thread listenThread;
        private Thread beaconThread;
        private volatile bool running;
        private readonly Dictionary<string, PeerInfo> peers = new Dictionary<string, PeerInfo>();
        private readonly object peerLock = new object();

        public UdpEngine(string id, string name, int httpPort)
        {
            this.myId = id;
            this.myName = name;
            this.myHttpPort = httpPort;
        }

        public void Start()
        {
            running = true;
            try
            {
                listener = new UdpClient();
                listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);

                try
                {
                    const int SIO_UDP_CONNRESET = -1744830452;
                    listener.Client.IOControl((IOControlCode)SIO_UDP_CONNRESET, new byte[] { 0, 0, 0, 0 }, null);
                }
                catch {}

                listener.Client.Bind(new IPEndPoint(IPAddress.Any, UDP_PORT));
                listener.EnableBroadcast = true;

                listenThread = new Thread(ListenLoop) { IsBackground = true };
                listenThread.Start();
            }
            catch (Exception ex)
            {
                Console.WriteLine("UDP listener warn: " + ex.Message);
            }

            beaconThread = new Thread(BeaconLoop) { IsBackground = true };
            beaconThread.Start();
        }

        public void Stop()
        {
            running = false;
            try { if (listener != null) listener.Close(); } catch {}
        }

        public List<PeerInfo> GetActivePeers()
        {
            long now = Program.NowMs();
            lock (peerLock)
            {
                List<PeerInfo> result = new List<PeerInfo>();
                List<string> expired = new List<string>();
                foreach (var kvp in peers)
                {
                    if (now - kvp.Value.lastSeen < 12000)
                    {
                        result.Add(kvp.Value);
                    }
                    else
                    {
                        expired.Add(kvp.Key);
                    }
                }
                foreach (string id in expired) peers.Remove(id);
                return result;
            }
        }

        private void ListenLoop()
        {
            while (running && listener != null)
            {
                try
                {
                    IPEndPoint ep = new IPEndPoint(IPAddress.Any, 0);
                    byte[] bytes = listener.Receive(ref ep);
                    if (bytes != null && bytes.Length > 0)
                    {
                        ProcessPacket(bytes, ep);
                    }
                }
                catch
                {
                    if (!running) break;
                    Thread.Sleep(100);
                }
            }
        }

        private void ProcessPacket(byte[] bytes, IPEndPoint ep)
        {
            try
            {
                string str = Encoding.UTF8.GetString(bytes);

                if (str.Contains("\"quickdrop-ping\"") || str.Contains("\"quickdrop-pong\""))
                {
                    string id = ExtractJsonVal(str, "id");
                    if (!string.IsNullOrEmpty(id) && id != myId)
                    {
                        string name = ExtractJsonVal(str, "name") ?? "Nearby Device";
                        string devType = ExtractJsonVal(str, "deviceType") ?? "mobile";
                        string os = ExtractJsonVal(str, "os") ?? "Unknown";
                        int port = 52223;
                        int.TryParse(ExtractJsonVal(str, "port"), out port);

                        PeerInfo p = new PeerInfo
                        {
                            id = id,
                            name = name,
                            deviceType = devType,
                            os = os,
                            ip = ep.Address.ToString(),
                            port = port > 0 ? port : 52223,
                            lastSeen = Program.NowMs()
                        };

                        lock (peerLock)
                        {
                            peers[id] = p;
                        }

                        if (str.Contains("\"quickdrop-ping\""))
                        {
                            string pong = string.Format(
                                "{{\"type\":\"quickdrop-pong\",\"id\":\"{0}\",\"name\":\"{1}\",\"deviceType\":\"desktop\",\"os\":\"Windows\",\"port\":{2},\"version\":\"2.1\"}}",
                                myId, EscapeJson(myName), myHttpPort
                            );
                            byte[] pongBytes = Encoding.UTF8.GetBytes(pong);
                            using (UdpClient sender = new UdpClient())
                            {
                                sender.Send(pongBytes, pongBytes.Length, ep);
                            }
                        }
                    }
                }
            }
            catch {}
        }

        private void BeaconLoop()
        {
            while (running)
            {
                try
                {
                    string ping = string.Format(
                        "{{\"type\":\"quickdrop-ping\",\"id\":\"{0}\",\"name\":\"{1}\",\"deviceType\":\"desktop\",\"os\":\"Windows\",\"port\":{2},\"version\":\"2.1\"}}",
                        myId, EscapeJson(myName), myHttpPort
                    );
                    byte[] bytes = Encoding.UTF8.GetBytes(ping);

                    using (UdpClient sender = new UdpClient())
                    {
                        sender.EnableBroadcast = true;
                        sender.Send(bytes, bytes.Length, new IPEndPoint(IPAddress.Broadcast, UDP_PORT));
                    }
                }
                catch {}

                Thread.Sleep(2500);
            }
        }

        private static string ExtractJsonVal(string json, string key)
        {
            string pattern = "\"" + key + "\":\"";
            int idx = json.IndexOf(pattern);
            if (idx >= 0)
            {
                int start = idx + pattern.Length;
                int end = json.IndexOf("\"", start);
                if (end > start) return json.Substring(start, end - start);
            }
            pattern = "\"" + key + "\":";
            idx = json.IndexOf(pattern);
            if (idx >= 0)
            {
                int start = idx + pattern.Length;
                int end = json.IndexOfAny(new char[] { ',', '}', ' ' }, start);
                if (end > start) return json.Substring(start, end - start).Trim('\"');
            }
            return null;
        }

        private static string EscapeJson(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            return s.Replace("\\", "\\\\").Replace("\"", "\\\"");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Direct Native TCP HTTP Server (Zero http.sys dependency!)
    // ─────────────────────────────────────────────────────────────

    public class HttpServer
    {
        private readonly int requestedPort;
        private TcpListener tcpListener;
        private Thread acceptThread;
        private volatile bool running;
        private UdpEngine udpEngine;
        public AppConfig Config { get; private set; }
        public string DownloadDir { get; private set; }
        private readonly Dictionary<string, TransferInfo> transfers = new Dictionary<string, TransferInfo>();
        private readonly List<HistoryItem> history = new List<HistoryItem>();
        private readonly object lockObj = new object();

        public HttpServer(int port)
        {
            this.requestedPort = port;
            this.Config = new AppConfig();
            string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            DownloadDir = Path.Combine(home, "Downloads", "QuickDrop");
            if (!Directory.Exists(DownloadDir))
            {
                Directory.CreateDirectory(DownloadDir);
            }
        }

        public void SetUdpEngine(UdpEngine engine)
        {
            this.udpEngine = engine;
        }

        public int Start()
        {
            int port = requestedPort;
            while (port < requestedPort + 10)
            {
                try
                {
                    tcpListener = new TcpListener(IPAddress.Any, port);
                    tcpListener.Start();
                    running = true;

                    acceptThread = new Thread(AcceptLoop) { IsBackground = true };
                    acceptThread.Start();
                    return port;
                }
                catch
                {
                    port++;
                }
            }
            throw new Exception("Could not bind HTTP port.");
        }

        public void Stop()
        {
            running = false;
            try { if (tcpListener != null) tcpListener.Stop(); } catch {}
        }

        public void OpenDownloadsFolder()
        {
            try { Process.Start("explorer.exe", DownloadDir); } catch {}
        }

        private void AcceptLoop()
        {
            while (running && tcpListener != null)
            {
                try
                {
                    TcpClient client = tcpListener.AcceptTcpClient();
                    ThreadPool.QueueUserWorkItem(delegate(object state) {
                        HandleClient(client);
                    });
                }
                catch
                {
                    if (!running) break;
                    Thread.Sleep(50);
                }
            }
        }

        private void HandleClient(TcpClient client)
        {
            try
            {
                using (NetworkStream stream = client.GetStream())
                {
                    // Read HTTP request header
                    byte[] headerBuf = new byte[8192];
                    int totalHeader = 0;
                    int headerEnd = -1;

                    while (totalHeader < headerBuf.Length)
                    {
                        int r = stream.Read(headerBuf, totalHeader, headerBuf.Length - totalHeader);
                        if (r <= 0) break;
                        totalHeader += r;

                        string tempStr = Encoding.ASCII.GetString(headerBuf, 0, totalHeader);
                        int idx = tempStr.IndexOf("\r\n\r\n");
                        if (idx >= 0)
                        {
                            headerEnd = idx + 4;
                            break;
                        }
                    }

                    if (headerEnd <= 0) return;

                    string headerString = Encoding.UTF8.GetString(headerBuf, 0, headerEnd);
                    string[] lines = headerString.Split(new string[] { "\r\n" }, StringSplitOptions.None);
                    if (lines.Length == 0) return;

                    string[] reqTokens = lines[0].Split(' ');
                    if (reqTokens.Length < 2) return;

                    string method = reqTokens[0].ToUpperInvariant();
                    string fullUrl = reqTokens[1];
                    string path = fullUrl.Split('?')[0].ToLowerInvariant();

                    Dictionary<string, string> headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                    for (int i = 1; i < lines.Length; i++)
                    {
                        int col = lines[i].IndexOf(':');
                        if (col > 0)
                        {
                            headers[lines[i].Substring(0, col).Trim()] = lines[i].Substring(col + 1).Trim();
                        }
                    }

                    long contentLength = 0;
                    if (headers.ContainsKey("Content-Length"))
                    {
                        long.TryParse(headers["Content-Length"], out contentLength);
                    }

                    // CORS preflight
                    if (method == "OPTIONS")
                    {
                        SendRawResponse(stream, 204, "No Content", "text/plain", new byte[0]);
                        return;
                    }

                    // API: Info
                    if (path == "/api/info")
                    {
                        List<string> ips = Program.GetLocalIPs();
                        StringBuilder ipSb = new StringBuilder("[");
                        for (int i = 0; i < ips.Count; i++)
                        {
                            if (i > 0) ipSb.Append(",");
                            ipSb.Append("\"" + Escape(ips[i]) + "\"");
                        }
                        ipSb.Append("]");

                        int curPort = ((IPEndPoint)client.Client.LocalEndPoint).Port;
                        string json = string.Format(
                            "{{\"id\":\"{0}\",\"name\":\"{1}\",\"deviceType\":\"desktop\",\"os\":\"Windows\",\"port\":{2},\"ips\":{3},\"downloadDir\":\"{4}\",\"autoAccept\":{5},\"version\":\"2.1\"}}",
                            Config.Id, Escape(Config.Name), curPort, ipSb.ToString(), Escape(DownloadDir), Config.AutoAccept ? "true" : "false"
                        );
                        SendJson(stream, json);
                        return;
                    }

                    // API: Peers
                    if (path == "/api/peers")
                    {
                        List<PeerInfo> list = udpEngine != null ? udpEngine.GetActivePeers() : new List<PeerInfo>();
                        StringBuilder sb = new StringBuilder("[");
                        for (int i = 0; i < list.Count; i++)
                        {
                            var p = list[i];
                            if (i > 0) sb.Append(",");
                            sb.Append(string.Format(
                                "{{\"id\":\"{0}\",\"name\":\"{1}\",\"deviceType\":\"{2}\",\"os\":\"{3}\",\"ip\":\"{4}\",\"port\":{5},\"lastSeen\":{6}}}",
                                p.id, Escape(p.name), p.deviceType, p.os, p.ip, p.port, p.lastSeen
                            ));
                        }
                        sb.Append("]");
                        SendJson(stream, sb.ToString());
                        return;
                    }

                    // API: Active Transfers
                    if (path == "/api/transfers")
                    {
                        lock (lockObj)
                        {
                            StringBuilder sb = new StringBuilder("[");
                            int count = 0;
                            foreach (var t in transfers.Values)
                            {
                                if (count++ > 0) sb.Append(",");
                                sb.Append(string.Format(
                                    "{{\"id\":\"{0}\",\"name\":\"{1}\",\"total\":{2},\"done\":{3},\"outgoing\":{4},\"peer\":\"{5}\",\"state\":{6},\"speed\":{7}}}",
                                    t.id, Escape(t.name), t.total, t.done, t.outgoing ? "true" : "false", Escape(t.peer), t.state, (long)t.speed
                                ));
                            }
                            sb.Append("]");
                            SendJson(stream, sb.ToString());
                        }
                        return;
                    }

                    // API: History
                    if (path == "/api/history")
                    {
                        lock (lockObj)
                        {
                            StringBuilder sb = new StringBuilder("[");
                            for (int i = 0; i < history.Count; i++)
                            {
                                var h = history[i];
                                if (i > 0) sb.Append(",");
                                sb.Append(string.Format(
                                    "{{\"id\":\"{0}\",\"name\":\"{1}\",\"size\":{2},\"mime\":\"{3}\",\"outgoing\":{4},\"peer\":\"{5}\",\"time\":{6},\"path\":\"{7}\",\"text\":{8}}}",
                                    h.id, Escape(h.name), h.size, Escape(h.mime), h.outgoing ? "true" : "false", Escape(h.peer), h.time, Escape(h.path), h.text != null ? "\"" + Escape(h.text) + "\"" : "null"
                                ));
                            }
                            sb.Append("]");
                            SendJson(stream, sb.ToString());
                        }
                        return;
                    }

                    if (path == "/api/history/clear" && method == "POST")
                    {
                        lock (lockObj) history.Clear();
                        SendJson(stream, "{\"success\":true}");
                        return;
                    }

                    // API: Settings
                    if (path == "/api/settings")
                    {
                        if (method == "POST")
                        {
                            byte[] body = ReadBodyBytes(stream, headerBuf, headerEnd, totalHeader, contentLength);
                            string bodyStr = Encoding.UTF8.GetString(body);
                            string newName = Extract(bodyStr, "name");
                            if (!string.IsNullOrEmpty(newName)) Config.Name = newName.Trim();
                        }
                        string cfgJson = string.Format("{{\"name\":\"{0}\",\"autoAccept\":{1}}}", Escape(Config.Name), Config.AutoAccept ? "true" : "false");
                        SendJson(stream, cfgJson);
                        return;
                    }

                    // API: Open Downloads Folder
                    if (path == "/api/open-downloads")
                    {
                        OpenDownloadsFolder();
                        SendJson(stream, "{\"success\":true}");
                        return;
                    }

                    // API: Open specific file
                    if (path == "/api/open-file" && method == "POST")
                    {
                        byte[] body = ReadBodyBytes(stream, headerBuf, headerEnd, totalHeader, contentLength);
                        string bodyStr = Encoding.UTF8.GetString(body);
                        string target = Extract(bodyStr, "filePath");
                        if (!string.IsNullOrEmpty(target) && File.Exists(target))
                        {
                            Process.Start(new ProcessStartInfo(target) { UseShellExecute = true });
                            SendJson(stream, "{\"success\":true}");
                        }
                        else
                        {
                            SendJson(stream, "{\"error\":\"File not found\"}", 404);
                        }
                        return;
                    }

                    // API: Windows Native File Picker Dialog
                    if (path == "/api/pick-files" && method == "POST")
                    {
                        List<string> picked = new List<string>();
                        Thread t = new Thread(delegate() {
                            using (OpenFileDialog dlg = new OpenFileDialog())
                            {
                                dlg.Multiselect = true;
                                dlg.Title = "Select files, photos or videos to send with QuickDrop";
                                dlg.Filter = "All Files (*.*)|*.*|Images & Videos (*.jpg;*.png;*.mp4;*.mkv)|*.jpg;*.png;*.mp4;*.mkv";
                                if (dlg.ShowDialog() == DialogResult.OK)
                                {
                                    picked.AddRange(dlg.FileNames);
                                }
                            }
                        });
                        t.SetApartmentState(ApartmentState.STA);
                        t.Start();
                        t.Join();

                        StringBuilder sb = new StringBuilder("{\"files\":[");
                        for (int i = 0; i < picked.Count; i++)
                        {
                            string fp = picked[i];
                            if (i > 0) sb.Append(",");
                            long sz = File.Exists(fp) ? new FileInfo(fp).Length : 0;
                            sb.Append(string.Format("{{\"path\":\"{0}\",\"name\":\"{1}\",\"size\":{2}}}", Escape(fp), Escape(Path.GetFileName(fp)), sz));
                        }
                        sb.Append("]}");
                        SendJson(stream, sb.ToString());
                        return;
                    }

                    // API: Stream local file blob
                    if (path == "/api/file-blob" && method == "GET")
                    {
                        string targetPath = null;
                        int pIdx = fullUrl.IndexOf("path=");
                        if (pIdx >= 0)
                        {
                            targetPath = Uri.UnescapeDataString(fullUrl.Substring(pIdx + 5));
                        }
                        if (!string.IsNullOrEmpty(targetPath) && File.Exists(targetPath))
                        {
                            FileInfo fi = new FileInfo(targetPath);
                            string respHdr = string.Format(
                                "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nContent-Type: application/octet-stream\r\nContent-Length: {0}\r\nConnection: close\r\n\r\n",
                                fi.Length
                            );
                            byte[] hdrBytes = Encoding.ASCII.GetBytes(respHdr);
                            stream.Write(hdrBytes, 0, hdrBytes.Length);

                            using (FileStream fs = File.OpenRead(targetPath))
                            {
                                byte[] buf = new byte[64 * 1024];
                                int rd;
                                while ((rd = fs.Read(buf, 0, buf.Length)) > 0)
                                {
                                    stream.Write(buf, 0, rd);
                                }
                            }
                        }
                        else
                        {
                            SendText(stream, "Not found", 404);
                        }
                        return;
                    }

                    // API: Upload direct (from WebRTC or web UI)
                    if (path == "/api/upload-direct" && method == "POST")
                    {
                        string rawName = headers.ContainsKey("x-file-name") ? headers["x-file-name"] : "QuickDrop_File.bin";
                        rawName = Uri.UnescapeDataString(rawName);
                        string safeName = CleanFilename(rawName);
                        string savePath = Path.Combine(DownloadDir, safeName);
                        string txId = "dir_" + Program.NowMs();

                        TransferInfo ti = new TransferInfo
                        {
                            id = txId,
                            name = safeName,
                            total = contentLength,
                            done = 0,
                            outgoing = false,
                            peer = "WebRTC / Browser",
                            state = 0,
                            speed = 0,
                            path = savePath,
                            startTime = Program.NowMs()
                        };
                        lock (lockObj) transfers[txId] = ti;

                        long written = SaveStreamToFile(stream, savePath, headerBuf, headerEnd, totalHeader, contentLength, ti);
                        ti.done = written;
                        ti.total = written;
                        ti.state = 1;

                        HistoryItem hi = new HistoryItem
                        {
                            id = "h_" + txId,
                            name = safeName,
                            size = written,
                            mime = "application/octet-stream",
                            outgoing = false,
                            peer = "WebRTC / Browser",
                            time = Program.NowMs(),
                            path = savePath
                        };
                        lock (lockObj) history.Insert(0, hi);

                        ThreadPool.QueueUserWorkItem(delegate(object s) {
                            Thread.Sleep(4000);
                            lock (lockObj) transfers.Remove(txId);
                        });

                        SendJson(stream, string.Format("{{\"success\":true,\"fileName\":\"{0}\"}}", Escape(safeName)));
                        return;
                    }

                    // API: Transfer Upload (From Android app via HTTP)
                    if (path == "/api/transfer/upload" && method == "POST")
                    {
                        string txId = headers.ContainsKey("x-transfer-id") ? headers["x-transfer-id"] : ("tx_" + Program.NowMs());
                        string rawName = headers.ContainsKey("x-file-name") ? headers["x-file-name"] : "ReceivedFile.bin";
                        rawName = Uri.UnescapeDataString(rawName);
                        string sender = headers.ContainsKey("x-sender-name") ? Uri.UnescapeDataString(headers["x-sender-name"]) : "Android Phone";

                        string safeName = CleanFilename(rawName);
                        string savePath = Path.Combine(DownloadDir, safeName);

                        TransferInfo ti = new TransferInfo
                        {
                            id = txId,
                            name = safeName,
                            total = contentLength,
                            done = 0,
                            outgoing = false,
                            peer = sender,
                            state = 0,
                            speed = 0,
                            path = savePath,
                            startTime = Program.NowMs()
                        };
                        lock (lockObj) transfers[txId] = ti;

                        long written = SaveStreamToFile(stream, savePath, headerBuf, headerEnd, totalHeader, contentLength, ti);
                        ti.done = written;
                        ti.total = written;
                        ti.state = 1;

                        HistoryItem hi = new HistoryItem
                        {
                            id = "h_" + txId,
                            name = safeName,
                            size = written,
                            mime = "application/octet-stream",
                            outgoing = false,
                            peer = sender,
                            time = Program.NowMs(),
                            path = savePath
                        };
                        lock (lockObj) history.Insert(0, hi);

                        ThreadPool.QueueUserWorkItem(delegate(object s) {
                            Thread.Sleep(4000);
                            lock (lockObj) transfers.Remove(txId);
                        });

                        SendJson(stream, string.Format("{{\"success\":true,\"savedAs\":\"{0}\"}}", Escape(safeName)));
                        return;
                    }

                    // API: Transfer Request
                    if (path == "/api/transfer/request" && method == "POST")
                    {
                        SendJson(stream, "{\"accepted\":true}");
                        return;
                    }

                    // API: Transfer Text Note
                    if (path == "/api/transfer/text" && method == "POST")
                    {
                        byte[] body = ReadBodyBytes(stream, headerBuf, headerEnd, totalHeader, contentLength);
                        string bodyStr = Encoding.UTF8.GetString(body);
                        string text = Extract(bodyStr, "text");
                        string sender = Extract(bodyStr, "senderName") ?? "Nearby Device";

                        if (!string.IsNullOrEmpty(text))
                        {
                            HistoryItem hi = new HistoryItem
                            {
                                id = "txt_" + Program.NowMs(),
                                name = "Text Note",
                                size = Encoding.UTF8.GetByteCount(text),
                                mime = "text/plain",
                                outgoing = false,
                                peer = sender,
                                time = Program.NowMs(),
                                text = text
                            };
                            lock (lockObj) history.Insert(0, hi);
                        }
                        SendJson(stream, "{\"success\":true}");
                        return;
                    }

                    // Serve Embedded Static UI Files
                    string fileResource = "index.html";
                    string contentType = "text/html; charset=utf-8";

                    if (path == "/" || path == "/index.html")
                    {
                        fileResource = "index.html";
                        contentType = "text/html; charset=utf-8";
                    }
                    else if (path == "/style.css")
                    {
                        fileResource = "style.css";
                        contentType = "text/css; charset=utf-8";
                    }
                    else if (path == "/app.js")
                    {
                        fileResource = "app.js";
                        contentType = "application/javascript; charset=utf-8";
                    }
                    else if (path == "/peerjs.min.js")
                    {
                        fileResource = "peerjs.min.js";
                        contentType = "application/javascript; charset=utf-8";
                    }
                    else if (path == "/qrcode.min.js")
                    {
                        fileResource = "qrcode.min.js";
                        contentType = "application/javascript; charset=utf-8";
                    }

                    using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream(fileResource))
                    {
                        if (s != null)
                        {
                            byte[] fileBytes = new byte[s.Length];
                            s.Read(fileBytes, 0, fileBytes.Length);
                            SendRawResponse(stream, 200, "OK", contentType, fileBytes);
                            return;
                        }
                    }

                    SendText(stream, "Not Found", 404);
                }
            }
            catch {}
            finally
            {
                try { client.Close(); } catch {}
            }
        }

        private byte[] ReadBodyBytes(NetworkStream stream, byte[] headerBuf, int headerEnd, int totalHeader, long contentLength)
        {
            byte[] body = new byte[contentLength];
            int preloaded = totalHeader - headerEnd;
            if (preloaded > 0)
            {
                int toCopy = Math.Min(preloaded, (int)contentLength);
                Array.Copy(headerBuf, headerEnd, body, 0, toCopy);
            }

            int readOffset = Math.Min(preloaded, (int)contentLength);
            while (readOffset < contentLength)
            {
                int r = stream.Read(body, readOffset, (int)contentLength - readOffset);
                if (r <= 0) break;
                readOffset += r;
            }
            return body;
        }

        private long SaveStreamToFile(NetworkStream stream, string filePath, byte[] headerBuf, int headerEnd, int totalHeader, long contentLength, TransferInfo ti)
        {
            long written = 0;
            using (FileStream fs = File.Create(filePath))
            {
                int preloaded = totalHeader - headerEnd;
                if (preloaded > 0)
                {
                    fs.Write(headerBuf, headerEnd, preloaded);
                    written += preloaded;
                    ti.done = written;
                }

                byte[] buf = new byte[64 * 1024];
                while (written < contentLength || contentLength <= 0)
                {
                    int toRead = contentLength > 0 ? (int)Math.Min((long)buf.Length, contentLength - written) : buf.Length;
                    if (toRead <= 0) break;

                    int r = stream.Read(buf, 0, toRead);
                    if (r <= 0) break;

                    fs.Write(buf, 0, r);
                    written += r;
                    ti.done = written;
                }
            }
            return written;
        }

        private void SendJson(NetworkStream stream, string json, int code = 200)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(json);
            SendRawResponse(stream, code, "OK", "application/json; charset=utf-8", bytes);
        }

        private void SendText(NetworkStream stream, string text, int code = 200)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(text);
            SendRawResponse(stream, code, "OK", "text/plain; charset=utf-8", bytes);
        }

        private void SendRawResponse(NetworkStream stream, int code, string status, string contentType, byte[] body)
        {
            string header = string.Format(
                "HTTP/1.1 {0} {1}\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, X-Transfer-Id, X-File-Index, X-File-Name, X-File-Size, X-Sender-Name\r\nContent-Type: {2}\r\nContent-Length: {3}\r\nConnection: close\r\n\r\n",
                code, status, contentType, body.Length
            );
            byte[] hdrBytes = Encoding.ASCII.GetBytes(header);
            stream.Write(hdrBytes, 0, hdrBytes.Length);
            if (body.Length > 0)
            {
                stream.Write(body, 0, body.Length);
            }
        }

        private string CleanFilename(string name)
        {
            foreach (char c in Path.GetInvalidFileNameChars())
            {
                name = name.Replace(c, '_');
            }
            string dest = Path.Combine(DownloadDir, name);
            if (!File.Exists(dest)) return name;

            string ext = Path.GetExtension(name);
            string baseName = Path.GetFileNameWithoutExtension(name);
            int counter = 1;
            while (File.Exists(Path.Combine(DownloadDir, string.Format("{0} ({1}){2}", baseName, counter, ext))))
            {
                counter++;
            }
            return string.Format("{0} ({1}){2}", baseName, counter, ext);
        }

        private static string Escape(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            return s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\r", "").Replace("\n", "\\n");
        }

        private static string Extract(string json, string key)
        {
            string pattern = "\"" + key + "\":\"";
            int idx = json.IndexOf(pattern);
            if (idx >= 0)
            {
                int start = idx + pattern.Length;
                int end = json.IndexOf("\"", start);
                if (end > start) return json.Substring(start, end - start);
            }
            return null;
        }
    }
}
