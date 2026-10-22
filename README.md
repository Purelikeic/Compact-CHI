# Compact-CHI

本仓库从 AnserL2 的 `anserl2.compactchi` 包中抽出可复用的 Compact CHI 请求方协议环境，供 L1D、L1I 和访存工程师按同一套类型对接。协议源码与 AnserL2 一致，只有 `legalReq` 的 Atomic 范围上一行注释不同。

## 内容

`src/main/scala/anserl2/compactchi/` 下共四个文件：

- `CompactChiParams.scala`：Compact CHI 字段宽度与派生宽度。
- `CompactChiOpcodes.scala`：Opcode 和 RespErr 常量。
- `CompactChiPort.scala`：七种 Flit 定义和 `CompactChiRequesterIO` 请求方端口。
- `CompactChiEdge.scala`：编码合法性判断，以及 CompAck、SnpResp 的填装辅助函数。

L2 是否命中、MSHR 与 CompAck 调度留在 core 内，本仓库只提供协议类型。接法、例子和当前不支持的请求见 [docs/usage.md](docs/usage.md)。每个默认参数的含义和取值理由见 [docs/params.md](docs/params.md)。

## 构建与测试

```sh
make compile
make test
```

测试位于 `src/test/scala/anserl2/`。
