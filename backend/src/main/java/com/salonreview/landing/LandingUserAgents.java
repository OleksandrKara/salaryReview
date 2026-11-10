package com.salonreview.landing;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import ua_parser.Client;
import ua_parser.Parser;

/**
 * Bot check and device/OS/browser derivation from the User-Agent. Same rules as the former
 * salonLandings (Python {@code user_agents} on top of ua-parser): the regexes come from uap-core,
 * the mobile/tablet/pc/bot decisions are a port of {@code user_agents.parsers.UserAgent}.
 */
public final class LandingUserAgents {

    public record Context(String userAgent, String deviceType, String osName, String osVersion,
                          String browserName, String browserVersion, String ipAddress) {
    }

    private static final Pattern BOT_PATTERN = Pattern.compile(
            "^Wget|^curl/|HeadlessChrome|python-requests|Go-http-client|FlowIQLabsBot"
                    + "|Software Security Research|ChatGPT-User|PaloAltoNetworks|Palo Alto Networks|CensysInspect|zgrab"
                    + "|facebookexternalhit|^Mozilla/5\\.0( \\(compatible\\))?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNIX_LINES);

    private static final Set<String> MOBILE_DEVICE_FAMILIES = Set.of("iPhone", "iPod", "Generic Smartphone",
            "Generic Feature Phone", "PlayStation Vita", "iOS-Device");
    private static final Set<String> PC_OS_FAMILIES = Set.of("Windows 95", "Windows 98", "Solaris");
    private static final Set<String> MOBILE_OS_FAMILIES = Set.of("Windows Phone", "Windows Phone OS", "Symbian OS",
            "Bada", "Windows CE", "Windows Mobile", "Maemo");
    private static final Set<String> MOBILE_BROWSER_FAMILIES = Set.of("IE Mobile", "Opera Mobile", "Opera Mini",
            "Chrome Mobile", "Chrome Mobile WebView", "Chrome Mobile iOS");
    private static final Set<String> TABLET_DEVICE_FAMILIES = Set.of("iPad", "BlackBerry Playbook",
            "Blackberry Playbook", "Kindle", "Kindle Fire", "Kindle Fire HD", "Galaxy Tab", "Xoom", "Dell Streak");

    private static final Parser PARSER = newParser();

    private LandingUserAgents() {
    }

    private static Parser newParser() {
        try (java.io.InputStream in = LandingUserAgents.class.getResourceAsStream("/landing-ua-regexes.yaml")) {
            return new Parser(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("user agent regexes unavailable", e);
        }
    }

    private record Parsed(String ua, String deviceFamily, String osFamily, String osVersion,
                          String browserFamily, String browserVersion) {

        boolean isBot() {
            return "Spider".equals(deviceFamily);
        }

        boolean isTablet() {
            if (TABLET_DEVICE_FAMILIES.contains(deviceFamily)) return true;
            if ("Android".equals(osFamily) && !ua.contains("Mobile Safari") && !"Firefox Mobile".equals(browserFamily)) {
                return true;
            }
            if ("Windows".equals(osFamily) && osVersion.startsWith("RT")) return true;
            return "Firefox OS".equals(osFamily) && !browserFamily.contains("Mobile");
        }

        boolean isMobile() {
            if (MOBILE_DEVICE_FAMILIES.contains(deviceFamily) || MOBILE_BROWSER_FAMILIES.contains(browserFamily)) {
                return true;
            }
            if (("Android".equals(osFamily) || "Firefox OS".equals(osFamily)) && !isTablet()) return true;
            if ("BlackBerry OS".equals(osFamily) && !"Blackberry Playbook".equals(deviceFamily)) return true;
            if (MOBILE_OS_FAMILIES.contains(osFamily)) return true;
            if (ua.contains("J2ME") || ua.contains("MIDP")) return true;
            if (ua.contains("iPhone;")) return true;
            if (ua.contains("Googlebot-Mobile")) return true;
            if ("Spider".equals(deviceFamily) && browserFamily.contains("Mobile")) return true;
            return ua.contains("NokiaBrowser") && ua.contains("Mobile");
        }

        boolean isPc() {
            if (ua.contains("Windows NT") || PC_OS_FAMILIES.contains(osFamily)
                    || ("Windows".equals(osFamily) && "ME".equals(osVersion))) {
                return true;
            }
            if ("Mac OS X".equals(osFamily) && !ua.contains("Silk")) return true;
            if (ua.contains("Maemo")) return false;
            if (osFamily.contains("Chrome OS")) return true;
            return ua.contains("Linux") && ua.contains("X11");
        }
    }

    private static String versionString(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            out.add(!p.isEmpty() && p.chars().allMatch(c -> c >= '0' && c <= '9') ? new BigInteger(p).toString() : p);
        }
        return String.join(".", out);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Parsed parse(String ua) {
        Client c = PARSER.parse(ua);
        return new Parsed(ua, nz(c.device.family), nz(c.os.family),
                versionString(c.os.major, c.os.minor, c.os.patch),
                nz(c.userAgent.family), versionString(c.userAgent.major, c.userAgent.minor, c.userAgent.patch));
    }

    public static boolean isBot(String userAgent) {
        String ua = userAgent == null ? "" : userAgent;
        return BOT_PATTERN.matcher(ua).find() || parse(ua).isBot();
    }

    public static Context derive(String userAgent, String forwardedFor, String remoteAddr) {
        String ua = userAgent == null ? "" : userAgent;
        Parsed p = parse(ua);
        String deviceType;
        if (p.isBot()) deviceType = "bot";
        else if (p.isMobile()) deviceType = "mobile";
        else if (p.isTablet()) deviceType = "tablet";
        else if (p.isPc()) deviceType = "desktop";
        else deviceType = "unknown";

        String ip;
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            int comma = forwardedFor.indexOf(',');
            ip = LandingText.strip(comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor);
        } else {
            ip = remoteAddr;
        }
        return new Context(ua.isEmpty() ? null : ua, deviceType, blankToNull(p.osFamily()), blankToNull(p.osVersion()),
                blankToNull(p.browserFamily()), blankToNull(p.browserVersion()), ip);
    }

    private static String blankToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
