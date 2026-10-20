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
    System.out.println(ok ? "PARSER PASS" : "PARSER FAIL");
  }
}
