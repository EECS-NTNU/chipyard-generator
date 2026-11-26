package btbfixture.integrations.boom

import boom.v3.common.BoomTileAttachParams
import boom.v3.ifu._
import chisel3._
import freechips.rocketchip.subsystem.{InSubsystem, TilesLocated}
import org.chipsalliance.cde.config.{Config, Parameters}

final class FixtureBTB(implicit p: Parameters)
    extends BTBBranchPredictorBank(
      BoomBTBParams(nSets = btbfixture.Module.sets, nWays = btbfixture.Module.ways,
        extendedNSets = btbfixture.Module.sets)
    )(p) {
  private val trained = RegInit(false.B)
  when (!trained && io.update.valid && io.update.bits.is_commit_update &&
      io.update.bits.cfi_idx.valid && io.update.bits.cfi_is_br && io.update.bits.cfi_taken) {
    trained := true.B
    printf("Custom BTB trained a taken branch\n")
  }
}

class WithFixtureBTB extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) =>
    val tiles = up(TilesLocated(InSubsystem), site)
    require(tiles.exists(_.isInstanceOf[BoomTileAttachParams]),
      "Unsupported integration: the fixture BTB requires a BOOM v3 predictor hook")
    tiles.map {
      case tile: BoomTileAttachParams =>
        val core = tile.tileParams.core.copy(
          branchPredictor = (input: BranchPredictionBankResponse, p: Parameters) => {
            val loop = Module(new LoopBranchPredictorBank()(p))
            val tage = Module(new TageBranchPredictorBank()(p))
            val btb = Module(new FixtureBTB()(p))
            val bim = Module(new BIMBranchPredictorBank()(p))
            val ubtb = Module(new FAMicroBTBBranchPredictorBank()(p))
            val banks = Seq(loop, tage, btb, ubtb, bim)
            banks.foreach(_.io := DontCare)
            ubtb.io.resp_in(0) := input
            bim.io.resp_in(0) := ubtb.io.resp
            btb.io.resp_in(0) := bim.io.resp
            tage.io.resp_in(0) := btb.io.resp
            loop.io.resp_in(0) := tage.io.resp
            (banks, loop.io.resp)
          }
        )
        tile.copy(tileParams = tile.tileParams.copy(core = core))
      case tile => tile
    }
})
