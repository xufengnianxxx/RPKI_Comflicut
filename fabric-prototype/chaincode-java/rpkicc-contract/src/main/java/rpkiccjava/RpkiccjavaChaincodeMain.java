package rpkiccjava;

import org.hyperledger.fabric.contract.ContractRouter;

/**
 * rpkiccjava 链码进程入口。
 * <p>
 * Fabric peer 以 {@code java -jar chaincode.jar} 启动时，由 {@link ContractRouter} 扫描 classpath 上
 * 带 {@link org.hyperledger.fabric.contract.annotation.Contract} 的类；本合约默认实现类为
 * {@link RpkiccjavaContract}（标注 {@code @Default}）。
 * </p>
 * <p><b>隐私</b>：仅加载脱敏 DTO，禁止将完整 PEM/DN 写入交易参数或世界状态。</p>
 */
public final class RpkiccjavaChaincodeMain {

    private RpkiccjavaChaincodeMain() {
    }

    public static void main(String[] args) {
        ContractRouter router = new ContractRouter(args);
        router.start(args);
    }
}
