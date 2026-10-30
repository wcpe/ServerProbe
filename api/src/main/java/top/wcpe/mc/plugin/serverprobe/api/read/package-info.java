/**
 * 对外只读能力的按职责分组（由 {@link top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi} 聚合继承）。
 *
 * <h2>为什么拆成子接口</h2>
 * 指标、启动画像、网络取证是三条互不相干的数据链（内存采样缓冲 / 启动时归档 / SQLite 取证库），
 * 生命周期与调用代价都不同。堆在一个接口里时，任何一条新增能力都会让所有消费方
 * 重新面对一个越来越宽的接口；拆开后新增能力只落到对应子接口。
 *
 * <h2>取用方式</h2>
 * 仍用 {@code ProbeReadApi}：{@code ServerProbeAPI.read()} 返回的即是它，
 * 全部子接口方法直接可用，**不要求**消费方按子接口分次获取。
 *
 * <h2>时间与单位约定（全 api 统一，必读）</h2>
 * <ul>
 *   <li><b>{@code ...Ms} 后缀且语义为时刻</b>（如 {@code timestampMs} / {@code createdAtMs} /
 *       {@code refreshedAtMs} / {@code sinceMs}）：<b>epoch 毫秒</b>，可直接与
 *       {@code System.currentTimeMillis()} 比较。</li>
 *   <li><b>{@code ...Ms} 后缀但语义为时长</b>（如 {@code costMs} / {@code durationMs} /
 *       {@code uptimeMs} / {@code pingMs}）：<b>毫秒时长</b>，是差值，不是时刻；
 *       不要与上面那一类比大小。</li>
 *   <li><b>{@code ...Nanos} 后缀</b>（如 {@code startNanos} / {@code endNanos} /
 *       {@code startRelNanos} / {@code premainNanos}）：<b>单调时钟的相对纳秒</b>
 *       （通常源自 {@code System.nanoTime()}），<b>不是墙钟时刻</b>。
 *       它与 epoch 毫秒<b>没有任何可比性</b>——两者基准完全不同，
 *       拿 {@code startNanos} 和 {@code timestampMs} 相减得到的是无意义的数。
 *       这类字段只用于求差（例如帧内相对偏移），且只在同一进程的同一段生命周期内有效。</li>
 *   <li><b>「无界」如何表达</b>：需要可选边界的查询接口一律用<b>装箱类型</b>
 *       （{@code Long}），{@code null} 表示该侧不设限；需要必填边界的用基本类型 {@code long}。
 *       不要用 {@code 0} 或 {@code Long.MAX_VALUE} 这类哨兵值冒充「无界」——
 *       前者与「1970 年」不可区分，后者在跨单位换算时会溢出。</li>
 *   <li><b>区间闭合性</b>：时间范围查询一律<b>闭区间</b> {@code [since, until]}，
 *       并在各方法 javadoc 里显式写明。</li>
 * </ul>
 */
package top.wcpe.mc.plugin.serverprobe.api.read;
