//******************************************************************************
// Copyright (c) 2015 - 2020, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Execution Unit Generator + Container Module for the Ring Microarchitecture
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import scala.collection.mutable.{ArrayBuffer}

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.rocket.{BP}
import freechips.rocketchip.tile

import boom.common._
import boom.util._
import boom.ifu.{GetPCFromFtqIO}

class RingExecutionUnits(implicit p: Parameters) extends BoomModule
{
  val io = IO(new BoomBundle {
    // I/O used by all units
    val exe_reqs  = Vec(coreWidth, Flipped(DecoupledIO(new FuncUnitReq(xLen))))
    val exe_resps = Output(Vec(coreWidth, Valid(new ExeUnitResp(xLen))))
    val ll_resps  = Output(Vec(coreWidth, Valid(new ExeUnitResp(xLen))))

    val brinfo    = Input(new BrResolutionInfo)
    val kill      = Input(Bool())

    // TODO get rid of this output
    val bypass = Output(new BypassData(coreWidth, xLen))

    // only used by the mem unit
    val lsu_io = Vec(memWidth, Flipped(new boom.lsu.LSUExeIO))
    val bp     = Input(Vec(nBreakpoints, new BP))

    // only used by the branch unit
    val br_unit    = Output(new BranchUnitResp)
    val get_ftq_pc = Flipped(new GetPCFromFtqIO)
    val status     = Input(new freechips.rocketchip.rocket.MStatus)

    // only used by the div unit
    val idiv_busy = Output(Bool())

    // only used by the CSR unit
    val csr_unit_resp = DecoupledIO(new FuncUnitResp(xLen))

    // only used by the rocc unit
    val rocc = if (usingRoCC) new RoCCShimCoreIO else null

    // only used by the fpu unit
    val fcsr_rm = if (usingFPU) Input(Bits(tile.FPConstants.RM_SZ.W)) else null

    // TODO move this out of ExecutionUnit
    val com_exception = Input(Bool())
  })

  //----------------------------------------------------------------------------------------------------
  // Instantiate the ExecutionUnits

  private val column_exe_units = ArrayBuffer[ExecutionUnit]()
  private val shared_exe_units = ArrayBuffer[ExecutionUnit]()

  def exe_units = column_exe_units ++ shared_exe_units

  //----------------------------------------------------------------------------------------------------
  // Getters

  def length = exe_units.length

  def apply(n: Int) = exe_units(n)

  def map[T](f: ExecutionUnit => T) = {
    exe_units.map(f)
  }

  def withFilter(f: ExecutionUnit => Boolean) = {
    exe_units.withFilter(f)
  }

  def foreach[U](f: ExecutionUnit => U) = {
    exe_units.foreach(f)
  }

  def zipWithIndex = {
    exe_units.zipWithIndex
  }

  def indexWhere(f: ExecutionUnit => Boolean) = {
    exe_units.indexWhere(f)
  }

  def count(f: ExecutionUnit => Boolean) = {
    exe_units.count(f)
  }

  def mem_units = {
    exe_units.filter(_.hasMem)
  }

  def br_unit = {
    require (exe_units.count(_.hasBrUnit) == 1)
    exe_units.find(_.hasBrUnit).get
  }

  def csr_unit = {
    require (exe_units.count(_.hasCSR) == 1)
    exe_units.find(_.hasCSR).get
  }

  def ifpu_unit = {
    require (usingFPU)
    require (exe_units.count(_.hasIfpu) == 1)
    exe_units.find(_.hasIfpu).get
  }

  def fpiu_unit = {
    require (usingFPU)
    require (exe_units.count(_.hasFpiu) == 1)
    exe_units.find(_.hasFpiu).get
  }

  def br_unit_io = {
    require (exe_units.count(_.hasBrUnit) == 1)
    (exe_units.find(_.hasBrUnit).get).io.br_unit
  }

  def br_unit_idx = {
    exe_units.indexWhere(_.hasBrUnit)
  }

  def rocc_unit = {
    require (usingRoCC)
    require (exe_units.count(_.hasRocc) == 1)
    exe_units.find(_.hasRocc).get
  }

  def idiv_busy = {
    !exe_units.find(_.hasDiv).get.io.fu_types(4)
  }

  //----------------------------------------------------------------------------------------------------
  // Generate the units

  // Generate column ALUs
  for (w <- 0 until coreWidth) {
    val alu = Module(new ALUExeUnit)
    alu.suggestName("alu_" + w)
    column_exe_units += alu
  }

  // Generate memory access units. Only 1 supported for now
  for (w <- 0 until memWidth) {
    val mem_unit = Module(new ALUExeUnit(
      hasAlu = false,
      hasMem = true))
    mem_unit.suggestName("mem_unit_" + w)
    mem_unit.io.ll_iresp.ready := DontCare
    shared_exe_units += mem_unit
  }

  // Branch unit
  val br_exe_unit = Module(new ALUExeUnit(hasBrUnit = true))
  br_exe_unit.suggestName("br_unit")
  shared_exe_units += br_exe_unit

  // Put remaining functional units in a shared execution unit
  val misc_unit = Module(new ALUExeUnit(hasMul  = true,
                                        hasDiv  = true,
                                        hasCSR  = true))
  misc_unit.suggestName("misc_unit")
  shared_exe_units += misc_unit

  //----------------------------------------------------------------------------------------------------
  // Generator string output

  val exeUnitsStr = new StringBuilder
  for (exe_unit <- exe_units) {
    exeUnitsStr.append(exe_unit.toString)
  }

  override def toString: String =
    (BoomCoreStringPrefix("===ExecutionUnits===") + "\n"
    + (BoomCoreStringPrefix(
         "==" + coreWidth + "-wide Machine==",
         "==" + coreWidth + " Issue==")
    ) + "\n"
    + exeUnitsStr.toString)

  //----------------------------------------------------------------------------------------------------
  // Req -> EU crossbar

  val xbarSize = shared_exe_units.length + 1
  val col_sels = Transpose(VecInit(io.exe_reqs.map(req => req.bits.uop.eu_code & Fill(xbarSize, req.valid))))

  // Hookup column units
  for (w <- 0 until coreWidth) {
    column_exe_units(w).io.req.bits  := io.exe_reqs(w).bits
    column_exe_units(w).io.req.valid := col_sels(0)(w)

    column_exe_units(w).io.brinfo := io.brinfo
    column_exe_units(w).io.kill   := io.kill

    column_exe_units(w).io.iresp.ready := DontCare
  }

  // Hookup shared units
  for ((i,eu) <- (1 until xbarSize) zip shared_exe_units) {
    eu.io.req.bits  := Mux1H(col_sels(i), io.exe_reqs.map(_.bits))
    eu.io.req.valid := col_sels(i).orR

    assert (PopCount(col_sels(i)) <= 1.U, "[exe] shared unit request crossbar collision on port " + i)

    eu.io.brinfo := io.brinfo
    eu.io.kill   := io.kill

    if (eu.writesIrf) eu.io.iresp.ready := DontCare
  }

  //----------------------------------------------------------------------------------------------------
  // EU -> Fast Resp crossbar

  val fast_eu_sels = Transpose(VecInit(Seq(VecInit(column_exe_units.map(_.io.iresp.valid)).asUInt) ++
    shared_exe_units.filter(_.writesIrf).map(eu => eu.io.iresp.bits.uop.dst_col & Fill(coreWidth, eu.io.iresp.valid))))

  for (w <- 0 until coreWidth) {
    io.exe_resps(w).bits  := Mux1H(fast_eu_sels(w), Seq(column_exe_units(w).io.iresp.bits) ++ shared_exe_units.filter(_.writesIrf).map(_.io.iresp.bits))
    io.exe_resps(w).valid := fast_eu_sels(w).orR

    assert (PopCount(fast_eu_sels(w)) <= 1.U, "[exe] writeback crossbar collision on port " + w)
  }

  //----------------------------------------------------------------------------------------------------
  // EU -> Slow (LL) Resp crossbar

  val slow_eu_reqs = Transpose(VecInit(shared_exe_units.filter(_.writesLlIrf).map(eu =>
    eu.io.ll_iresp.bits.uop.dst_col & Fill(coreWidth, eu.io.ll_iresp.valid)).asUInt))
  val slow_eu_gnts = Transpose(VecInit(slow_eu_reqs.map(r => PriorityEncoderOH(r))))

  for (w <- 0 until coreWidth) {
    io.ll_resps(w).bits  := PriorityMux(slow_eu_reqs(w), shared_exe_units.filter(_.writesLlIrf).map(_.io.ll_iresp.bits))
    io.ll_resps(w).valid := slow_eu_reqs(w).orR
  }

  for (eu <- shared_exe_units.filter(_.writesLlIrf)) {
    eu.io.ll_iresp.ready := (slow_eu_gnts(i) & eu.io.ll_iresp.bits.uop.dst_col).orR
  }

  //----------------------------------------------------------------------------------------------------
  // Punch through misc unit I/O to core

  // ALU bypasses
  io.bypass := DontCare
  for (w <- 0 until coreWidth) {
    io.bypass.data((w + 1) % coreWidth) := column_exe_units(w).io.bypass.data(0)
  }

  // Memory access units
  for ((mem_unit, w) <- mem_units.zipWithIndex) {
    mem_unit.io.lsu_io <> io.lsu_io(w)
    mem_unit.io.bp     := io.bp
    mem_unit.io.status := io.status
    mem_unit.io.com_exception := io.com_exception
  }

  // Branch unit
  io.br_unit := br_unit_io
  br_unit.io.get_ftq_pc <> io.get_ftq_pc
  br_unit.io.status := io.status

  // Div unit
  io.idiv_busy := idiv_busy

  // CSR unit
  io.csr_unit_resp <> csr_unit.io.iresp

  // Core <-> FPU transfer units
  if (usingFPU) {
    for (unit <- exe_units.filter(_.hasFcsr)) {
      unit.io.fcsr_rm := io.fcsr_rm
    }
  }

  // RoCC unit
  if (usingRoCC) {
    rocc_unit.io.rocc <> io.rocc
    rocc_unit.io.com_exception := io.com_exception
  }
}
