package anserl2

import anserl2.compactchi._
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CompactChiEdgeHarness(c: CompactChiParams) extends Module {
  val io = IO(new Bundle {
    val req = Input(UInt(c.reqOpcodeBits.W))
    val evt = Input(UInt(c.evtOpcodeBits.W))
    val upRsp = Input(UInt(c.upRspOpcodeBits.W))
    val upDat = Input(UInt(c.upDatOpcodeBits.W))
    val downRsp = Input(UInt(c.downRspOpcodeBits.W))
    val downDat = Input(UInt(1.W))
    val snp = Input(UInt(c.snpOpcodeBits.W))
    val reqLegal = Output(Bool())
    val ack = Output(Bool())
    val mayReturnData = Output(Bool())
    val evtLegal = Output(Bool())
    val upRspLegal = Output(Bool())
    val upDatLegal = Output(Bool())
    val downRspLegal = Output(Bool())
    val downDatLegal = Output(Bool())
    val snpLegal = Output(Bool())
  })
  io.reqLegal := CompactChiEdge.legalReq(io.req)
  io.ack := CompactChiEdge.responseNeedsAck(io.req)
  io.mayReturnData := CompactChiEdge.mayReturnData(io.req)
  io.evtLegal := CompactChiEdge.legalEvt(io.evt)
  io.upRspLegal := CompactChiEdge.legalUpRsp(io.upRsp)
  io.upDatLegal := CompactChiEdge.legalUpDat(io.upDat)
  io.downRspLegal := CompactChiEdge.legalDownRsp(io.downRsp)
  io.downDatLegal := CompactChiEdge.legalDownDat(io.downDat)
  io.snpLegal := CompactChiEdge.legalSnp(io.snp)
}

class CompactChiResponseHarness(c: CompactChiParams) extends Module {
  val io = IO(new Bundle {
    val rsp = Input(new CompactChiDownRspFlit(c))
    val dat = Input(new CompactChiDownDatFlit(c))
    val snp = Input(new CompactChiSnpFlit(c))
    val rspAck = Output(new CompactChiUpRspFlit(c))
    val datAck = Output(new CompactChiUpRspFlit(c))
    val snoopResponse = Output(new CompactChiUpRspFlit(c))
  })
  io.rspAck := CompactChiEdge.compAck(c, io.rsp)
  io.datAck := CompactChiEdge.compAck(c, io.dat)
  io.snoopResponse := CompactChiEdge.snoopResponse(c, io.snp, 1.U, CompactChiRespErr.DERR)
}

class CompactChiEdgeSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  it should "validate the supported Compact CHI endpoint message sets" in {
    val c = CompactChiParams()
    test(new CompactChiEdgeHarness(c)) { dut =>
      dut.io.req.poke(CompactChiOpcode.Req.ReadShared)
      dut.io.evt.poke(CompactChiOpcode.Evt.WriteBackFull)
      dut.io.upRsp.poke(CompactChiOpcode.Rsp.CompAck)
      dut.io.upDat.poke(CompactChiOpcode.Dat.SnpRespData)
      dut.io.downRsp.poke(CompactChiOpcode.Rsp.CompCMO)
      dut.io.downDat.poke(CompactChiOpcode.Dat.CompData)
      dut.io.snp.poke(CompactChiOpcode.Snp.SnpToClean)
      dut.io.reqLegal.expect(true.B)
      dut.io.evtLegal.expect(true.B)
      dut.io.upRspLegal.expect(true.B)
      dut.io.upDatLegal.expect(true.B)
      dut.io.downRspLegal.expect(true.B)
      dut.io.downDatLegal.expect(true.B)
      dut.io.snpLegal.expect(true.B)

      dut.io.req.poke("h05".U)
      dut.io.upDat.poke("h1".U)
      dut.io.downRsp.poke("h5".U)
      dut.io.downDat.poke("h1".U)
      dut.io.reqLegal.expect(false.B)
      dut.io.evtLegal.expect(true.B)
      dut.io.upRspLegal.expect(true.B)
      dut.io.upDatLegal.expect(false.B)
      dut.io.downRspLegal.expect(false.B)
      dut.io.downDatLegal.expect(false.B)
      dut.io.snpLegal.expect(true.B)
    }
  }

  for ((txnBits, dbidBits) <- Seq((5, 9), (10, 7))) {
  it should s"route CompAck and SnpResp with independent TxnID=$txnBits DBID=$dbidBits widths" in {
    val c = CompactChiParams(txnIdBits = txnBits, dbidBits = dbidBits, tagAliasBits = 2)
    test(new CompactChiResponseHarness(c)) { dut =>
      dut.io.rsp.txnID.getWidth shouldBe txnBits
      dut.io.rsp.dbID.getWidth shouldBe dbidBits
      dut.io.rspAck.txnID.getWidth shouldBe dbidBits
      dut.io.rsp.srcID.poke(11.U)
      dut.io.rsp.tgtID.poke(3.U)
      dut.io.rsp.txnID.poke(7.U)
      dut.io.rsp.dbID.poke(83.U)
      dut.io.rsp.traceTag.poke(true.B)
      dut.io.dat.srcID.poke(12.U)
      dut.io.dat.tgtID.poke(4.U)
      dut.io.dat.txnID.poke(8.U)
      dut.io.dat.dbID.poke(84.U)
      dut.io.dat.traceTag.poke(false.B)
      dut.io.snp.srcID.poke(13.U)
      dut.io.snp.tgtID.poke(5.U)
      dut.io.snp.txnID.poke(85.U)
      dut.io.snp.tagAlias.getWidth shouldBe c.tagAliasBits
      dut.io.snp.tagAlias.poke(2.U)
      dut.io.snp.traceTag.poke(true.B)
      dut.io.rspAck.opcode.expect(CompactChiOpcode.Rsp.CompAck)
      dut.io.rspAck.srcID.expect(3.U)
      dut.io.rspAck.tgtID.expect(11.U)
      dut.io.rspAck.txnID.expect(83.U)
      dut.io.rspAck.traceTag.expect(true.B)
      dut.io.rspAck.respErr.expect(0.U)
      dut.io.datAck.srcID.expect(4.U)
      dut.io.datAck.tgtID.expect(12.U)
      dut.io.datAck.txnID.expect(84.U)
      dut.io.datAck.traceTag.expect(false.B)
      dut.io.snoopResponse.opcode.expect(CompactChiOpcode.Rsp.SnpResp)
      dut.io.snoopResponse.srcID.expect(5.U)
      dut.io.snoopResponse.tgtID.expect(13.U)
      dut.io.snoopResponse.txnID.expect(85.U)
      dut.io.snoopResponse.traceTag.expect(true.B)
      dut.io.snoopResponse.resp.expect(1.U)
      dut.io.snoopResponse.respErr.expect(CompactChiRespErr.DERR)
    }
  }
  }

  it should "classify assigned REQ encodings by legality, CompAck and data response" in {
    test(new CompactChiEdgeHarness(CompactChiParams())) { dut =>
      val defined = Set(0, 1, 2, 3, 4, 8, 9, 10, 11, 12, 13, 14, 16, 18) ++ (0x20 to 0x31)
      for (op <- 0 until 64) {
        dut.io.req.poke(op.U)
        dut.io.reqLegal.expect(defined.contains(op).B)
        dut.io.ack.expect(Set(4, 16, 18).contains(op).B)
        dut.io.mayReturnData.expect(Set(2, 3, 4, 16).contains(op).B)
      }
    }
  }
}
