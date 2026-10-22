package anserl2

import anserl2.compactchi._
import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.util.Random

class CompactChiMonitorHarness extends Module {
  val io = IO(new Bundle {
    val channel = Flipped(Decoupled(UInt(16.W)))
    val ready = Input(Bool())
  })
  io.channel.ready := io.ready
  CompactChiMonitor.stable(io.channel, "test flit")
}

class CompactChiRequesterBufferSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  for ((dataBits, depth) <- Seq((64, 1), (128, 2), (256, 2), (512, 4))) {
    it should s"preserve all seven channels with dataBits=$dataBits depth=$depth" in {
      val c = CompactChiParams(dataBits = dataBits, dataIdBits = Integer.numberOfTrailingZeros(512 / dataBits))
      test(new CompactChiRequesterBuffer(c, wayBits = 2, depth = depth)) { dut =>
        val rnd = new Random(901 + dataBits)
        class Lane(val in: DecoupledIO[_ <: Bundle], val out: DecoupledIO[_ <: Bundle],
          val opcodes: Seq[Int]) {
          val expected = mutable.Queue.empty[Map[String, BigInt]]
          var offer: Option[Map[String, BigInt]] = None
          var received = 0
          var blocked = false
        }
        val lanes = Seq(
          new Lane(dut.io.core.req, dut.io.link.req, Seq(2, 3, 4, 8, 9, 12, 16, 18)),
          new Lane(dut.io.core.evt, dut.io.link.evt, Seq(0, 1)),
          new Lane(dut.io.core.txRsp, dut.io.link.txRsp, Seq(0, 1)),
          new Lane(dut.io.core.txDat, dut.io.link.txDat, Seq(0, 2, 3)),
          new Lane(dut.io.link.rxRsp, dut.io.core.rxRsp, Seq(0, 1, 2, 3, 4)),
          new Lane(dut.io.link.rxDat, dut.io.core.rxDat, Seq(0)),
          new Lane(dut.io.link.rxSnp, dut.io.core.rxSnp, Seq(0, 1, 2, 3)))

        def poke(field: Data, value: BigInt): Unit = field match {
          case b: Bool => b.poke((value != 0).B)
          case u: UInt => u.poke(value.U)
        }
        def expect(field: Data, value: BigInt): Unit = field match {
          case b: Bool => b.expect((value != 0).B)
          case u: UInt => u.expect(value.U)
        }
        def cycle(produce: Boolean, drain: Boolean = false): Unit = {
          for (lane <- lanes) {
            if (produce && lane.offer.isEmpty && rnd.nextInt(4) != 0) {
              val fields = lane.in.bits.elements.map { case (name, field) =>
                name -> BigInt(field.getWidth, rnd)
              }.toMap
              lane.offer = Some(fields.updated("opcode", BigInt(lane.opcodes(rnd.nextInt(lane.opcodes.size)))))
            }
            lane.in.valid.poke(lane.offer.nonEmpty.B)
            lane.offer.foreach(fields => fields.foreach { case (name, value) => poke(lane.in.bits.elements(name), value) })
            lane.out.ready.poke((drain || rnd.nextInt(5) == 0).B)
          }
          for (lane <- lanes) {
            if (lane.out.valid.peek().litToBoolean) {
              lane.expected.nonEmpty shouldBe true
              lane.expected.front.foreach { case (name, value) => expect(lane.out.bits.elements(name), value) }
              if (lane.out.ready.peek().litToBoolean) {
                lane.expected.dequeue()
                lane.received += 1
              }
            }
            if (lane.in.valid.peek().litToBoolean) {
              if (lane.in.ready.peek().litToBoolean) {
                lane.expected.enqueue(lane.offer.get)
                lane.offer = None
              } else lane.blocked = true
            }
          }
          dut.clock.step()
        }
        lanes.foreach { lane => lane.in.valid.poke(false.B); lane.out.ready.poke(false.B) }
        dut.io.buffered.expect(false.B)
        for (_ <- 0 until 140) cycle(produce = true)
        for (_ <- 0 until 30) cycle(produce = false, drain = true)
        for (lane <- lanes) {
          lane.offer shouldBe empty
          lane.expected shouldBe empty
          lane.received should be > 10
          lane.blocked shouldBe true
        }
        dut.io.buffered.expect(false.B)
      }
    }
  }

  for (withdraw <- Seq(false, true)) {
    it should s"detect a stalled sender contract violation withdraw=$withdraw" in {
      assertThrows[ChiselAssertionError] {
        test(new CompactChiMonitorHarness) { dut =>
          dut.io.ready.poke(false.B)
          dut.io.channel.valid.poke(true.B)
          dut.io.channel.bits.poke(123.U)
          dut.clock.step()
          if (withdraw) dut.io.channel.valid.poke(false.B)
          else dut.io.channel.bits.poke(124.U)
          dut.clock.step()
        }
      }
    }
  }

  it should "discard buffered traffic only at a coordinated reset" in {
    test(new CompactChiRequesterBuffer(CompactChiParams(), wayBits = 2)) { dut =>
      dut.io.core.req.valid.poke(false.B)
      dut.io.core.evt.valid.poke(false.B)
      dut.io.core.txRsp.valid.poke(false.B)
      dut.io.core.txDat.valid.poke(false.B)
      dut.io.link.rxRsp.valid.poke(false.B)
      dut.io.link.rxDat.valid.poke(false.B)
      dut.io.link.rxSnp.valid.poke(false.B)
      dut.io.link.req.ready.poke(false.B)
      dut.io.link.evt.ready.poke(false.B)
      dut.io.link.txRsp.ready.poke(false.B)
      dut.io.link.txDat.ready.poke(false.B)
      dut.io.core.rxRsp.ready.poke(false.B)
      dut.io.core.rxDat.ready.poke(false.B)
      dut.io.core.rxSnp.ready.poke(false.B)
      dut.io.core.req.bits.opcode.poke(CompactChiOpcode.Req.ReadOnce)
      dut.io.core.req.valid.poke(true.B)
      dut.clock.step()
      dut.io.core.req.valid.poke(false.B)
      dut.io.link.req.valid.expect(true.B)
      dut.io.buffered.expect(true.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.link.req.valid.expect(false.B)
      dut.io.buffered.expect(false.B)
      dut.clock.step()
    }
  }
}
