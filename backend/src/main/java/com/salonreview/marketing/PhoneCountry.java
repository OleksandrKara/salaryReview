package com.salonreview.marketing;

import com.salonreview.web.GeoIpService;

import java.util.HashMap;
import java.util.Map;

/** Country of a non-US/Canada phone number from its international calling code (null for +1 and unknown codes). */
final class PhoneCountry {

    private static final Map<String, String> CODES = new HashMap<>();

    static {
        String[] pairs = {
                "7 RU", "20 EG", "27 ZA", "30 GR", "31 NL", "32 BE", "33 FR", "34 ES", "36 HU", "39 IT", "40 RO", "41 CH",
                "43 AT", "44 GB", "45 DK", "46 SE", "47 NO", "48 PL", "49 DE", "51 PE", "52 MX", "53 CU", "54 AR", "55 BR",
                "56 CL", "57 CO", "58 VE", "60 MY", "61 AU", "62 ID", "63 PH", "64 NZ", "65 SG", "66 TH", "81 JP", "82 KR",
                "84 VN", "86 CN", "90 TR", "91 IN", "92 PK", "93 AF", "94 LK", "95 MM", "98 IR", "212 MA", "213 DZ",
                "216 TN", "218 LY", "234 NG", "254 KE", "255 TZ", "256 UG", "351 PT", "352 LU", "353 IE", "354 IS",
                "355 AL", "356 MT", "357 CY", "358 FI", "359 BG", "370 LT", "371 LV", "372 EE", "373 MD", "374 AM",
                "375 BY", "376 AD", "380 UA", "381 RS", "382 ME", "385 HR", "386 SI", "387 BA", "389 MK", "420 CZ",
                "421 SK", "423 LI", "501 BZ", "502 GT", "503 SV", "504 HN", "505 NI", "506 CR", "507 PA", "591 BO",
                "593 EC", "595 PY", "598 UY", "852 HK", "853 MO", "855 KH", "856 LA", "880 BD", "886 TW", "960 MV",
                "961 LB", "962 JO", "963 SY", "964 IQ", "965 KW", "966 SA", "967 YE", "968 OM", "971 AE", "972 IL",
                "973 BH", "974 QA", "975 BT", "976 MN", "977 NP", "992 TJ", "993 TM", "994 AZ", "995 GE", "996 KG", "998 UZ"};
        for (String p : pairs) {
            String[] kv = p.split(" ");
            CODES.put(kv[0], kv[1]);
        }
    }

    private PhoneCountry() {}

    static GeoIpService.Place place(String phone) {
        String iso = iso(phone);
        if (iso == null) return null;
        return new GeoIpService.Place(iso, new java.util.Locale("", iso).getDisplayCountry(java.util.Locale.ENGLISH), null, null,
                GeoIpService.flag(iso));
    }

    static String iso(String phone) {
        if (phone == null) return null;
        String t = phone.trim();
        if (!t.startsWith("+")) return null;
        String d = t.replaceAll("\\D", "");
        if (d.isEmpty() || d.startsWith("1")) return null;
        // Kazakhstan shares +7 with Russia (numbers 6xx and 7xx).
        if (d.startsWith("76") || d.startsWith("77")) return "KZ";
        for (int len = 3; len >= 1; len--) {
            if (d.length() > len) {
                String c = CODES.get(d.substring(0, len));
                if (c != null) return c;
            }
        }
        return null;
    }
}
