package com.pickixo.vpn.sstp;
import java.util.*;
public class ParserTest {
  public static void main(String[] a) {
    String cfgTcp = Base64.getEncoder().encodeToString("dev tun\r\nproto tcp\r\nremote 219.100.37.1 1443\r\ncipher AES-128-CBC\r\n".getBytes());
    String cfgUdp = Base64.getMimeEncoder().encodeToString(("proto udp\nremote 1.2.3.4 1194\n" + "x".repeat(200)).getBytes());
    String csv = "*vpn_servers\r\n#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\r\n"
      + "public-vpn-1,219.100.37.1,900000,12,90000000,Japan,JP,40,100,1000,99999,2weeks,Daiyuu Nobori,,"+cfgTcp+"\r\n"
      + "vpn2,1.2.3.4,500,30,1000,Korea Republic of,kr,3,1,1,1,2weeks,Op,hello, world, with commas,"+cfgUdp.replace("\r\n","")+"\r\n"
      + "evil,192.168.1.1,99999999,1,1,Evil,EV,0,0,0,0,x,y,,"+cfgTcp+"\r\n"
      + "evil2,127.0.0.1,9,1,1,Evil,EV,0,0,0,0,x,y,,"+cfgTcp+"\r\n"
      + "bad,999.1.1.1,9,1,1,Bad,BD,0,0,0,0,x,y,,x\r\n"
      + "short,5.6.7.8,1\r\n"
      + "dup,219.100.37.1,1,1,1,Japan,JP,0,0,0,0,x,y,,"+cfgTcp+"\r\n"
      + "garbagecfg,8.8.4.4,1,abc,1,United States,US,0,0,0,0,x,y,,%%%notbase64\r\n*\r\n";
    List<VpnGateParser.Server> l = VpnGateParser.parse(csv);
    for (VpnGateParser.Server s : l) System.out.println(s.ip+" "+s.countryCode+" "+s.countryName+" score="+s.score+" ping="+s.ping+" ports="+s.ports);
    boolean ok = l.size()==3 && l.get(0).ports.equals(List.of(1443,443)) && l.get(1).ports.equals(List.of(443))
       && l.get(1).countryCode.equals("KR") && l.get(2).ports.equals(List.of(443)) && l.get(2).ping==0;
    String html = "<table><tr><td class='vg_table_row_1'><img src='flag.png'/><br>Russian Federation</td><td><b><span style='font-size: 10pt;'>vpn687902218.opengw.net</span></b><br><span style='font-size: 10pt;'>219.100.37.1</span></td>"
      + "<td>MS-SSTP<br><a href='x'>Connect guide</a><br><span>SSTP Hostname :<br /><b><span style='color: #006600;'>vpn687902218.opengw.net:1443</span></b></span></td></tr>"
      + "<tr><td>Korea</td><td>vpn2.opengw.net<br>1.2.3.4</td><td>OpenVPN Config file UDP: 1194</td></tr>"
      + "<tr><td>US</td><td>vpn3.opengw.net<br>8.8.4.4</td><td>SSTP Hostname : vpn3.opengw.net</td></tr></table>";
    java.util.Map<String,Integer> m = VpnGateParser.sstpPortsFromHtml(html);
    System.out.println("html map: " + m);
    List<VpnGateParser.Server> merged = VpnGateParser.applySstpPorts(l, m);
    for (VpnGateParser.Server s : merged) System.out.println("merged " + s.ip + " ports=" + s.ports);
    ok = ok && Integer.valueOf(1443).equals(m.get("219.100.37.1")) && Integer.valueOf(-1).equals(m.get("1.2.3.4"))
       && Integer.valueOf(443).equals(m.get("8.8.4.4")) && merged.size() == 2
       && merged.get(0).ports.equals(List.of(1443)) && merged.get(1).ports.equals(List.of(443));
    System.out.println(ok ? "PARSER PASS" : "PARSER FAIL");
  }
}
