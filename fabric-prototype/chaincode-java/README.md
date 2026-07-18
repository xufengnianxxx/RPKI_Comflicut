# Java 链码 `rpkicc-contract`

```bash
cd rpkicc-contract
mvn -DskipTests package
# 产物: target/rpkicc-contract-1.0.0-SNAPSHOT.jar（shade 可执行）
```

部署时请与本地 `fabric-samples/test-network` 的 Fabric 版本一致；推荐将本目录作为 `-ccp` 指向的链码目录结构的一部分，或使用官方 `chaincode-java` 模板替换 `src` 与 `pom` 后执行 `./network.sh deployCC -ccl java ...`。
