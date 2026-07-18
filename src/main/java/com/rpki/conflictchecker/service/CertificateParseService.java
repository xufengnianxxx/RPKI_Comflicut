package com.rpki.conflictchecker.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rpki.conflictchecker.dto.RpkCertificateDTO;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.util.CertificateKeyIds;
import com.rpki.conflictchecker.util.CertificateUtils;
import com.rpki.conflictchecker.util.FileUtils;
import com.rpki.conflictchecker.util.Rfc3779ResourceParser;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class CertificateParseService {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public RpkCertificateDTO parseCertificateToDto(String certPath, String rir) throws Exception {
        byte[] certData = FileUtils.readFileToBytes(certPath);
        return parseCertificateToDtoFromBytes(certData, certPath, rir);
    }

    /**
     * 从 DER 证书字节解析，供功能测试/批量导入等场景复用，避免强制写临时文件。
     */
    public RpkCertificateDTO parseCertificateToDtoFromBytes(byte[] certData, String labelPathOrName, String rir) throws Exception {
        String certHash = FileUtils.calculateSHA256FromBytes(certData);
        X509Certificate x509Cert = CertificateUtils.parseCertificate(certData);
        X509CertificateHolder certHolder = CertificateUtils.parseCertificateHolder(certData);

        List<String> ipv4 = new ArrayList<>();
        List<String> ipv6 = new ArrayList<>();
        List<String> asn = new ArrayList<>();
        Extensions extensions = certHolder.getExtensions();
        fillRfc3779Resources(extensions, ipv4, ipv6, asn);
        String ski = CertificateKeyIds.subjectKeyIdHex(extensions);
        String aki = CertificateKeyIds.authorityKeyIdHex(extensions);

        String cerFileName = null;
        if (labelPathOrName != null) {
            try {
                Path p = Paths.get(labelPathOrName);
                cerFileName = p.getFileName() != null ? p.getFileName().toString() : null;
            } catch (Exception ignored) {
                cerFileName = labelPathOrName;
            }
        }

        return RpkCertificateDTO.builder()
                .filePath(labelPathOrName)
                .cerFileName(cerFileName)
                .certName(rir + "-" + x509Cert.getSerialNumber().toString(16))
                .serialNumber(CertificateUtils.getSerialNumber(x509Cert))
                .issuer(CertificateUtils.getCertificateIssuer(x509Cert))
                .subject(CertificateUtils.getCertificateSubject(x509Cert))
                .notBefore(convertToLocalDateTime(x509Cert.getNotBefore()))
                .notAfter(convertToLocalDateTime(x509Cert.getNotAfter()))
                .ipv4Prefixes(ipv4)
                .ipv6Prefixes(ipv6)
                .asNumbers(asn)
                .certHash(certHash)
                .revoked(false)
                .subjectKeyId(ski)
                .authorityKeyId(aki)
                .build();
    }

    public RpkiCert toEntity(RpkCertificateDTO dto, byte[] rawCertData, String rir) {
        RpkiCert cert = new RpkiCert();
        cert.setCertName(dto.getCertName());
        cert.setCerFileName(dto.getCerFileName());
        cert.setSubjectKeyId(dto.getSubjectKeyId());
        cert.setAuthorityKeyId(dto.getAuthorityKeyId());
        cert.setSerialNumber(dto.getSerialNumber());
        cert.setIssuer(dto.getIssuer());
        cert.setSubject(dto.getSubject());
        cert.setRir(rir == null ? null : rir.toUpperCase(Locale.ROOT));
        cert.setNotBefore(dto.getNotBefore());
        cert.setNotAfter(dto.getNotAfter());
        cert.setIpv4Prefixes(gson.toJson(dto.getIpv4Prefixes()));
        cert.setIpv6Prefixes(gson.toJson(dto.getIpv6Prefixes()));
        cert.setAsNumbers(gson.toJson(dto.getAsNumbers()));
        cert.setCertHash(dto.getCertHash());
        cert.setRawCertData(Base64.getEncoder().encodeToString(rawCertData));
        cert.setRevoked(dto.isRevoked());
        cert.setHasConflict(false);
        cert.setCreatedAt(LocalDateTime.now());
        cert.setUpdatedAt(LocalDateTime.now());
        return cert;
    }

    private void fillRfc3779Resources(Extensions extensions, List<String> ipv4, List<String> ipv6, List<String> asn) {
        if (extensions == null) {
            return;
        }
        try {
            Extension ipExt = extensions.getExtension(Rfc3779ResourceParser.OID_IP_ADDR_BLOCKS);
            if (ipExt != null) {
                ASN1Encodable parsed = ipExt.getParsedValue();
                if (parsed == null) {
                    parsed = ASN1Primitive.fromByteArray(ipExt.getExtnValue().getOctets());
                }
                Rfc3779ResourceParser.parseIpResources(parsed, ipv4, ipv6);
            }
        } catch (Exception e) {
            log.debug("解析 ipAddrBlocks 失败: {}", e.getMessage());
        }
        try {
            Extension asExt = extensions.getExtension(Rfc3779ResourceParser.OID_AS_IDS);
            if (asExt != null) {
                // 与 X.509 扩展值 octets 一致；BC 的 getParsedValue 对某些 DER 形状可能不展开 id-pe-autonomousSysNums
                ASN1Encodable parsed = ASN1Primitive.fromByteArray(asExt.getExtnValue().getOctets());
                asn.addAll(Rfc3779ResourceParser.parseAsResources(parsed));
            }
        } catch (Exception e) {
            log.debug("解析 autonomousSysNum 失败: {}", e.getMessage());
        }
    }

    private LocalDateTime convertToLocalDateTime(java.util.Date date) {
        return date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }
}
