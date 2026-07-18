package com.rpki.conflictchecker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rpki.conflictchecker.entity.RpkiCert;
import com.rpki.conflictchecker.service.CertificateParseService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseSampleCertsTest {

    @Test
    void tc01_has_ipv4() throws Exception {
        var parse = new CertificateParseService();
        byte[] a = Files.readAllBytes(
                Path.of("qidongjiaoben/test-data/certs/TC01_A.cer").toAbsolutePath().normalize());
        var dto = parse.parseCertificateToDtoFromBytes(a, "TC01_A.cer", "TEST");
        assertFalse(dto.getIpv4Prefixes().isEmpty(), "TC01_A should have IPv4: " + dto.getIpv4Prefixes());
        RpkiCert e = parse.toEntity(dto, a, "TEST");
        Type t = new TypeToken<List<String>>() { }.getType();
        List<String> round = new Gson().fromJson(e.getIpv4Prefixes(), t);
        assertFalse(round.isEmpty());
        System.out.println("TC01_A ipv4=" + dto.getIpv4Prefixes() + " entityJson=" + e.getIpv4Prefixes());
    }

    @Test
    void tc01_b_has_ipv4() throws Exception {
        var parse = new CertificateParseService();
        byte[] b = Files.readAllBytes(
                Path.of("qidongjiaoben/test-data/certs/TC01_B.cer").toAbsolutePath().normalize());
        var dto = parse.parseCertificateToDtoFromBytes(b, "TC01_B.cer", "TEST");
        System.out.println("TC01_B dto ipv4=" + dto.getIpv4Prefixes());
        assertFalse(dto.getIpv4Prefixes().isEmpty());
    }

    @Test
    void tc09_as_parse() throws Exception {
        var parse = new CertificateParseService();
        for (String f : new String[] {"TC09_P.cer", "TC09_C.cer"}) {
            byte[] d = Files.readAllBytes(
                    Path.of("qidongjiaoben/test-data/certs/" + f).toAbsolutePath().normalize());
            var dto = parse.parseCertificateToDtoFromBytes(d, f, "TEST");
            System.out.println(f + " as=" + dto.getAsNumbers());
        }
    }

    @Test
    void tc05_has_ipv6() throws Exception {
        var parse = new CertificateParseService();
        byte[] a = Files.readAllBytes(
                Path.of("qidongjiaoben/test-data/certs/TC05_A.cer").toAbsolutePath().normalize());
        var dto = parse.parseCertificateToDtoFromBytes(a, "TC05_A.cer", "TEST");
        assertFalse(dto.getIpv6Prefixes().isEmpty(), "TC05_A v6: " + dto.getIpv6Prefixes());
        System.out.println("TC05_A ipv6=" + dto.getIpv6Prefixes());
    }
}
