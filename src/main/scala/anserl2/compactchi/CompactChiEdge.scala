package anserl2.compactchi

import chisel3._

/** Protocol-level legality and response mapping for a Compact CHI endpoint. */
object CompactChiEdge {
  private def oneOf(value: UInt, values: UInt*): Bool =
    values.map(value === _).reduce(_ || _)

  // Encoding legality (guide 9.2.5) is separate from endpoint support.
  // Atomic encodings are assigned, but their transaction semantics remain TBD
  // in the supplied draft; this library does not implement Atomic transactions.
  def legalReq(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Req.StashShared, CompactChiOpcode.Req.StashUnique,
    CompactChiOpcode.Req.ReadNoSnp, CompactChiOpcode.Req.ReadOnce,
    CompactChiOpcode.Req.ReadShared, CompactChiOpcode.Req.WriteNoSnpPtl,
    CompactChiOpcode.Req.WriteNoSnpFull, CompactChiOpcode.Req.WriteUniquePtl,
    CompactChiOpcode.Req.WriteUniqueFull, CompactChiOpcode.Req.CleanShared,
    CompactChiOpcode.Req.CleanInvalid, CompactChiOpcode.Req.MakeInvalid,
    CompactChiOpcode.Req.ReadUnique, CompactChiOpcode.Req.MakeUnique) ||
    (opcode >= 0x20.U && opcode <= 0x31.U)


  def legalEvt(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Evt.Evict,
    CompactChiOpcode.Evt.WriteBackFull)

  def legalUpRsp(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Rsp.CompAck,
    CompactChiOpcode.Rsp.SnpResp)

  def legalUpDat(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Dat.NonCopyBackWrData,
    CompactChiOpcode.Dat.CopyBackWrData,
    CompactChiOpcode.Dat.SnpRespData)

  def legalDownRsp(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Rsp.CompStash,
    CompactChiOpcode.Rsp.Comp,
    CompactChiOpcode.Rsp.DBIDResp,
    CompactChiOpcode.Rsp.CompDBIDResp,
    CompactChiOpcode.Rsp.CompCMO)

  def legalDownDat(opcode: UInt): Bool = opcode === CompactChiOpcode.Dat.CompData

  def legalSnp(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Snp.SnpMakeInvalid,
    CompactChiOpcode.Snp.SnpToInvalid,
    CompactChiOpcode.Snp.SnpToShared,
    CompactChiOpcode.Snp.SnpToClean)

  def responseNeedsAck(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Req.ReadShared,
    CompactChiOpcode.Req.ReadUnique,
    CompactChiOpcode.Req.MakeUnique)

  // ReadUnique may complete with Comp when the requester still has data.
  def mayReturnData(opcode: UInt): Bool = oneOf(opcode,
    CompactChiOpcode.Req.ReadNoSnp,
    CompactChiOpcode.Req.ReadOnce,
    CompactChiOpcode.Req.ReadShared,
    CompactChiOpcode.Req.ReadUnique)

  private def compAck(c: CompactChiParams, dbid: UInt, requester: UInt,
    completer: UInt, traceTag: Bool): CompactChiUpRspFlit = {
    val result = Wire(new CompactChiUpRspFlit(c))
    result := 0.U.asTypeOf(new CompactChiUpRspFlit(c))
    result.opcode := CompactChiOpcode.Rsp.CompAck
    result.txnID := dbid
    result.srcID := requester
    result.tgtID := completer
    result.traceTag := traceTag
    result
  }

  // The caller schedules this only after the cache accepts the completed state/data.
  def compAck(c: CompactChiParams, completion: CompactChiDownRspFlit): CompactChiUpRspFlit =
    compAck(c, completion.dbID, completion.tgtID, completion.srcID, completion.traceTag)

  def compAck(c: CompactChiParams, completion: CompactChiDownDatFlit): CompactChiUpRspFlit =
    compAck(c, completion.dbID, completion.tgtID, completion.srcID, completion.traceTag)

  // Snoop final state and errors are supplied by the Core's cache controller.
  def snoopResponse(c: CompactChiParams, snoop: CompactChiSnpFlit,
    resp: UInt, respErr: UInt): CompactChiUpRspFlit = {
    val result = Wire(new CompactChiUpRspFlit(c))
    result.opcode := CompactChiOpcode.Rsp.SnpResp
    result.txnID := snoop.txnID
    result.srcID := snoop.tgtID
    result.tgtID := snoop.srcID
    result.traceTag := snoop.traceTag
    result.resp := resp
    result.respErr := respErr
    result
  }
}
