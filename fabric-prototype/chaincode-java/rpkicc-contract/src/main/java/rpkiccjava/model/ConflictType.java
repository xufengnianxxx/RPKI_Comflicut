package rpkiccjava.model;

/**
 * 链上冲突规则类型。
 * <p><b>严重度</b>：枚举声明顺序即 {@link #ordinal()}，<b>数值越大越严重</b>，与开题「优先级」一致：</p>
 * <ol>
 *   <li>IP 前缀类：{@link #PREFIX_ADJACENT_LENGTH_MISMATCH} &lt; {@link #PREFIX_OVERLAP} &lt; {@link #PREFIX_CONTAINMENT}</li>
 *   <li>跨类：IP 前缀类 &lt; {@link #AS_MISMATCH} &lt; {@link #INHERITANCE_VIOLATION}</li>
 *   <li>{@link #MISSING_ASSET}：无法加载比对对象，单独记为最高档之一（与继承同级或更高，由合约取 max）</li>
 * </ol>
 */
public enum ConflictType {
    /** 无命中 */
    NONE,
    /** 相邻网段且前缀长度不一致（无包含、无重叠）— IP 前缀类中相对最轻 */
    PREFIX_ADJACENT_LENGTH_MISMATCH,
    /** 部分重叠（有交集且互不包含） */
    PREFIX_OVERLAP,
    /** 完全包含（一方 CIDR 覆盖另一方） */
    PREFIX_CONTAINMENT,
    /** 在共享 IP 资源上，双方 AS 集合均非空且经合约 isASConflict(Set&lt;Long&gt;) 判定为冲突 */
    AS_MISMATCH,
    /** 子证书资源超出父证书授权 */
    INHERITANCE_VIOLATION,
    /** 账本中缺少参与比较的证书资产 */
    MISSING_ASSET
}
