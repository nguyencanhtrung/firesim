//See LICENSE for license details
package firesim.bridges

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._

import testchipip.{SerializableTileTraceIO, TileTraceIO, TraceBundleWidths}

import midas.targetutils.TriggerSource
import midas.widgets._

class TracerVTargetIO(widths: TraceBundleWidths) extends Bundle {
  val trace         = Input(new SerializableTileTraceIO(widths))
  val triggerCredit = Output(Bool())
  val triggerDebit  = Output(Bool())
}

/** Target-side module for the TracerV Bridge.
  *
  * @param insnWidths
  *   A case class containing the widths of configurable-length fields in the trace interface.
  *
  * @param numInsns
  *   The number of instructions captured in a single a cycle (generally, the commit width of the pipeline)
  *
  * Warning: If you're not going to use the companion object to instantiate this bridge you must call
  * [[TracerVBridge.generateTriggerAnnotations] _in the parent module_.
  */
class TracerVBridge(widths: TraceBundleWidths)
    extends BlackBox
    with Bridge[HostPortIO[TracerVTargetIO], TracerVBridgeModule] {
  require(widths.retireWidth > 0, "TracerVBridge: number of instructions must be larger than 0")
  val io                                 = IO(new TracerVTargetIO(widths))
  val bridgeIO                           = HostPort(io)
  val constructorArg                     = Some(widths)
  generateAnnotations()
  // Use in parent module: annotates the bridge instance's ports to indicate its trigger sources
  // def generateTriggerAnnotations(): Unit = TriggerSource(io.triggerCredit, io.triggerDebit)
  def generateTriggerAnnotations(): Unit =
    TriggerSource.evenUnderReset(WireDefault(io.triggerCredit), WireDefault(io.triggerDebit))

  // To placate CheckHighForm, uniquify blackbox module names by using the
  // bridge's instruction count as a string suffix. This ensures that TracerV
  // blackboxes with different instruction counts will have different defnames,
  // preventing FIRRTL CheckHighForm failure when using a chipyard "Hetero"
  // config. While a black box parameter relaxes the check on leaf field
  // widths, CheckHighForm does not permit parameterizations of the length of a
  // Vec enclosing those fields (as is the case here), since the Vec is lost in
  // a lowered verilog module.
  //
  // See https://github.com/firesim/firesim/issues/729.
  def defnameSuffix = s"_${widths.retireWidth}Wide_" + widths.toString.replaceAll("[(),]", "_")

  override def desiredName = super.desiredName + defnameSuffix
}

object TracerVBridge {
  def apply(widths: TraceBundleWidths)(implicit p: Parameters): TracerVBridge = {
    val tracerv = Module(new TracerVBridge(widths))
    tracerv.generateTriggerAnnotations
    tracerv.io.trace.clock := Module.clock
    tracerv.io.trace.reset := Module.reset
    tracerv
  }

  def apply(tracedInsns: TileTraceIO)(implicit p: Parameters): TracerVBridge = {
    val tracerv = withClockAndReset(tracedInsns.clock, tracedInsns.reset) {
      TracerVBridge(tracedInsns.traceBundleWidths)
    }
    tracerv.io.trace := tracedInsns.asSerializableTileTrace
    tracerv
  }
}

class TracerVBridgeModule(key: TraceBundleWidths)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[TracerVTargetIO]]()(p)
    with StreamToHostCPU {

  // StreamToHostCPU  mixin parameters
  // Use the legacy NIC depth
  val toHostCPUQueueDepth = 6144

  lazy val module = new BridgeModuleImp(this) {
    val io    = IO(new WidgetIO)
    val hPort = IO(HostPort(new TracerVTargetIO(key)))

    // Mask off valid committed instructions when under reset
    val traces            = hPort.hBits.trace.trace.insns.map({ unmasked =>
      val masked = WireDefault(unmasked)
      masked.valid := unmasked.valid && !hPort.hBits.trace.reset.asBool
      masked
    })
    private val pcWidth   = traces.map(_.iaddr.getWidth).max
    private val insnWidth = traces.map(_.insn.getWidth).max
    val cycleCountWidth   = 64

    // Set after trigger-dependent memory-mapped registers have been set, to
    // prevent spurious credits
    val initDone                 = genWORegInit(Wire(Bool()), "initDone", false.B)
    // When unset, diables token capture to improve FMR, while still enabling the
    // use of TracerV-based triggers
    val traceEnable              = genWORegInit(Wire(Bool()), "traceEnable", true.B)
    //Program Counter trigger value can be configured externally
    val hostTriggerPCWidthOffset = pcWidth - p(CtrlNastiKey).dataBits
    val hostTriggerPCLowWidth    = if (hostTriggerPCWidthOffset > 0) p(CtrlNastiKey).dataBits else pcWidth
    val hostTriggerPCHighWidth   = if (hostTriggerPCWidthOffset > 0) hostTriggerPCWidthOffset else 0

    val hostTriggerPCStartHigh = RegInit(0.U(hostTriggerPCHighWidth.W))
    val hostTriggerPCStartLow  = RegInit(0.U(hostTriggerPCLowWidth.W))
    attach(hostTriggerPCStartHigh, "hostTriggerPCStartHigh", WriteOnly)
    attach(hostTriggerPCStartLow, "hostTriggerPCStartLow", WriteOnly)
    val hostTriggerPCStart     = Cat(hostTriggerPCStartHigh, hostTriggerPCStartLow)
    val triggerPCStart         = RegInit(0.U(pcWidth.W))
    triggerPCStart := hostTriggerPCStart

    val hostTriggerPCEndHigh = RegInit(0.U(hostTriggerPCHighWidth.W))
    val hostTriggerPCEndLow  = RegInit(0.U(hostTriggerPCLowWidth.W))
    attach(hostTriggerPCEndHigh, "hostTriggerPCEndHigh", WriteOnly)
    attach(hostTriggerPCEndLow, "hostTriggerPCEndLow", WriteOnly)
    val hostTriggerPCEnd     = Cat(hostTriggerPCEndHigh, hostTriggerPCEndLow)
    val triggerPCEnd         = RegInit(0.U(pcWidth.W))
    triggerPCEnd := hostTriggerPCEnd

    //Cycle count trigger
    val hostTriggerCycleCountWidthOffset = 64 - p(CtrlNastiKey).dataBits
    val hostTriggerCycleCountLowWidth    = if (hostTriggerCycleCountWidthOffset > 0) p(CtrlNastiKey).dataBits else 64
    val hostTriggerCycleCountHighWidth   =
      if (hostTriggerCycleCountWidthOffset > 0) hostTriggerCycleCountWidthOffset else 0

    val hostTriggerCycleCountStartHigh = RegInit(0.U(hostTriggerCycleCountHighWidth.W))
    val hostTriggerCycleCountStartLow  = RegInit(0.U(hostTriggerCycleCountLowWidth.W))
    attach(hostTriggerCycleCountStartHigh, "hostTriggerCycleCountStartHigh", WriteOnly)
    attach(hostTriggerCycleCountStartLow, "hostTriggerCycleCountStartLow", WriteOnly)
    val hostTriggerCycleCountStart     = Cat(hostTriggerCycleCountStartHigh, hostTriggerCycleCountStartLow)
    val triggerCycleCountStart         = RegInit(0.U(cycleCountWidth.W))
    triggerCycleCountStart := hostTriggerCycleCountStart

    val hostTriggerCycleCountEndHigh = RegInit(0.U(hostTriggerCycleCountHighWidth.W))
    val hostTriggerCycleCountEndLow  = RegInit(0.U(hostTriggerCycleCountLowWidth.W))
    attach(hostTriggerCycleCountEndHigh, "hostTriggerCycleCountEndHigh", WriteOnly)
    attach(hostTriggerCycleCountEndLow, "hostTriggerCycleCountEndLow", WriteOnly)
    val hostTriggerCycleCountEnd     = Cat(hostTriggerCycleCountEndHigh, hostTriggerCycleCountEndLow)
    val triggerCycleCountEnd         = RegInit(0.U(cycleCountWidth.W))
    triggerCycleCountEnd := hostTriggerCycleCountEnd

    val trace_cycle_counter = RegInit(0.U(cycleCountWidth.W))

    //target instruction type trigger (trigger through target software)
    //can configure the trigger instruction type externally though simulation driver
    val hostTriggerStartInst     = RegInit(0.U(insnWidth.W))
    val hostTriggerStartInstMask = RegInit(0.U(insnWidth.W))
    attach(hostTriggerStartInst, "hostTriggerStartInst", WriteOnly)
    attach(hostTriggerStartInstMask, "hostTriggerStartInstMask", WriteOnly)

    val hostTriggerEndInst     = RegInit(0.U(insnWidth.W))
    val hostTriggerEndInstMask = RegInit(0.U(insnWidth.W))
    attach(hostTriggerEndInst, "hostTriggerEndInst", WriteOnly)
    attach(hostTriggerEndInstMask, "hostTriggerEndInstMask", WriteOnly)

    //trigger selector
    val triggerSelector = RegInit(0.U((p(CtrlNastiKey).dataBits).W))
    attach(triggerSelector, "triggerSelector", WriteOnly)

    //set the trigger
    //assert(triggerCycleCountEnd >= triggerCycleCountStart)
    val triggerCycleCountVal = RegInit(false.B)
    triggerCycleCountVal := (trace_cycle_counter >= triggerCycleCountStart) & (trace_cycle_counter <= triggerCycleCountEnd)

    val triggerPCValVec = RegInit(VecInit(Seq.fill(traces.length)(false.B)))
    traces.zipWithIndex.foreach { case (trace, i) =>
      when(trace.valid) {
        when(triggerPCStart === trace.iaddr) {
          triggerPCValVec(i) := true.B
        }.elsewhen((triggerPCEnd === trace.iaddr) && triggerPCValVec(i)) {
          triggerPCValVec(i) := false.B
        }
      }
    }

    val triggerInstValVec = RegInit(VecInit(Seq.fill(traces.length)(false.B)))
    traces.zipWithIndex.foreach { case (trace, i) =>
      when(trace.valid) {
        when(!((hostTriggerStartInst ^ trace.insn) & hostTriggerStartInstMask).orR) {
          triggerInstValVec(i) := true.B
        }.elsewhen(!((hostTriggerEndInst ^ trace.insn) & hostTriggerEndInstMask).orR) {
          triggerInstValVec(i) := false.B
        }
      }
    }

    val trigger = MuxLookup(
      triggerSelector,
      false.B,
      Seq(
        0.U -> true.B,
        1.U -> triggerCycleCountVal,
        2.U -> triggerPCValVec.reduce(_ || _),
        3.U -> triggerInstValVec.reduce(_ || _),
      ),
    )

    // the maximum width of a single arm, this is determined by the 512 bit width of a single beat
    val armWidth = 7

    // divide with a ceiling round, to get the total number of arms
    val armCount = (traces.length + armWidth - 1) / armWidth

    // A Seq of Seq which represents each arm of the mux
    val allTraceArms = traces.grouped(armWidth).toSeq

    // an intermediate value used to build allStreamBits
    val allUintTraces = allTraceArms.map(arm => arm.map((trace => Cat(trace.valid, trace.iaddr).pad(64))).reverse)

    // Literally each arm of the mux, these are directly the bits that get put into the bump
    val allStreamBits =
      allUintTraces.map(uarm => Cat(uarm :+ trace_cycle_counter.pad(64)).pad(BridgeStreamConstants.streamWidthBits))

    // Number of bits to use for the counter, the +1 is required because the counter will count 1 past the number of arms
    val counterBits = log2Ceil(armCount + 1)

    // This counter acts to select the mux arm
    val counter = RegInit(0.U(counterBits.W))

    // The main mux where the input arms are different possible valid traces, and the output goes to streamEnq
    val streamMux = MuxLookup(counter, allStreamBits(0), Seq.tabulate(armCount)(x => x.U -> allStreamBits(x)))

    // a parallel set of arms to a parallel mux, true if any instructions in the arm are valid (OR reduction)
    val anyValid = allTraceArms.map(arm => arm.map(trace => trace.valid).reduce((a, b) => (a | b)))

    // all of the valids of the larger indexed arms are OR reduced
    val anyValidRemain    =
      Seq.tabulate(armCount)(idx => (idx until armCount).map(x => anyValid(x)).reduce((a, b) => (a | b)))
    val anyValidRemainMux = MuxLookup(counter, false.B, Seq.tabulate(armCount)(x => x.U -> anyValidRemain(x)))

    streamEnq.bits := streamMux

    val maybeFire = !anyValidRemainMux || (counter === (armCount - 1).U)
    val maybeEnq  = anyValidRemainMux

    val commonPredicates = Seq(hPort.toHost.hValid, hPort.fromHost.hReady, streamEnq.ready, initDone)
    val do_enq_helper  = DecoupledHelper((Seq(maybeEnq, traceEnable) ++ commonPredicates):_*)
    val do_fire_helper = DecoupledHelper((maybeFire +: commonPredicates):_*)

    // Note, if we dequeue a token that wins out over the increment below
    when(do_fire_helper.fire()) {
      counter := 0.U
    }.elsewhen(do_enq_helper.fire()) {
      counter := counter + 1.U
    }

    streamEnq.valid     := do_enq_helper.fire(streamEnq.ready, trigger)
    hPort.toHost.hReady := do_fire_helper.fire(hPort.toHost.hValid)

    // Output token (back to hub model) handling.
    val triggerReg = RegEnable(trigger, false.B, do_fire_helper.fire())
    hPort.hBits.triggerDebit  := !trigger && triggerReg
    hPort.hBits.triggerCredit := trigger && !triggerReg

    hPort.fromHost.hValid := do_fire_helper.fire(hPort.fromHost.hReady)

    when(hPort.toHost.fire) {
      trace_cycle_counter := trace_cycle_counter + 1.U
    }

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
        base,
        sb,
        "tracerv_t",
        "tracerv",
        Seq(
          UInt32(toHostStreamIdx),
          UInt32(toHostCPUQueueDepth),
          UInt32(traces.size),
          Verbatim(clockDomainInfo.toC),
        ),
        hasStreams = true,
      )
    }
  }
}
