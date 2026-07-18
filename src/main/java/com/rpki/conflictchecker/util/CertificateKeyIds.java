package com.rpki.conflictchecker.util;

import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;

import java.util.Locale;

/**
 * 从证书扩展提取 SKI / AKI（十六进制、小写、无分隔符），用于父子证书匹配。
 */
public final class CertificateKeyIds {

    private CertificateKeyIds() {
    }

    public static String subjectKeyIdHex(Extensions extensions) {
        if (extensions == null) {
            return null;
        }
        Extension ext = extensions.getExtension(Extension.subjectKeyIdentifier);
        if (ext == null) {
            return null;
        }
        try {
            byte[] ev = ext.getExtnValue().getOctets();
            SubjectKeyIdentifier ski = SubjectKeyIdentifier.getInstance(ASN1Primitive.fromByteArray(ev));
            byte[] raw = ski.getKeyIdentifier();
            return raw == null || raw.length == 0 ? null : toHex(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static String authorityKeyIdHex(Extensions extensions) {
        if (extensions == null) {
            return null;
        }
        Extension ext = extensions.getExtension(Extension.authorityKeyIdentifier);
        if (ext == null) {
            return null;
        }
        try {
            byte[] ev = ext.getExtnValue().getOctets();
            AuthorityKeyIdentifier aki = AuthorityKeyIdentifier.getInstance(ASN1Primitive.fromByteArray(ev));
            byte[] raw = aki.getKeyIdentifier();
            return raw == null || raw.length == 0 ? null : toHex(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHex(byte[] raw) {
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
