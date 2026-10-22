# 使用 Compact CHI 请求方端口

本仓库只有请求方要接到 AnserL2 上的类型、编码常量和一条不改 Flit 内容的队列。导入：

```scala
import chisel3._
import chisel3.util.log2Ceil
import anserl2.compactchi._
```

缓存是否命中、MSHR、一致性状态、CompAck 何时发出、Snoop 要不要带数据，都由请求方自己的控制器决定。下面的例子只演示如何填端口。

与当前 AnserL2 对齐时使用默认参数。默认数据宽度 256 位，`dataIdBits` 必须是 `log2Ceil(512 / dataBits)`，八路缓存的 `wayBits` 是 3。

```scala
val c = CompactChiParams()
val wayBits = 3
```

`wayBits = 0` 时 Way 字段宽度为 0，表示这条链路上没有 Way。

## 端口方向

请求方模块引出 `CompactChiRequesterIO`。七个通道都是 ready/valid。

| 端口 | 方向 | Flit | 请求方 TxnID 宽度 |
| --- | --- | --- | --- |
| `req` | 发出 | `CompactChiReqFlit` | `txnIdBits` |
| `evt` | 发出 | `CompactChiEvtFlit` | `txnIdBits` |
| `txRsp` | 发出 | `CompactChiUpRspFlit` | `dbidBits` |
| `txDat` | 发出 | `CompactChiUpDatFlit` | `dbidBits` |
| `rxRsp` | 接收 | `CompactChiDownRspFlit` | `txnIdBits`，另有 `dbID` |
| `rxDat` | 接收 | `CompactChiDownDatFlit` | `txnIdBits`，另有 `dbID` |
| `rxSnp` | 接收 | `CompactChiSnpFlit` | `dbidBits` |

发出的 RSP/DAT 以及收到的 SNP，`txnID` 用的是 `dbidBits`，不是请求的 `txnIdBits`。这两个宽度可以不同。CompAck 和写数据都把完成消息里的 `dbID` 填进自己的 `txnID`。

直接接到 AnserL2 时，本端口的 `txRsp`/`txDat` 就是 L2 的 `upRspIn`/`upDatIn`，`rxRsp`/`rxDat`/`rxSnp` 就是 L2 的 `upRsp`/`upDat`/`upSnp`。中间可以插入 `CompactChiRequesterBuffer`，它不改字段。

## 例：L1D 发 ReadShared，并在接受数据后回 CompAck

ReadShared、ReadUnique、MakeUnique 需要 CompAck。`CompactChiEdge.responseNeedsAck` 看的是当初发出的 REQ opcode，不是完成消息的 opcode。请求方必须自己按 TxnID 记住这笔请求。库不配对、不数拍。

一条 64 字节的缓存行会拆成 `512 / dataBits` 拍。默认 256 位数据是 2 拍。CompAck 只发一次，并且要等请求方接受完最后一拍。不要在每一拍 CompData 上各发一个。`compAck` 只填 Flit：`txnID` 取保留的那拍完成消息的 `dbID`，源和目标对调，`traceTag` 原样带上，其余字段为 0。

ReadUnique 可能只返回 Comp、不返回数据。`expCompData` 置 1 时，手册不允许 Completer 走这条路径。只收到 Comp 时，改保留 `rxRsp.bits`，再调用 `CompactChiEdge.compAck(c, completionRsp)`。不要对 ReadOnce、ReadNoSnp、CMO、Stash 或写请求发 CompAck。

```scala
class ReadSharedPort(c: CompactChiParams, wayBits: Int) extends Module {
  val io = IO(new Bundle {
    val chi = new CompactChiRequesterIO(c, wayBits)
    val send = Input(Bool())
    val savedOpcode = Input(UInt(c.reqOpcodeBits.W))
    val addr = Input(UInt(c.addrBits.W))
    val srcID = Input(UInt(c.upstreamNodeIdBits.W))
    val tgtID = Input(UInt(c.downstreamNodeIdBits.W))
    val txnID = Input(UInt(c.txnIdBits.W))
  })
  val beats = 512 / c.dataBits

  val req = Wire(new CompactChiReqFlit(c, wayBits))
  req := 0.U.asTypeOf(req)
  req.opcode := CompactChiOpcode.Req.ReadShared
  req.size := 6.U // Size 0b110，64 字节
  req.addr := io.addr
  req.srcID := io.srcID
  req.tgtID := io.tgtID
  req.txnID := io.txnID
  io.chi.req.valid := io.send
  io.chi.req.bits := req

  io.chi.evt.valid := false.B
  io.chi.evt.bits := 0.U.asTypeOf(new CompactChiEvtFlit(c, wayBits))
  io.chi.txDat.valid := false.B
  io.chi.txDat.bits := 0.U.asTypeOf(new CompactChiUpDatFlit(c))
  io.chi.rxRsp.ready := true.B
  io.chi.rxSnp.ready := true.B

  val seen = RegInit(0.U(log2Ceil(beats + 1).W))
  val pending = RegInit(false.B)
  val completion = Reg(new CompactChiDownDatFlit(c))
  io.chi.rxDat.ready := !pending
  when (io.chi.rxDat.fire) {
    completion := io.chi.rxDat.bits
    val last = seen + 1.U === beats.U
    seen := Mux(last, 0.U, seen + 1.U)
    pending := last
  }
  io.chi.txRsp.valid := pending && CompactChiEdge.responseNeedsAck(io.savedOpcode)
  io.chi.txRsp.bits := CompactChiEdge.compAck(c, completion)
  when (io.chi.txRsp.fire) { pending := false.B }
}
```

`savedOpcode` 必须是这笔 `rxDat.txnID` 对应的原请求。这里的拍计数只覆盖一笔在途的数据完成；多个 TxnID 同时返回时要分开计数。上面把 `rxRsp.ready` 和 `rxSnp.ready` 恒置为真，只是为了把 CompAck 的填法写完整；真正的控制器不能这样丢掉 Snoop 和没有数据的完成消息。`txRsp` 上的 CompAck 和 SnpResp 也要自己仲裁。

## 例：L1I 发 ReadOnce，并用 SnpResp 回复 SnpMakeInvalid

一致性 L1I 在手册里是 Type 2：可以发 ReadNoSnp、ReadOnce、ReadShared、Evict、StashShared、StashUnique，并且要回答 SnpMakeInvalid。Type 2 不能拿到 Unique。本仓库不检查组件类型。

ReadOnce 在收齐 CompData 后结束，没有 CompAck。EVT 的 `memAttr` 只有 1 位，表示 Allocate；不要把它当成 REQ 上那个 4 位 MemAttr。

```scala
val req = Wire(new CompactChiReqFlit(c, wayBits))
req := 0.U.asTypeOf(req)
req.opcode := CompactChiOpcode.Req.ReadOnce
req.size := 6.U
req.addr := addr
req.srcID := srcID
req.tgtID := tgtID
req.txnID := txnID

val evt = Wire(new CompactChiEvtFlit(c, wayBits))
evt := 0.U.asTypeOf(evt)
evt.opcode := CompactChiOpcode.Evt.Evict
evt.addr := addr
evt.srcID := srcID
evt.tgtID := tgtID
evt.txnID := txnID
evt.wayValid := false.B
```

Snoop 回复的 `resp` 和 `respErr` 由缓存控制器提供。`snoopResponse` 把 SNP 的 `txnID` 原样写回，源和目标对调，opcode 固定为 SnpResp。它不产生 DAT。需要退数据时，请求方自己填 `CompactChiUpDatFlit`，opcode 用 `CompactChiOpcode.Dat.SnpRespData`，`txnID` 仍用 SNP 的 `txnID`。

```scala
io.chi.txRsp.valid := io.chi.rxSnp.valid
io.chi.txRsp.bits := CompactChiEdge.snoopResponse(
  c, io.chi.rxSnp.bits, resp = 0.U, respErr = CompactChiRespErr.NoError) // Resp 0b000 = I
io.chi.rxSnp.ready := io.chi.txRsp.ready
```

Resp 的具名取值是 I=`0`、SC=`1`、UC=`2`、I_PD=`4`、SC_PD=`5`、UC_PD=`6`。`3` 和 `7` 没有定义。库不检查 Resp。

## 例：访存写，等 DBID 之后再发数据

非一致性读用 ReadNoSnp，非一致性写用 WriteNoSnpPtl 或 WriteNoSnpFull。写数据必须等收到 DBIDResp 或 CompDBIDResp 之后再上 `txDat`。库不记录这个顺序。

```scala
val dat = Wire(new CompactChiUpDatFlit(c))
dat := 0.U.asTypeOf(dat)
dat.opcode := CompactChiOpcode.Dat.NonCopyBackWrData
dat.txnID := io.chi.rxRsp.bits.dbID
dat.srcID := io.chi.rxRsp.bits.tgtID
dat.tgtID := io.chi.rxRsp.bits.srcID
dat.dataID := 0.U
dat.data := data
dat.byteEnable := ((BigInt(1) << c.byteEnableBits) - 1).U
```

WriteBackFull 的数据 opcode 是 `CompactChiOpcode.Dat.CopyBackWrData`，同样使用完成消息里的 `dbID`。`byteEnable` 的宽度是 `dataBits / 8`，默认 256 位数据时为 32。

## 例：在请求方和链路之间插入队列

七个通道各有一个队列，默认深度 2，`pipe` 和 `flow` 都是 false。通道之间不排序，每个通道内部保持顺序。`buffered` 表示队列里还有 Flit。复位会丢掉这些 Flit，对端必须一起复位。

```scala
class BufferedRequester(c: CompactChiParams, wayBits: Int) extends Module {
  val io = IO(new Bundle {
    val core = Flipped(new CompactChiRequesterIO(c, wayBits))
    val link = new CompactChiRequesterIO(c, wayBits)
  })
  val buffer = Module(new CompactChiRequesterBuffer(c, wayBits, depth = 2))
  buffer.io.core <> io.core
  io.link <> buffer.io.link
}
```

`checkProtocol` 默认打开。某一拍 valid 为高且 ready 为低时，下一拍必须仍然 valid，并且 Flit 的每一位不变。Opcode 必须通过 `CompactChiEdge` 的 `legal*`。综合时若不需要这些检查，把 `checkProtocol` 设为 false。

## 不支持的情况

本仓库不提供，也不要在这些类型上寻找的行为：

- 没有事务状态机、一致性状态、MSHR、拍计数、DBID 配对或 CompAck 调度。
- `legalReq` 只说明 opcode 在手册 9.2.5 里有名字。它不等于 AnserL2 会接受，也不等于本仓库实现了该事务。
- `0x20` 到 `0x31` 的 Atomic 编码算合法，但手册 10.10 仍是 TBD。这里没有 Atomic 事务。
- REQ 和 EVT 没有 `AllowRetry`。这条端口是 ready/valid，没有 RetryAck、P-Credit 或 PCrdGrant。
- `DnRSP` 和 `DnDAT` 没有 `WayValid`/`Way`。Comp 和 CompData 交不回可持久化的路索引。REQ 和 EVT 上的 Way 只是字段；当前 AnserL2 忽略它们。
- RespErr 只有 `NoError = 0`、`DERR = 2`、`NDERR = 3`，没有 EXOK。
- `0x11` CleanUnique 是保留编码，`legalReq` 为假。
- 上游 DAT 的 opcode `1`（NCBWrDataCompAck）是保留编码。下游 DAT 只有 CompData。
- 监视器不检查地址对齐、Size、Order、MemAttr、DBID 是否对得上，也不生成 Snoop 数据。

和当前 AnserL2 对齐时，REQ 只发送 AnserL2 里 `CompactChiRequest.supportedByAnserL2` 覆盖的 opcode。这个函数不在本仓库：

- StashShared、StashUnique
- ReadNoSnp、ReadOnce、ReadShared、ReadUnique、MakeUnique
- WriteNoSnpPtl、WriteNoSnpFull
- CleanShared、CleanInvalid、MakeInvalid

WriteUniquePtl 和 WriteUniqueFull 编码合法，但不在这个接收范围内。AnserL2 当前也不实现上游 Exclusive、Atomic，并且不实现除 0 和 ReadReceipt 以外的 Order。Order 的数值表在手册 9.2.10，本文不另写一套编码。`excl` 字段存在，对齐时保持 0。
