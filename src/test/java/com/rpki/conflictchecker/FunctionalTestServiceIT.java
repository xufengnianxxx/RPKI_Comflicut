package com.rpki.conflictchecker;

import com.rpki.conflictchecker.service.FunctionalTestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需本地 MySQL（与 application-dev 一致）且已 mvn -Dtest=TestDataGenerator#generateAll 生成样例数据。
 * 不阻塞默认 {@code mvn test} 时可先跳过，待环境就绪后启用验证。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "rpki.test.functional.data-dir=qidongjiaoben/test-data",
        "spring.profiles.active=dev",
        "fabric.mode=MOCK"
})
class FunctionalTestServiceIT {

    @Autowired
    private FunctionalTestService functionalTestService;

    @Test
    void runAll_fromManifest() throws Exception {
        var r = functionalTestService.runAll();
        assertNotNull(r);
        assertEquals(12, r.getCaseCount());
        assertTrue(r.getPassedOffline() >= 0);
        for (var c : r.getCases()) {
            assertNotNull(c.getCaseId());
            System.out.println(c.getCaseId() + " offlinePass=" + c.isOfflinePass()
                    + " expected=" + c.getExpectedOfflineTypes()
                    + " actual=" + c.getActualOfflineTypes());
        }
    }
}
