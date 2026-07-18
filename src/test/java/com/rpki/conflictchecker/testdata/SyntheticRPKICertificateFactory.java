package com.rpki.conflictchecker.testdata;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

/**
 * 功能测试用合成 RPKI 资源证书。CA 为自签，EE 多由同一测试 CA 签发以模拟「同签发者」的 peer 条件；
 * 多跳/父子可改为由作为「资源母体的父证书私钥」签发子证书，并在数据库侧以 {@code parent_cert_id} 关联。
 */
public final class SyntheticRPKICertificateFactory {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private SyntheticRPKICertificateFactory() {
    }

    public static KeyPair newRsa2048() throws Exception {
        KeyPairGenerator k = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        k.initialize(2048, new SecureRandom());
        return k.generateKeyPair();
    }

    public static Date notAfterYears(Date notBefore, int years) {
        return new Date(notBefore.getTime() + (365L * years + 5) * 24L * 60L * 60L * 1000L);
    }

    public static X509CertificateHolder buildCaCertificate(KeyPair caKp) throws Exception {
        long now = System.currentTimeMillis();
        Date nb = new Date(now - 86_400_000L);
        Date na = notAfterYears(nb, 15);
        X500Name name = new X500Name("CN=Functional-Test RPKI CA,O=TestLab,C=AP");
        SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(caKp.getPublic().getEncoded());
        JcaX509v3CertificateBuilder b =
                new JcaX509v3CertificateBuilder(name, BigInteger.ONE, nb, na, name, caKp.getPublic());
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(2));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(spki));
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(spki));
        return b.build(
                new JcaContentSignerBuilder("SHA256WithRSA").setProvider("BC").build(caKp.getPrivate()));
    }

    public static X509CertificateHolder buildEeSignedByCa(
            X509CertificateHolder caHolder,
            KeyPair caPrivate,
            String subjectRdn, // 例如 "CN=TC01-A"（会追加 O/C）
            KeyPair subjectKp,
            BigInteger serial,
            List<Extension> rfcExtensions) throws Exception {
        long now = System.currentTimeMillis();
        Date nb = new Date(now - 86_400_000L);
        Date na = notAfterYears(nb, 15);
        X500Name subj = new X500Name(subjectRdn + ",O=TestLab,C=AP");
        SubjectPublicKeyInfo spSub = SubjectPublicKeyInfo.getInstance(subjectKp.getPublic().getEncoded());
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                caHolder.getSubject(), serial, nb, na, subj, subjectKp.getPublic());
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(spSub));
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(
                SubjectPublicKeyInfo.getInstance(caPrivate.getPublic().getEncoded())));
        for (Extension e : rfcExtensions) {
            b.addExtension(e);
        }
        return b.build(
                new JcaContentSignerBuilder("SHA256WithRSA").setProvider("BC").build(caPrivate.getPrivate()));
    }

    public static X509CertificateHolder buildEeSignedByResourceParent(
            X509CertificateHolder parentHolder,
            KeyPair parentPrivate,
            String subjectRdn,
            KeyPair subjectKp,
            BigInteger serial,
            List<Extension> rfcExtensions) throws Exception {
        long now = System.currentTimeMillis();
        Date nb = new Date(now - 86_400_000L);
        Date na = notAfterYears(nb, 15);
        X500Name subj = new X500Name(subjectRdn + ",O=TestLab,C=AP");
        SubjectPublicKeyInfo spSub = SubjectPublicKeyInfo.getInstance(subjectKp.getPublic().getEncoded());
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                parentHolder.getSubject(), serial, nb, na, subj, subjectKp.getPublic());
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(spSub));
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(
                SubjectPublicKeyInfo.getInstance(parentPrivate.getPublic().getEncoded())));
        for (Extension e : rfcExtensions) {
            b.addExtension(e);
        }
        return b.build(
                new JcaContentSignerBuilder("SHA256WithRSA").setProvider("BC").build(parentPrivate.getPrivate()));
    }

    public static X509Certificate toX509(X509CertificateHolder h) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509", "BC")
                .generateCertificate(new ByteArrayInputStream(h.getEncoded()));
    }
}
