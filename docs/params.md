# CompactChiParams 的当前配置

本文只讨论 `CompactChiParams()` 的默认值，也就是当前 AnserL2 `L2Cache()` 使用的那一套。字段定义在 `src/main/scala/anserl2/compactchi/CompactChiParams.scala`。`wayBits` 不属于这个类，它由缓存路数单独传入，八路时为 3。

这些值分成三种：

- 手册把编码宽度锁死，默认值取能够放下全部命名编码的最大宽度。
- AnserL2 用现有资源数量算出最小宽度，默认值刚好等于它。
- 手册只给了原则，默认值是当前系统的选择。改这一类时 L1 和 L2 必须一起改。

## 锁死的编码宽度

手册 9.1 和 9.2.5 规定了这些字段的总宽度。`require` 不允许改成别的数，否则命名 opcode 或 `Resp` 会放不下。

| 参数 | 默认 | 含义 | 为什么是这个数 |
| --- | --- | --- | --- |
| `reqOpcodeBits` | 6 | REQ opcode | 手册总宽度 6。`ReadUnique` 是 `0x10`，`MakeUnique` 是 `0x12`，5 位放不下 |
| `evtOpcodeBits` | 1 | EVT opcode | 只有 `Evict` 和 `WriteBackFull` |
| `snpOpcodeBits` | 2 | SNP opcode | 四个 Snoop 用满 2 位。Type 2 可以只用更窄的端口，默认仍给满 |
| `upRspOpcodeBits` | 1 | L1 发出的 RSP opcode | 只有 `CompAck` 和 `SnpResp` |
| `downRspOpcodeBits` | 3 | L2 发出的 RSP opcode | `CompCMO` 是 `0x4`，至少 3 位。允许更窄，默认给满 |
| `upDatOpcodeBits` | 2 | L1 发出的 DAT opcode | `CopyBackWrData` 是 2，`SnpRespData` 是 3 |
| `downDatOpcodeBits` | 1 | L2 发出的 DAT opcode | 只有 `CompData` = 0。这个宽度必须是 1，不能改 |
| `sizeBits` | 3 | `Size` | 手册的长度表就是 3 位，`0b110` 表示 64 字节 |
| `respErrBits` | 2 | `RespErr` | `NoError` = 0，`DERR` = 2，`NDERR` = 3 |
| `respBits` | 3 | `Resp` | `I_PD`、`SC_PD`、`UC_PD` 用到位 2 |
| `cBusyBits` | 3 | 下游忙碌程度 | 手册字段宽度是 3。当前 AnserL2 发出时恒为 0 |
| `dataSourceBits` | 5 | 数据来源 | 手册字段宽度是 5。手册没有给出编码表，AnserL2 发出时恒为 0 |

`downReqOpcodeBits` 默认也是 7，但本仓库没有任何 Flit 读取它。7 只与下游 AMBA CHI 的 REQ opcode 宽度相同，不决定 Compact CHI 的 REQ。Compact CHI 的 REQ 用的是上面的 `reqOpcodeBits`。

## 由资源数量算出的宽度

### `dbidBits = 8`

这是 L2 发给 L1 的 `dbID`，也是 CompAck、写数据和 Snoop 的 `txnID` 宽度。AnserL2 用它在返回时找回表项。默认配置下的位域是：

| 位 | 宽度 | 来源 |
| --- | --- | --- |
| `[4:0]` | 5 | `max(wbbBits, mshrBits)`。16 个 MSHR 是 4 位；16 + 8 个写回项向上取整是 5 位 |
| `[6:5]` | 2 | 4 个 Slice |
| `[7]` | 1 | 区分普通响应和 MMIO。`L2Cache` 要求去掉 Slice 和 local 之后至少还剩这一位 |

5 + 2 + 1 = 8。MMIO 有 16 项，本地编号 4 位，也放得进这 8 位。所以 8 是当前缓存容量下的最小合法宽度，不是随意取的。

### `dataBits = 256`，`dataIdBits = 1`

手册允许的数据宽度只有 64、128、256、512，默认 256。AnserL2 的 `beatBytes` 是 32，所以一拍也是 256 位。缓存行是 64 字节，因此一行两拍。

`dataIdBits` 必须等于 `log2Ceil(512 / dataBits)`。这里的 512 是手册用来计算一条 64 字节行有几拍的常数。256 位对应 `log2Ceil(2) = 1`，所以 `DataID` 只有 0 和 1。

由此得到 `byteEnableBits = dataBits / 8 = 32`，`dataBytes = 32`。

## 系统选择的宽度

手册没有把这些数算死。当前值是 AnserL2 这套实现的选择。

### `addrBits = 48`

REQ 和 EVT 的字节地址宽度。手册 9.1 的字段表写的就是 48。`require` 只要求大于 6，好让地址里装得下 64 字节行的偏移。AnserL2 的 `CacheParams.addrBits` 也是 48，两边必须相等。

派生的 `snoopAddrBits = addrBits - 3 = 45`。SNP 地址丢掉低 3 位，是 8 字节对齐，不是 64 字节对齐。48 位物理地址因此变成 45 位，与手册字段表一致。

### `upstreamNodeIdBits = 7`，`downstreamNodeIdBits = 7`

前者是 L1 的节点号宽度，后者是 L2 在 Compact CHI 上的节点号宽度。手册只说由节点数量和路由方式决定，没有规定 7。代码只要求大于 0。

7 位能表示 128 个节点。单核私有 L2 实际只有 L1D、L1I、PTW 和 L2 自己这几个号，3 位就够。inclusive 不会增加节点号。当前仍用 7，是因为 AnserL2 的下游 AMBA CHI 节点号是 11 位，接到 Compact CHI 时取低 7 位。两个方向默认相同，但它们是两个参数，不是同一个字段。

### `txnIdBits = 8`

这是 L1 发给自己请求的 `txnID` 宽度，也是 L2 完成消息里原样带回的那个 `txnID`。手册规定它由单个上游节点的最大在途事务数决定。AnserL2 不分配、也不拆分这个字段。

默认 8 位可以同时标出 256 笔请求。它不从 MSHR 或 Slice 推出来，和 `dbidBits` 只是数值相同。L1 若要改小，必须和 L2 使用同一个值。

### `tagAliasBits = 1`

REQ 上的 `TagAlias` 宽度，用来区分 VIPT 别名。手册允许 0，0 表示不做硬件别名。AnserL2 的 `CacheParams.aliasBits` 也是 1，`L2Cache` 要求二者相等。仓库里没有从页大小或 L1 组数推出“必须是 1”的公式；1 是当前核心的选择。
