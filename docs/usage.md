# Compact CHI 请求方端口使用说明

本仓库提供 Compact CHI 请求方使用的参数、Opcode、Flit 类型和基础填装 helper。缓存命中、MSHR、一致性状态、DBID 生命周期、CompAck 时机以及 Snoop 数据处理由请求方控制器负责。

## 导入与默认参数

```scala
import chisel3._
import chisel3.util.log2Ceil
import anserl2.compactchi._
```

与 Anser 单核系统集成时，约定上游节点号如下：

```scala
CompactChiUpstreamNodeId.ICache  // 0b00
CompactChiUpstreamNodeId.PTW     // 0b01
CompactChiUpstreamNodeId.DCache  // 0b10
CompactChiUpstreamNodeId.UnCache // 0b11
```

这只是系统集成约定，公共 NodeID 字段的宽度仍由 `CompactChiParams` 参数决定。

默认配置：

```scala
val c = CompactChiParams()
val wayBits = 3 // 八路缓存
```

默认数据宽度为 256 bit，因此一条 64B 缓存行包含两拍数据。`dataIdBits` 必须满足：

```scala
dataIdBits == log2Ceil(512 / dataBits)
```

`wayBits = 0` 表示该链路不携带 Way 字段。

## TagAlias

REQ 上的 `TagAlias` 由请求方根据自身 VIPT 索引结构生成，协议包只传递这个值，不固定取某几个虚拟地址位。`tagAliasBits` 在生成 RTL 时确定，REQ 和 SNP 使用同一组参数时必须保持一致。

SNP 扩展也携带 `TagAlias`：

- `Addr` 仍然是物理地址右移 3 位；
- `TagAlias` 表示被探测的上游旧副本所使用的 alias；
- 请求方可以用它缩小 VIPT 候选 set，再比较物理 tag 和状态。

## Resp 状态编码

协议包提供线上 `Resp` 编码常量，具体事务选择由缓存控制器决定。

| 常量 | 编码 |
| --- | --- |
| `CompactChiResp.I` | `0b000` |
| `CompactChiResp.SC` | `0b001` |
| `CompactChiResp.UC` | `0b010` |
| `CompactChiResp.I_PD` | `0b100` |
| `CompactChiResp.SC_PD` | `0b101` |
| `CompactChiResp.UC_PD` | `0b110` |

`0b011` 和 `0b111` 是保留编码。协议包只提供编码常量，不实现缓存状态机或事务级状态检查。

`RespErr` 与 `Resp` 分开：

```scala
CompactChiRespErr.NoError
CompactChiRespErr.DERR
CompactChiRespErr.NDERR
```

## 请求方端口

请求方模块引出：

```scala
val port = IO(new CompactChiRequesterIO(c, wayBits))
```

| 端口 | 方向（请求方视角） | Flit | TxnID 宽度 |
| --- | --- | --- | --- |
| `req` | 发出 | `CompactChiReqFlit` | `txnIdBits` |
| `evt` | 发出 | `CompactChiEvtFlit` | `txnIdBits` |
| `txRsp` | 发出 | `CompactChiUpRspFlit` | `dbidBits` |
| `txDat` | 发出 | `CompactChiUpDatFlit` | `dbidBits` |
| `rxRsp` | 接收 | `CompactChiDownRspFlit` | `txnIdBits`，另有 `dbID` |
| `rxDat` | 接收 | `CompactChiDownDatFlit` | `txnIdBits`，另有 `dbID` |
| `rxSnp` | 接收 | `CompactChiSnpFlit` | `dbidBits` |

所有通道都是 ready/valid 接口。发出的 RSP/DAT 和接收的 SNP 使用 `dbidBits` 宽度；REQ/EVT 使用 `txnIdBits` 宽度。

## 示例：ReadShared 与 CompAck

ReadShared 使用 `req`、`rxDat` 和 `txRsp`。收到最后一拍 CompData 后，使用 CompData 的 `dbID` 发送 CompAck；CompAck 的 TxnID 不是原请求的 TxnID。

```scala
val beats = 512 / c.dataBits

val req = Wire(new CompactChiReqFlit(c, wayBits))
req := 0.U.asTypeOf(req)
req.opcode := CompactChiOpcode.Req.ReadShared
req.size := 6.U // 0b110，64B
req.addr := addr
req.srcID := srcID
req.tgtID := tgtID
req.txnID := txnID
port.req.valid := send
port.req.bits := req

val seen = RegInit(0.U(log2Ceil(beats + 1).W))
val pendingAck = RegInit(false.B)
val completion = Reg(new CompactChiDownDatFlit(c))

port.rxDat.ready := !pendingAck
when (port.rxDat.fire) {
  completion := port.rxDat.bits
  val last = seen + 1.U === beats.U
  seen := Mux(last, 0.U, seen + 1.U)
  pendingAck := last
}

port.txRsp.valid := pendingAck
port.txRsp.bits := CompactChiEdge.compAck(c, completion)
when (port.txRsp.fire) {
  pendingAck := false.B
}
```

ReadUnique 和 MakeUnique 也可能需要 CompAck。ReadUnique 可以通过 RXRSP 返回无数据 Comp；请求方需要保存该完成消息，并使用其中的 DBID 发送 CompAck。ReadOnce、ReadNoSnp、CMO、Stash 和写请求不发送 CompAck。

## 示例：ReadOnce 与 Snoop 响应

ReadOnce 在收齐 CompData 后结束，不发送 CompAck。收到 SNP 后，使用 SNP 的 TxnID 原样生成 SnpResp，源和目标对调。

```scala
val req = Wire(new CompactChiReqFlit(c, wayBits))
req := 0.U.asTypeOf(req)
req.opcode := CompactChiOpcode.Req.ReadOnce
req.size := 6.U
req.addr := addr
req.srcID := srcID
req.tgtID := tgtID
req.txnID := txnID

port.txRsp.valid := port.rxSnp.valid
port.txRsp.bits := CompactChiEdge.snoopResponse(
  c,
  port.rxSnp.bits,
  resp = CompactChiResp.I,
  respErr = CompactChiRespErr.NoError
)
port.rxSnp.ready := port.txRsp.ready
```

需要返回数据时，控制器另外发送 `CompactChiUpDatFlit`，Opcode 使用 `CompactChiOpcode.Dat.SnpRespData`，TxnID 仍使用 SNP 的 TxnID。

## 示例：非缓存写与 byteEnable

非缓存读使用 ReadNoSnp，非缓存写使用 WriteNoSnpPtl 或 WriteNoSnpFull。写数据必须在收到 DBIDResp 或 CompDBIDResp 后发送。

```scala
val dat = Wire(new CompactChiUpDatFlit(c))
dat := 0.U.asTypeOf(dat)
dat.opcode := CompactChiOpcode.Dat.NonCopyBackWrData
dat.txnID := completion.dbID
dat.srcID := completion.tgtID
dat.tgtID := completion.srcID
dat.dataID := dataID
dat.data := data
dat.byteEnable := byteEnable
```

`byteEnable` 的宽度是 `dataBits / 8`。总线宽度为 256 bit 时，一拍包含 32 个字节，但不代表 32 个字节都有效；MMIO 的 1B、2B、4B、8B 写需要使用对应字节掩码。

## 当前不包含的控制逻辑

本仓库不实现以下控制器功能：

- MSHR 和事务状态机；
- DBID 分配、回收和配对；
- CompAck 调度；
- 多拍数据收集；
- RetryAck、P-Credit 和资源绑定；
- Cache 状态迁移和目录控制；
- Snoop 数据生成；
- Atomic 事务执行；
- LR/SC reservation。

`CompactChiEdge.legalReq` 只判断 Opcode 编码是否合法，不代表请求方已经实现对应事务语义。

当前 Compact CHI REQ 的普通非保留 Opcode 包括：

- StashShared、StashUnique；
- ReadNoSnp、ReadOnce、ReadShared、ReadUnique；
- WriteNoSnpPtl、WriteNoSnpFull、WriteUniquePtl、WriteUniqueFull；
- CleanShared、CleanInvalid、MakeInvalid、MakeUnique。

`0x20..0x31` 是 Atomic 编码范围，目前只作为合法编码范围保留，没有具名事务 helper 或执行语义。REQ 的保留编码、上游 DAT 的 `NCBWrDataCompAck` 保留编码，以及下游其他协议 Opcode 不应作为正常事务使用。
