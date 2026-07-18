package com.rpki.conflictchecker.util;

import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

public class CertificateUtils {
    
    private static final Logger logger = LoggerFactory.getLogger(CertificateUtils.class);
    
    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
    
    /**
     * 从字节数组解析 X.509 证书
     */
    public static X509Certificate parseCertificate(byte[] certData) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509", "BC");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certData));
    }
    
    /**
     * 从 DER 格式字节数组解析证书持有者对象（用于获取扩展信息）
     */
    public static X509CertificateHolder parseCertificateHolder(byte[] certData) throws IOException {
        return new X509CertificateHolder(certData);
    }
    
    /**
     * 获取证书中的 RFC 3779 扩展信息
     */
    public static Extensions getCertificateExtensions(X509CertificateHolder holder) {
        return holder.getExtensions();
    }
    
    /**
     * 获取证书主体
     */
    public static String getCertificateSubject(X509Certificate cert) {
        return cert.getSubjectX500Principal().getName();
    }
    
    /**
     * 获取证书发行者
     */
    public static String getCertificateIssuer(X509Certificate cert) {
        return cert.getIssuerX500Principal().getName();
    }
    
    /**
     * 获取证书序列号
     */
    public static String getSerialNumber(X509Certificate cert) {
        return cert.getSerialNumber().toString();
    }
    
    /**
     * 验证证书签名
     */
    public static boolean verifyCertificateSignature(X509Certificate cert, X509Certificate caCert) {
        try {
            cert.verify(caCert.getPublicKey(), "BC");
            return true;
        } catch (Exception e) {
            logger.error("证书签名验证失败: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取证书副本序列
     */
    public static String getCertificateSequence(X509Certificate cert) {
        return cert.getVersion() + "";
    }
}
