package org.lflang.generator.c;

import static org.lflang.util.StringUtil.addDoubleQuotes;

import java.util.ArrayList;
import java.util.List;
import org.lflang.generator.ActionInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.TimerInstance;

/**
 * Generates C code to support tracing.
 *
 * @author Edward A. Lee
 * @author Soroush Bateni
 * @author Hou Seng Wong
 */
public class CTracingGenerator {
  /**
   * If tracing is turned on, then generate code that records the full name of the specified reactor
   * instance in the trace table. If tracing is not turned on, do nothing.
   *
   * <p>If tracing is turned on, record the address of this reaction in the
   * _lf_trace_object_descriptions table that is used to generate the header information in the
   * trace file.
   *
   * @param instance The reactor instance.
   */
  public static String generateTraceTableEntries(ReactorInstance instance) {
    List<String> code = new ArrayList<>();
    var description = CUtil.getShortenedName(instance);
    var selfStruct = CUtil.reactorRef(instance);
    var envRef = "&" + CUtil.getEnvironmentStruct(instance);
    code.add(registerTraceEvent(envRef, selfStruct, "NULL", "trace_reactor", description));
    for (ActionInstance action : instance.actions) {
      code.add(
          registerTraceEvent(
              envRef,
              selfStruct,
              getTrigger(selfStruct, action.getName()),
              "trace_trigger",
              description + "." + action.getName()));
    }
    for (TimerInstance timer : instance.timers) {
      code.add(
          registerTraceEvent(
              envRef,
              selfStruct,
              getTrigger(selfStruct, timer.getName()),
              "trace_trigger",
              description + "." + timer.getName()));
    }
    return String.join("\n", code);
  }

  private static String registerTraceEvent(
      String env, String obj, String trigger, String type, String description) {
    return "_lf_register_trace_event("
        + env
        + ", "
        + obj
        + ", "
        + trigger
        + ", "
        + type
        + ", "
        + addDoubleQuotes(description)
        + ");";
  }

  private static String getTrigger(String obj, String triggerName) {
    return "&(" + obj + "->_lf__" + triggerName + ")";
  }
}
