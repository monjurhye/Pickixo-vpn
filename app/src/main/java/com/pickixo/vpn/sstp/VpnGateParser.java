package com.pickixo.vpn.sstp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the VPN Gate server list (http://www.vpngate.net/api/iphone/).
 *
 * The list arrives over plain HTTP, so every entry is validated: only public IPv4 literals and sane
 * ports are accepted, which stops a tampered list from pointing the app at local or private hosts.
 */
public final class VpnGateParser {
    private VpnGateParser() {}

    public static final class Server {
        public final String hostName;
        public final String ip;
        public final long score;
        public final int ping;
        public final long speed;
        public final String countryName;
        public final String countryCode;
        public final int sessions;
        public final List<Integer> ports;

        Server(String hostName, String ip, long score, int ping, long speed, String countryName,
               String countryCode, int sessions, List<Integer> ports) {
            this.hostName = hostName; this.ip = ip; this.score = score; this.ping = ping; this.speed = speed;
            this.countryName = countryName; this.countryCode = countryCode; this.sessions = sessions; this.ports = ports;
        }
    }

    public static List<Server> parse(String csv) {
        List<Server> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : csv.split("\r?\n")) {
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue;
            String[] f = line.split(",", -1);
            if (f.length < 15) continue;
            try {
                // Columns: HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,
                //          TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
                // Message may contain commas, so the config is always taken from the last field.
                String ip = f[1].trim();
                if (!isPublicIpv4(ip) || !seen.add(ip)) continue;
                String code = f[6].trim().toUpperCase(Locale.ROOT);
                if (!code.matches("[A-Z]{2}")) continue;
                String name = f[5].trim();
                if (name.isEmpty() || name.length() > 60) name = code;
                long score = parseLong(f[2]);
                int ping = (int) Math.min(9999, Math.max(0, parseLong(f[3])));
                long speed = Math.max(0, parseLong(f[4]));
                int sessions = (int) Math.max(0, parseLong(f[7]));
                List<Integer> ports = sstpPorts(f[f.length - 1].trim());
                out.add(new Server(f[0].trim(), ip, score, ping, speed, name, code, sessions, ports));
            } catch (RuntimeException ignored) {
                // skip malformed line
            }
        }
        return out;
    }

    /**
     * SoftEther (which VPN Gate runs) accepts SSTP on each of its TCP listener ports. The OpenVPN
     * profile tells us one listener; 443 is the usual SSTP port.
     */
    static List<Integer> sstpPorts(String base64Config) {
        Set<Integer> ports = new LinkedHashSet<>();
        try {
            String cfg = new String(Base64.getMimeDecoder().decode(base64Config), StandardCharsets.UTF_8);
            boolean tcp = false;
            int remotePort = -1;
            for (String raw : cfg.split("\r?\n")) {
                String l = raw.trim();
                if (l.startsWith("proto ")) tcp = l.substring(6).trim().startsWith("tcp");
                else if (l.startsWith("remote ")) {
                    String[] p = l.split("\\s+");
                    if (p.length >= 3) remotePort = Integer.parseInt(p[2]);
                }
            }
            if (tcp && remotePort > 0 && remotePort < 65536) ports.add(remotePort);
        } catch (RuntimeException ignored) {
        }
        ports.add(443);
        return new ArrayList<>(ports);
    }

    static boolean isPublicIpv4(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            if (p[i].isEmpty() || p[i].length() > 3 || !p[i].matches("\\d+")) return false;
            o[i] = Integer.parseInt(p[i]);
            if (o[i] > 255) return false;
        }
        if (o[0] == 0 || o[0] == 10 || o[0] == 127 || o[0] >= 224) return false;
        if (o[0] == 100 && o[1] >= 64 && o[1] <= 127) return false;
        if (o[0] == 169 && o[1] == 254) return false;
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) return false;
        if (o[0] == 192 && o[1] == 168) return false;
        if (o[0] == 192 && o[1] == 0 && o[2] == 0) return false;
        if (o[0] == 198 && (o[1] == 18 || o[1] == 19)) return false;
        return true;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
