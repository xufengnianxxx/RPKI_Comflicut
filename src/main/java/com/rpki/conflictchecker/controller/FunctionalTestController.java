package com.rpki.conflictchecker.controller;

import com.rpki.conflictchecker.dto.Result;
import com.rpki.conflictchecker.dto.test.FunctionalTestRunReportDto;
import com.rpki.conflictchecker.service.FunctionalTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 功能测试：读取 {@code qidongjiaoben/test-data/} 下 manifest 与样例证，在内存中跑通链下+链上（MOCK 下链上仅供参考）。
 * 仅在 {@code rpki.test.functional.rest-enabled=true} 时注册，避免生产误暴露。
 */
@Tag(name = "functional-test", description = "功能测试与报告")
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rpki.test.functional.rest-enabled", havingValue = "true")
public class FunctionalTestController {

    private final FunctionalTestService functionalTestService;

    @Operation(summary = "运行全部功能测试用例，并写 qidongjiaoben/测试结果-功能测试.md（可配置）")
    @PostMapping("/functional")
    public ResponseEntity<Result<FunctionalTestRunReportDto>> runFunctional() throws Exception {
        FunctionalTestRunReportDto r = functionalTestService.runAll();
        return ResponseEntity.ok(Result.ok(r, "功能测试已执行，详见 data.cases 与 qidongjiaoben/测试结果-功能测试.md"));
    }

    @Operation(summary = "获取最近一次功能测试报告（未运行过则为 null）")
    @GetMapping("/functional/report")
    public ResponseEntity<Result<FunctionalTestRunReportDto>> getReport() {
        FunctionalTestRunReportDto r = functionalTestService.getLastReport();
        if (r == null) {
            return ResponseEntity.ok(Result.fail(404, "尚未执行 POST /test/functional；请先跑测试或调 generateAll 生成样例数据"));
        }
        return ResponseEntity.ok(Result.ok(r, "最近一次报告"));
    }
}
