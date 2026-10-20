package com.pickixo.vpn.sstp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Live diagnostics against real VPN Gate servers (run from CI, which has open internet).
 * Tries SSTP on several ports per server and writes a step-by-step report.
 */
public class LiveProbe {
    static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 PickixoProbe");
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    public static void main(String[] a) throws Exception {
        int limit = a.length > 0 ? Integer.parseInt(a[0]) : 30;
        if (a.length > 1 && a[1].equals("android")) {
            // Android 10+ (Conscrypt) default suites: no DHE key exchange.
            SstpClient.forcedCipherSuites = new String[]{
                "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA"};
        }
        PrintStream out = new PrintStream(new FileOutputStream("probe-report.txt"), true, "UTF-8");
        String csv = fetch("http://www.vpngate.net/api/iphone/");
        List<VpnGateParser.Server> servers = VpnGateParser.parse(csv);
        out.println("servers in list: " + servers.size());
        Map<String, Integer> byCountry = new TreeMap<>();
        for (VpnGateParser.Server s : servers) byCountry.merge(s.countryCode, 1, Integer::sum);
        out.println("by country: " + byCountry);

        // SSTP host:port as published on the HTML list ("xxx.opengw.net:1234")
        Map<String, Integer> sstpPort = new HashMap<>();
        try {
            String html = fetch("https://www.vpngate.net/en/");
            Matcher m = Pattern.compile("([a-z0-9\\-]+)\\.opengw\\.net(?::(\\d+))?").matcher(html);
            int found = 0;
            while (m.find()) { found++; sstpPort.putIfAbsent(m.group(1).toLowerCase(), m.group(2) == null ? 443 : Integer.parseInt(m.group(2))); }
            out.println("html opengw.net hostnames: " + found + ", distinct " + sstpPort.size());
            int idx = html.indexOf("SSTP");
            out.println("html sample around 'SSTP': " + (idx >= 0 ? html.substring(idx, Math.min(html.length(), idx + 600)).replaceAll("\\s+", " ") : "none"));
        } catch (Exception e) { out.println("html fetch failed: " + e); }

        List<VpnGateParser.Server> sorted = new ArrayList<>(servers);
        sorted.sort((x, y) -> Long.compare(y.score, x.score));
        List<VpnGateParser.Server> pick = sorted.subList(0, Math.min(limit, sorted.size()));

        ConcurrentLinkedQueue<String> all = new ConcurrentLinkedQueue<>();
        SstpClient.debug = all::add;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<String>> results = new ArrayList<>();
        for (VpnGateParser.Server s : pick) {
            results.add(pool.submit(() -> {
                StringBuilder r = new StringBuilder();
                Set<Integer> ports = new LinkedHashSet<>();
                Integer hp = sstpPort.get(s.hostName.toLowerCase());
                if (hp != null) ports.add(hp);
                ports.addAll(s.ports);
                ports.addAll(List.of(443, 992, 1194, 5555));
                r.append("\n=== ").append(s.countryCode).append(" ").append(s.hostName).append(" ").append(s.ip)
                 .append(" score=").append(s.score).append(" htmlSstpPort=").append(hp).append(" ports=").append(ports).append('\n');
                for (int port : ports) {
                    SstpClient c = new SstpClient(s.ip, port, "vpn", "vpn");
                    String res;
                    try {
                        SstpClient.Result x = c.connect(sock -> true, new SstpClient.Listener() {
                            public void onIpPacket(byte[] b, int o, int l) {}
                            public void onClosed(String reason) {}
                        }, 4000, 15000);
                        res = "OK ip=" + InetAddress.getByAddress(x.address).getHostAddress() + " auth=" + x.auth + " mtu=" + x.mtu;
                    } catch (Exception e) {
                        res = "FAIL " + e.getMessage();
                    } finally { c.close(); }
                    r.append("  port ").append(port).append(": ").append(res).append('\n');
                    String prefix = s.ip + ":" + port + " ";
                    for (String l : all) if (l.startsWith(prefix)) r.append("      ").append(l.substring(prefix.length())).append('\n');
                    if (res.startsWith("OK")) break;
                }
                return r.toString();
            }));
        }
        int ok = 0;
        for (Future<String> f : results) {
            String r = f.get();
            if (r.contains(": OK")) ok++;
            out.print(r);
        }
        pool.shutdown();
        out.println("\nSUMMARY: " + ok + " / " + pick.size() + " servers connected");
        System.out.println("SUMMARY: " + ok + " / " + pick.size() + " servers connected");
    }

}
