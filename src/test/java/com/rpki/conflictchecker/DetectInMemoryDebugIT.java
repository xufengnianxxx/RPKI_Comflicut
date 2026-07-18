package com.rpki.conflictchecker;

import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.service.CertificateParseService;
import com.rpki.conflictchecker.service.RpkiUnifiedConflictDetectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.profiles.active=dev",
        "fabric.mode=MOCK"
})
class DetectInMemoryDebugIT {

    @Autowired
    RpkiUnifiedConflictDetectionService unified;
    @Autowired
    CertificateParseService parse;

    @Test
    void tc01_pair() throws Exception {
        Path d = Path.of("qidongjiaoben/test-data/certs");
        byte[] a1 = Files.readAllBytes(d.resolve("TC01_A.cer"));
        byte[] a2 = Files.readAllBytes(d.resolve("TC01_B.cer"));
        RpkiCert c1 = build(a1, "TC01_A.cer", 1L);
        RpkiCert c2 = build(a2, "TC01_B.cer", 2L);
        System.out.println("c1 ipv4 json=" + c1.getIpv4Prefixes());
        System.out.println("c2 ipv4 json=" + c2.getIpv4Prefixes());
        System.out.println("c1.rir=" + c1.getRir() + " issuer=" + c1.getIssuer());
        System.out.println("c2.rir=" + c2.getRir() + " issuer=" + c2.getIssuer());
        var r = unified.detectInMemory(java.util.List.of(c1, c2));
        System.out.println("TC01 results: " + r.size() + " " + r);
        assertFalse(r.isEmpty());
    }

    private RpkiCert build(byte[] der, String name, long id) throws Exception {
        var dto = parse.parseCertificateToDtoFromBytes(der, name, "TEST");
        RpkiCert e = parse.toEntity(dto, der, "TEST");
        e.setId(id);
        e.setParentCertId(null);
        e.setRevoked(false);
        LocalDateTime w0 = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        e.setNotBefore(w0);
        e.setNotAfter(w0.plusYears(20));
        return e;
    }
}
