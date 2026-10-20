package com.pickixo.vpn.sstp;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Live check of exactly what the app does: API list + HTML SSTP ports, then SSTP connect per country. */
public class AppProbe {
    static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }
    public static void main(String[] a) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream("probe-report.txt"), true, "UTF-8");
        List<VpnGateParser.Server> api = VpnGateParser.parse(fetch("http://www.vpngate.net/api/iphone/"));
        Map<String, Integer> html = VpnGateParser.sstpPortsFromHtml(fetch("https://www.vpngate.net/en/"));
        int withPort = 0, noSstp = 0, missing = 0;
        for (VpnGateParser.Server s : api) { Integer p = html.get(s.ip); if (p == null) missing++; else if (p > 0) withPort++; else noSstp++; }
        out.println("api=" + api.size() + " htmlRows=" + html.size() + " sstpPort=" + withPort + " noSstp=" + noSstp + " notInHtml=" + missing);
        List<VpnGateParser.Server> list = VpnGateParser.applySstpPorts(api, html);
        out.println("usable after merge: " + list.size());
        ExecutorService pool = Executors.newFixedThreadPool(12);
        Map<String, List<Future<String>>> byCountry = new TreeMap<>();
        for (VpnGateParser.Server s : list) {
            byCountry.computeIfAbsent(s.countryCode, k -> new ArrayList<>()).add(pool.submit(() -> {
                for (int port : s.ports) {
                    SstpClient c = new SstpClient(s.ip, port, "vpn", "vpn");
                    long t0 = System.currentTimeMillis();
                    try {
                        c.connect(x -> true, new SstpClient.Listener() {
                            public void onIpPacket(byte[] b, int o, int l) {}
                            public void onClosed(String r) {}
                        }, 5000, 20000);
                        return "OK   " + s.ip + ":" + port + " in " + (System.currentTimeMillis() - t0) + " ms";
                    } catch (Exception e) {
                        if (port == s.ports.get(s.ports.size() - 1))
                            return "FAIL " + s.ip + ":" + s.ports + " " + e.getMessage() + " after " + (System.currentTimeMillis() - t0) + " ms";
                    } finally { c.close(); }
                }
                return "FAIL " + s.ip;
            }));
        }
        int ok = 0, all = 0;
        for (Map.Entry<String, List<Future<String>>> e : byCountry.entrySet()) {
            int cok = 0;
            StringBuilder sb = new StringBuilder();
            for (Future<String> f : e.getValue()) { String r = f.get(); sb.append("    ").append(r).append('\n'); if (r.startsWith("OK")) cok++; }
            ok += cok; all += e.getValue().size();
            out.println(e.getKey() + ": " + cok + "/" + e.getValue().size() + " connected");
            out.print(sb);
        }
        pool.shutdown();
        out.println("SUMMARY: " + ok + " / " + all + " servers connected");
        System.out.println("SUMMARY: " + ok + " / " + all);
        System.exit(0);
    }
}
