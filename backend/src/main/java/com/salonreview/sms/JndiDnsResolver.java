package com.salonreview.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;

/** {@link DnsResolver} backed by Java's built-in JNDI DNS provider — same "no extra dependency"
 * style as {@code TwilioSmsClient}/{@code VoyageClient} (no dnsjava or similar library). A fresh
 * {@link DirContext} per call rather than a shared/pooled one: this runs at most a handful of
 * times per owner page load, not hot-path traffic, so the simplicity is worth more than reuse. */
@Component
class JndiDnsResolver implements DnsResolver {

    private static final Logger log = LoggerFactory.getLogger(JndiDnsResolver.class);

    private static DirContext context() throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "3000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        return new InitialDirContext(env);
    }

    @Override
    public List<String> txt(String name) {
        List<String> out = new ArrayList<>();
        try {
            Attributes attrs = context().getAttributes(name, new String[]{"TXT"});
            Attribute attr = attrs.get("TXT");
            if (attr == null) return out;
            NamingEnumeration<?> e = attr.getAll();
            while (e.hasMore()) {
                out.add(String.valueOf(e.next()).replace("\"", ""));
            }
        } catch (NameNotFoundException ignored) {
            // no such name — same as "no records"
        } catch (NamingException ex) {
            log.debug("TXT lookup failed for {}: {}", name, ex.toString());
        }
        return out;
    }

    @Override
    public Optional<String> cname(String name) {
        try {
            Attributes attrs = context().getAttributes(name, new String[]{"CNAME"});
            Attribute attr = attrs.get("CNAME");
            return attr == null ? Optional.empty() : Optional.ofNullable((String) attr.get());
        } catch (NamingException ex) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> mxHosts(String domain) {
        List<String> out = new ArrayList<>();
        try {
            Attributes attrs = context().getAttributes(domain, new String[]{"MX"});
            Attribute attr = attrs.get("MX");
            if (attr == null) return out;
            NamingEnumeration<?> e = attr.getAll();
            while (e.hasMore()) {
                String raw = String.valueOf(e.next()); // "<priority> <host>."
                String host = raw.contains(" ") ? raw.substring(raw.indexOf(' ') + 1) : raw;
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                out.add(host);
            }
        } catch (NamingException ex) {
            log.debug("MX lookup failed for {}: {}", domain, ex.toString());
        }
        return out;
    }

    @Override
    public boolean resolves(String host) {
        try {
            Attributes attrs = context().getAttributes(host, new String[]{"A"});
            Attribute a = attrs.get("A");
            if (a != null && a.size() > 0) return true;
            Attributes attrsAaaa = context().getAttributes(host, new String[]{"AAAA"});
            Attribute aaaa = attrsAaaa.get("AAAA");
            return aaaa != null && aaaa.size() > 0;
        } catch (NamingException ex) {
            return false;
        }
    }
}
