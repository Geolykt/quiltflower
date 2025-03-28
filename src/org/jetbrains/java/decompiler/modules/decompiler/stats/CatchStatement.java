// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.stats;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.cfg.BasicBlock;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.util.TextBuffer;
import org.jetbrains.java.decompiler.main.collectors.BytecodeMappingTracer;
import org.jetbrains.java.decompiler.main.collectors.CounterContainer;
import org.jetbrains.java.decompiler.modules.decompiler.DecHelper;
import org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor;
import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.TextBuffer;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

public final class CatchStatement extends Statement {
  public static final int NORMAL = 0;
  public static final int RESORCES = 1;

  private final List<List<String>> exctstrings = new ArrayList<>();
  private final List<VarExprent> vars = new ArrayList<>();
  private final List<Exprent> resources = new ArrayList<>();

  private int tryType;

  // *****************************************************************************
  // constructors
  // *****************************************************************************

  private CatchStatement() {
    type = TYPE_TRYCATCH;
    tryType = NORMAL;
  }

  private CatchStatement(Statement head, Statement next, Set<Statement> setHandlers) {
    this();

    first = head;
    stats.addWithKey(first, first.id);

    for (StatEdge edge : head.getSuccessorEdges(StatEdge.TYPE_EXCEPTION)) {
      Statement stat = edge.getDestination();

      if (setHandlers.contains(stat)) {
        stats.addWithKey(stat, stat.id);
        exctstrings.add(new ArrayList<>(edge.getExceptions()));
        
        vars.add(new VarExprent(DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.VAR_COUNTER),
                                new VarType(CodeConstants.TYPE_OBJECT, 0, edge.getExceptions().get(0)),
                                // FIXME: for now simply the first type. Should get the first common superclass when possible.
                                DecompilerContext.getVarProcessor()));
      }
    }

    if (next != null) {
      post = next;
    }
  }

  // *****************************************************************************
  // public methods
  // *****************************************************************************

  public static Statement isHead(Statement head) {
    if (head.getLastBasicType() != LASTBASICTYPE_GENERAL) {
      return null;
    }

    Set<Statement> setHandlers = DecHelper.getUniquePredExceptions(head);
    if (!setHandlers.isEmpty()) {
      int hnextcount = 0; // either no statements with connection to next, or more than 1

      Statement next = null;
      List<StatEdge> lstHeadSuccs = head.getSuccessorEdges(STATEDGE_DIRECT_ALL);
      if (!lstHeadSuccs.isEmpty() && lstHeadSuccs.get(0).getType() == StatEdge.TYPE_REGULAR) {
        next = lstHeadSuccs.get(0).getDestination();
        hnextcount = 2;
      }

      for (StatEdge edge : head.getSuccessorEdges(StatEdge.TYPE_EXCEPTION)) {
        Statement stat = edge.getDestination();

        boolean handlerok = true;

        if (edge.getExceptions() != null && setHandlers.contains(stat)) {
          if (stat.getLastBasicType() != LASTBASICTYPE_GENERAL) {
            handlerok = false;
          }
          else {
            List<StatEdge> lstStatSuccs = stat.getSuccessorEdges(STATEDGE_DIRECT_ALL);
            if (!lstStatSuccs.isEmpty() && lstStatSuccs.get(0).getType() == StatEdge.TYPE_REGULAR) {

              Statement statn = lstStatSuccs.get(0).getDestination();

              if (next == null) {
                next = statn;
              }
              else if (next != statn) {
                handlerok = false;
              }

              if (handlerok) {
                hnextcount++;
              }
            }
          }
        }
        else {
          handlerok = false;
        }

        if (!handlerok) {
          setHandlers.remove(stat);
        }
      }

      if (hnextcount != 1 && !setHandlers.isEmpty()) {
        List<Statement> lst = new ArrayList<>();
        lst.add(head);
        lst.addAll(setHandlers);

        for (Statement st : lst) {
          if (st.isMonitorEnter()) {
            return null;
          }
        }

        if (DecHelper.checkStatementExceptions(lst)) {
          return new CatchStatement(head, next, setHandlers);
        }
      }
    }
    return null;
  }

  @Override
  public TextBuffer toJava(int indent, BytecodeMappingTracer tracer) {
    TextBuffer buf = new TextBuffer();

    buf.append(ExprProcessor.listToJava(varDefinitions, indent, tracer));

    if (isLabeled()) {
      buf.appendIndent(indent).append("label").append(this.id.toString()).append(":").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }

    if (tryType == NORMAL) {
      buf.appendIndent(indent).append("try {").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }
    else {
      buf.appendIndent(indent).append("try (");

      if (resources.size() > 1) {
        buf.appendLineSeparator();
        tracer.incrementCurrentSourceLine();
        buf.append(ExprProcessor.listToJava(resources, indent + 1, tracer));
        buf.appendIndent(indent);
      }
      else {
        buf.append(resources.get(0).toJava(indent + 1, tracer));
      }
      buf.append(") {").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
    }

    buf.append(ExprProcessor.jmpWrapper(first, indent + 1, true, tracer));
    buf.appendIndent(indent).append("}");

    for (int i = 1; i < stats.size(); i++) {
      Statement stat = stats.get(i);
      // map first instruction storing the exception to the catch statement
      BasicBlock block = stat.getBasichead().getBlock();
      if (!block.getSeq().isEmpty() && block.getInstruction(0).opcode == CodeConstants.opc_astore) {
        Integer offset = block.getOldOffset(0);
        if (offset > -1) tracer.addMapping(offset);
      }

      buf.append(" catch (");

      List<String> exception_types = exctstrings.get(i - 1);
      if (exception_types.size() > 1) { // multi-catch, Java 7 style
        for (int exc_index = 1; exc_index < exception_types.size(); ++exc_index) {
          VarType exc_type = new VarType(CodeConstants.TYPE_OBJECT, 0, exception_types.get(exc_index));
          String exc_type_name = ExprProcessor.getCastTypeName(exc_type);

          buf.append(exc_type_name).append(" | ");
        }
      }
      buf.append(vars.get(i - 1).toJava(indent, tracer));
      buf.append(") {").appendLineSeparator();
      tracer.incrementCurrentSourceLine();
      buf.append(ExprProcessor.jmpWrapper(stat, indent + 1, false, tracer)).appendIndent(indent)
        .append("}");
    }
    buf.appendLineSeparator();

    tracer.incrementCurrentSourceLine();
    return buf;
  }

  public List<Object> getSequentialObjects() {

    List<Object> lst = new ArrayList<>(resources);
    lst.addAll(stats);
    lst.addAll(vars);

    return lst;
  }

  @Override
  public Statement getSimpleCopy() {
    CatchStatement cs = new CatchStatement();

    for (List<String> exc : this.exctstrings) {
      cs.exctstrings.add(new ArrayList<>(exc));
      cs.vars.add(new VarExprent(DecompilerContext.getCounterContainer().getCounterAndIncrement(CounterContainer.VAR_COUNTER),
                                 new VarType(CodeConstants.TYPE_OBJECT, 0, exc.get(0)),
                                 DecompilerContext.getVarProcessor()));
    }

    return cs;
  }

  public void getOffset(BitSet values) {
    super.getOffset(values);

    for (Exprent exp : this.getResources()) {
      exp.getBytecodeRange(values);
    }
  }

  // *****************************************************************************
  // getter and setter methods
  // *****************************************************************************

  public List<List<String>> getExctStrings() {
    return exctstrings;
  }

  public List<VarExprent> getVars() {
    return vars;
  }

  public int getTryType() {
    return tryType;
  }

  public void setTryType(int tryType) {
    this.tryType = tryType;
  }

  public List<Exprent> getResources() {
    return resources;
  }
}