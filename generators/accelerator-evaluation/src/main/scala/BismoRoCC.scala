package chipyard.evaluation

import bismo.{BitSerialMatMulAccel, BitSerialMatMulParams}
import chisel3._
import chisel3.util._
import fpgatidbits.PlatformWrapper.PlatformWrapperParams
import fpgatidbits.dma.GenericMemoryMasterPort
import freechips.rocketchip.tile.{LazyRoCC, LazyRoCCModuleImp, OpcodeSet}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

/** Register access over RoCC and physical, single-beat DMA through Rocket's TL master port. */
class BismoRoCC(opcodes: OpcodeSet, params: BitSerialMatMulParams, platform: PlatformWrapperParams)
    (implicit p: Parameters) extends LazyRoCC(opcodes) {
  private val beatBytes = params.mrp.dataWidth / 8
  require(platform.memDataBits == params.mrp.dataWidth)
  require(platform.memAddrBits == params.mrp.addrWidth)
  require(platform.memIDBits == params.mrp.idWidth)
  require(platform.memMetaBits == params.mrp.metaDataWidth)

  private val dma = TLClientNode(Seq(TLMasterPortParameters.v1(
    Seq(TLMasterParameters.v1(name = "bismo-dma"))
  )))
  tlNode := TLWidthWidget(beatBytes) := dma

  override lazy val module = new LazyRoCCModuleImp(this) {
    private val registers = RegInit(VecInit(Seq.fill(51)(0.U(32.W))))
    private val cmd = Queue(io.cmd)
    private val address = cmd.bits.rs2
    private val index = address(5, 0)
    private val write = cmd.bits.inst.funct === 0.U
    private val read = cmd.bits.inst.funct === 1.U
    private val start = write && address === 27.U && cmd.bits.rs1(0)
    private val softReset = RegInit(false.B)
    private val launchActive = RegInit(false.B)
    softReset := false.B

    private val core = withReset(reset.asBool || softReset) {
      Module(new BitSerialMatMulAccel(params, platform))
    }
    private val accel = core.io
    private val launchCooldown = RegNext(accel.ins.fire, false.B)
    private val launchReady = !launchActive && !launchCooldown && !softReset
    private val status = WireDefault(registers)
    status(0) := 0.U
    status(1) := accel.signature
    status(5) := accel.fetch_op_count
    status(6) := accel.exec_op_count
    status(7) := accel.result_op_count
    status(8) := accel.hw.readChanWidth
    status(9) := accel.hw.writeChanWidth
    status(10) := accel.hw.dpaDimLHS
    status(11) := accel.hw.dpaDimRHS
    status(12) := accel.hw.dpaDimCommon
    status(13) := accel.hw.lhsEntriesPerMem
    status(14) := accel.hw.rhsEntriesPerMem
    status(15) := accel.hw.accWidth
    status(16) := accel.hw.maxShiftSteps
    status(17) := accel.hw.cmdQueueEntries
    status(18) := accel.perf.cc
    status(20) := accel.perf.prf_fetch.count
    status(22) := accel.perf.prf_exec.count
    status(24) := accel.perf.prf_res.count
    status(26) := launchReady
    status(27) := 0.U

    private val writable = Seq(2, 3, 4, 19, 21, 23, 25) ++ (28 to 50)
    when(cmd.fire && write) {
      writable.foreach { i =>
        when(address === i.U) { registers(i) := cmd.bits.rs1(31, 0) }
      }
      when(address === 0.U && cmd.bits.rs1(0)) {
        softReset := true.B
        registers := VecInit(Seq.fill(51)(0.U(32.W)))
      }
    }

    when(cmd.fire && start) { launchActive := true.B }
    when(accel.ins.fire) { launchActive := false.B }

    private val modifiesDescriptor = write && address >= 28.U && address < 51.U
    private val requestsReset = write && address === 0.U && cmd.bits.rs1(0)
    private val controllerReady = !softReset &&
      (!start || launchReady) && (!(modifiesDescriptor || requestsReset) || !launchActive)
    cmd.ready := controllerReady && (!cmd.bits.inst.xd || io.resp.ready)
    io.resp.valid := cmd.valid && cmd.bits.inst.xd && controllerReady
    io.resp.bits.rd := cmd.bits.inst.rd
    io.resp.bits.data := Mux(read && address < 51.U, status(index), 0.U)
    io.mem.req.valid := false.B
    io.interrupt := false.B

    accel.fetch_enable := registers(2)(0)
    accel.exec_enable := registers(3)(0)
    accel.result_enable := registers(4)(0)
    accel.perf.cc_enable := registers(19)(0)
    accel.perf.prf_fetch.sel := registers(21)
    accel.perf.prf_exec.sel := registers(23)
    accel.perf.prf_res.sel := registers(25)

    accel.ins.valid := launchActive
    accel.ins.bits.dram_base_lhs := registers(28)
    accel.ins.bits.dram_base_rhs := registers(29)
    accel.ins.bits.dram_block_offset_bytes := registers(30)
    accel.ins.bits.dram_block_size_bytes := registers(31)
    accel.ins.bits.dram_block_count := registers(32)
    accel.ins.bits.tiles_per_row := registers(33)
    accel.ins.bits.numTiles := registers(34)
    accel.ins.bits.shiftAmount := registers(35)
    accel.ins.bits.negate := registers(36)(0)
    accel.ins.bits.dram_base := registers(37)
    accel.ins.bits.dram_skip := registers(38)
    accel.ins.bits.wait_complete_bytes := registers(39)
    accel.ins.bits.lhs_l0_per_l1 := registers(40)
    accel.ins.bits.rhs_l0_per_l1 := registers(41)
    accel.ins.bits.lhs_l1_per_l2 := registers(42)
    accel.ins.bits.rhs_l1_per_l2 := registers(43)
    accel.ins.bits.lhs_l2_per_matrix := registers(44)
    accel.ins.bits.rhs_l2_per_matrix := registers(45)
    accel.ins.bits.z_l2_per_matrix := registers(46)
    accel.ins.bits.lhs_bytes_per_l2 := registers(47)
    accel.ins.bits.rhs_bytes_per_l2 := registers(48)
    accel.ins.bits.nrows_a := registers(49)
    accel.ins.bits.dpa_z_bytes := registers(50)

    private val (tl, edge) = dma.out(0)
    require(edge.bundle.dataBits == params.mrp.dataWidth)
    private val mem: GenericMemoryMasterPort = accel.memPort(0)
    private val idle :: reading :: writing :: Nil = Enum(3)
    private val dmaState = RegInit(idle)
    private val channel = Reg(UInt(params.mrp.idWidth.W))
    private val readReq = mem.memRdReq
    private val writeReq = mem.memWrReq
    private val writeData = mem.memWrDat
    private val chooseWrite = writeReq.valid && writeData.valid
    private val size = log2Ceil(beatBytes).U
    private val (readLegal, get) = edge.Get(0.U, readReq.bits.addr, size)
    private val (writeLegal, put) = edge.Put(0.U, writeReq.bits.addr, size, writeData.bits)
    // Buffer the selected request so the TL A payload remains stable under backpressure.
    private val requests = Module(new Queue(chiselTypeOf(tl.a.bits), 1))
    tl.a <> requests.io.deq
    requests.io.enq.valid := dmaState === idle && (chooseWrite || readReq.valid)
    requests.io.enq.bits := Mux(chooseWrite, put, get)
    writeReq.ready := dmaState === idle && writeData.valid && requests.io.enq.ready
    writeData.ready := dmaState === idle && writeReq.valid && requests.io.enq.ready
    readReq.ready := dmaState === idle && !chooseWrite && requests.io.enq.ready
    when(requests.io.enq.fire) {
      assert(Mux(chooseWrite, writeLegal, readLegal), "BISMO DMA address is outside the negotiated TL map")
      assert(Mux(chooseWrite, writeReq.bits.numBytes, readReq.bits.numBytes) === beatBytes.U)
      assert(Mux(chooseWrite, writeReq.bits.addr, readReq.bits.addr)(log2Ceil(beatBytes) - 1, 0) === 0.U)
      channel := Mux(chooseWrite, writeReq.bits.channelID, readReq.bits.channelID)
      dmaState := Mux(chooseWrite, writing, reading)
    }
    when(tl.d.fire) {
      assert(!tl.d.bits.denied && !tl.d.bits.corrupt, "BISMO DMA access failed")
      dmaState := idle
    }
    tl.d.ready := Mux(dmaState === writing, mem.memWrRsp.ready,
      dmaState === reading && mem.memRdRsp.ready)
    mem.memRdRsp.valid := dmaState === reading && tl.d.valid
    mem.memRdRsp.bits.driveDefaults()
    mem.memRdRsp.bits.readData := tl.d.bits.data
    mem.memRdRsp.bits.isLast := true.B
    mem.memRdRsp.bits.channelID := channel
    mem.memWrRsp.valid := dmaState === writing && tl.d.valid
    mem.memWrRsp.bits.driveDefaults()
    mem.memWrRsp.bits.isWrite := true.B
    mem.memWrRsp.bits.isLast := true.B
    mem.memWrRsp.bits.channelID := channel
    io.busy := cmd.valid || launchActive || dmaState =/= idle
  }
}
