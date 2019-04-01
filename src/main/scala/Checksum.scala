package icenet

import chisel3._
import chisel3.util._
import freechips.rocketchip.unittest.UnitTest
import testchipip.{StreamIO, StreamChannel}
import IceNetConsts._

class ChecksumCalcRequest extends Bundle {
  val check  = Bool()
  val start = UInt(16.W)
  val init  = UInt(16.W)
}

class ChecksumRewriteRequest extends Bundle {
  val check  = Bool()
  val offset = UInt(16.W)
  val start  = UInt(16.W)
  val init   = UInt(16.W)
}

class ChecksumCalc(dataBits: Int) extends Module {
  val dataBytes = dataBits / 8

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ChecksumCalcRequest))
    val stream = new StreamIO(dataBits)
    val result = Decoupled(UInt(16.W))
  })

  val csum = Reg(UInt((dataBits + 16).W))
  val check = Reg(Bool())
  val start  = Reg(UInt(16.W))
  val startPos = Reg(UInt(16.W))
  val nextStartPos = startPos + dataBytes.U
  val sumMask = (0 until dataBytes).map { i =>
    io.stream.in.bits.keep(i) && (startPos + i.U) >= start
  }
  val sumData = io.stream.in.bits.data & FillInterleaved(8, sumMask)

  val s_req :: s_stream :: s_fold :: s_result :: Nil = Enum(4)
  val state = RegInit(s_req)

  io.req.ready := state === s_req
  io.stream.out.valid := state === s_stream && io.stream.in.valid
  io.stream.in.ready := state === s_stream && io.stream.out.ready
  io.stream.out.bits := io.stream.in.bits
  io.result.valid := state === s_result
  io.result.bits := csum(15, 0)

  when (io.req.fire()) {
    check := io.req.bits.check
    start := io.req.bits.start
    csum := io.req.bits.init
    startPos := 0.U
    state := s_stream
  }

  when (io.stream.in.fire()) {
    when (check) {
      csum := csum + sumData
      startPos := nextStartPos
    }

    when (io.stream.in.bits.last) {
      state := Mux(check, s_fold, s_req)
    }
  }

  when (state === s_fold) {
    val upper = csum(15 + dataBits, 16)
    val lower = csum(15, 0)

    when (upper === 0.U) {
      csum := ~lower
      state := s_result
    } .otherwise {
      csum := upper + lower
    }
  }

  when (io.result.fire()) { state := s_req }
}

class ChecksumRewrite(dataBits: Int, nBufFlits: Int) extends Module {
  val dataBytes = dataBits / 8

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new ChecksumRewriteRequest))
    val stream = new StreamIO(dataBits)
  })

  val calc = Module(new ChecksumCalc(dataBits))
  val buffer = Module(new Queue(new StreamChannel(dataBits), nBufFlits))
  val offset = Reg(UInt(16.W))
  val check  = Reg(Bool())
  val csum = Reg(UInt(16.W))

  val byteOffBits = log2Ceil(dataBytes)
  val startPos = Reg(UInt(16.W))
  val nextStartPos = startPos + dataBytes.U
  val baseData = buffer.io.deq.bits.data
  val shiftAmt = Cat((offset - startPos)(byteOffBits-1, 0), 0.U(3.W))
  val dataMask = ~(~0.U(8.W) << shiftAmt)
  val csumShifted = csum << shiftAmt
  val replace = check && (offset >= startPos) && (offset < nextStartPos)
  val outData = Mux(replace, (baseData & dataMask) | csumShifted, baseData)

  val s_req :: s_wait :: s_flush :: Nil = Enum(3)
  val state = RegInit(s_req)

  when (io.req.fire()) {
    check := io.req.bits.check
    offset := io.req.bits.offset
    startPos := 0.U
    state := Mux(io.req.bits.check, s_wait, s_flush)
  }

  when (calc.io.result.fire()) {
    csum := calc.io.result.bits
    state := s_flush
  }

  when (io.stream.out.fire()) {
    startPos := nextStartPos
    when (io.stream.out.bits.last) { state := s_req }
  }

  val deqOK = (state === s_flush || nextStartPos <= offset)

  io.req.ready := state === s_req && calc.io.req.ready
  calc.io.req.valid := state === s_req && io.req.valid
  calc.io.req.bits.check := io.req.bits.check
  calc.io.req.bits.start := io.req.bits.start
  calc.io.req.bits.init := io.req.bits.init
  calc.io.result.ready := state === s_wait
  calc.io.stream.in <> io.stream.in
  buffer.io.enq <> calc.io.stream.out

  io.stream.out.valid := buffer.io.deq.valid && deqOK
  buffer.io.deq.ready := io.stream.out.ready && deqOK
  io.stream.out.bits := buffer.io.deq.bits
  io.stream.out.bits.data := outData
}

class ChecksumTest extends UnitTest {
  val offset = 6
  val init = 0x4315
  val start = 2
  val data = Seq(0xdead, 0xbeef, 0x7432, 0x0000, 0xf00d, 0x3163, 0x9821, 0x1543)

  var csum = init + data.drop(start/2).reduce(_ + _)
  while (csum > 0xffff) {
    csum = (csum >> 16) + (csum & 0xffff)
  }
  csum = ~csum & 0xffff

  val expected = data.take(offset/2) ++
    Seq(csum) ++ data.drop(offset/2+1)

  def seqToVec(seq: Seq[Int], step: Int) = {
    VecInit((0 until seq.length by step).map { i =>
      Cat((i until (i + step)).map(seq(_).U(16.W)).reverse)
    })
  }

  val dataBits = 32
  val dataBytes = dataBits / 8
  val shortsPerFlit = dataBits / 16
  val dataVec = seqToVec(data, shortsPerFlit)
  val expectedVec = seqToVec(expected, shortsPerFlit)

  val s_start :: s_req :: s_input :: s_output :: s_done :: Nil = Enum(5)
  val state = RegInit(s_start)

  val rewriter = Module(new ChecksumRewrite(
    dataBits, data.length/shortsPerFlit))

  val (inIdx, inDone) = Counter(rewriter.io.stream.in.fire(), dataVec.length)
  val (outIdx, outDone) = Counter(rewriter.io.stream.out.fire(), expectedVec.length)

  rewriter.io.req.valid := state === s_req
  rewriter.io.req.bits.check  := true.B
  rewriter.io.req.bits.start  := start.U
  rewriter.io.req.bits.init   := init.U
  rewriter.io.req.bits.offset := offset.U
  rewriter.io.stream.in.valid := state === s_input
  rewriter.io.stream.in.bits.data := dataVec(inIdx)
  rewriter.io.stream.in.bits.keep := ~0.U(dataBytes.W)
  rewriter.io.stream.in.bits.last := inIdx === (dataVec.length-1).U
  rewriter.io.stream.out.ready := state === s_output
  io.finished := state === s_done

  when (state === s_start && io.start) { state := s_req }
  when (rewriter.io.req.fire()) { state := s_input }
  when (inDone) { state := s_output }
  when (outDone) { state := s_done }

  assert(!rewriter.io.stream.out.valid ||
    rewriter.io.stream.out.bits.data === expectedVec(outIdx),
    "ChecksumTest: got wrong data")
}