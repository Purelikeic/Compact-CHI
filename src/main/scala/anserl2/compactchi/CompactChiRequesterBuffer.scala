package anserl2.compactchi

import chisel3._
import chisel3.util._

/** Checks the ready/valid contract without inserting functional control. */
object CompactChiMonitor {
  def stable[T <: Data](channel: ReadyValidIO[T], name: String): Unit = {
    val stalled = RegNext(channel.valid && !channel.ready, false.B)
    val saved = RegEnable(channel.bits.asUInt, channel.valid && !channel.ready)
    when (stalled) {
      assert(channel.valid, s"$name valid withdrawn under backpressure")
      assert(channel.bits.asUInt === saved, s"$name flit changed under backpressure")
    }
  }

  def apply(port: CompactChiRequesterIO): Unit = {
    stable(port.req, "Compact CHI REQ")
    stable(port.evt, "Compact CHI EVT")
    stable(port.txRsp, "Compact CHI UpRSP")
    stable(port.txDat, "Compact CHI UpDAT")
    stable(port.rxRsp, "Compact CHI DnRSP")
    stable(port.rxDat, "Compact CHI DnDAT")
    stable(port.rxSnp, "Compact CHI SNP")
    assert(!port.req.valid || CompactChiEdge.legalReq(port.req.bits.opcode), "reserved REQ opcode")
    assert(!port.evt.valid || CompactChiEdge.legalEvt(port.evt.bits.opcode), "reserved EVT opcode")
    assert(!port.txRsp.valid || CompactChiEdge.legalUpRsp(port.txRsp.bits.opcode), "reserved UpRSP opcode")
    assert(!port.txDat.valid || CompactChiEdge.legalUpDat(port.txDat.bits.opcode), "reserved UpDAT opcode")
    assert(!port.rxRsp.valid || CompactChiEdge.legalDownRsp(port.rxRsp.bits.opcode), "reserved DnRSP opcode")
    assert(!port.rxDat.valid || CompactChiEdge.legalDownDat(port.rxDat.bits.opcode), "reserved DnDAT opcode")
    assert(!port.rxSnp.valid || CompactChiEdge.legalSnp(port.rxSnp.bits.opcode), "reserved SNP opcode")
  }
}

/** Independent registered queues for the seven-channel ready/valid profile.
  *
  * Core/L1 owns transaction IDs, response assembly, CompAck scheduling and
  * coherent line state. This transport preserves every flit and per-channel
  * order; it neither acknowledges data installation nor invents Snoop replies.
  * Reset cancels the local queues and requires a coordinated peer reset.
  */
class CompactChiRequesterBuffer(c: CompactChiParams, wayBits: Int, depth: Int = 2,
  checkProtocol: Boolean = true) extends Module {
  require(depth > 0, "Compact CHI channel queues must have positive depth")
  val io = IO(new Bundle {
    val core = Flipped(new CompactChiRequesterIO(c, wayBits))
    val link = new CompactChiRequesterIO(c, wayBits)
    val buffered = Output(Bool())
  })

  private def buffer[T <: Data](in: DecoupledIO[T], out: DecoupledIO[T]): Bool = {
    val queue = Module(new Queue(chiselTypeOf(in.bits), depth, pipe = false, flow = false))
    queue.io.enq <> in
    out <> queue.io.deq
    queue.io.count =/= 0.U
  }

  val occupied = Seq(
    buffer(io.core.req, io.link.req),
    buffer(io.core.evt, io.link.evt),
    buffer(io.core.txRsp, io.link.txRsp),
    buffer(io.core.txDat, io.link.txDat),
    buffer(io.link.rxRsp, io.core.rxRsp),
    buffer(io.link.rxDat, io.core.rxDat),
    buffer(io.link.rxSnp, io.core.rxSnp))
  io.buffered := occupied.reduce(_ || _)
  if (checkProtocol) {
    CompactChiMonitor(io.core)
    CompactChiMonitor(io.link)
  }
}
