package org.jetbrains.java.decompiler.modules.decompiler.deobfuscator.generics;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.code.Instruction;
import org.jetbrains.java.decompiler.code.InstructionSequence;
import org.jetbrains.java.decompiler.code.JumpInstruction;
import org.jetbrains.java.decompiler.main.ClassesProcessor;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger.Severity;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.consts.ConstantPool;
import org.jetbrains.java.decompiler.struct.consts.LinkConstant;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericMain;

public class GenericsGuesser implements CodeConstants {

  /**
   * A hardcoded set of implementations of the {@link Iterable} interface that
   * apply for generics checking later on. As this set is hardcoded, using it
   * directly is not really recommended as changing the implementation might be
   * beneficial in the future. Use {@link #isIterable(String)} instead.
   */
  private static final Set<String> ITERABLES = new HashSet<String>() {
    private static final long serialVersionUID = -3779578266088390365L;

    {
      add("Ljava/util/Vector;");
      add("Ljava/util/List;");
      add("Ljava/util/ArrayList;");
      add("Ljava/util/Collection;");
      add("Ljava/util/AbstractCollection;");
      add("Ljava/util/AbstractList;");
      add("Ljava/util/AbstractSet;");
      add("Ljava/util/AbstractQueue;");
      add("Ljava/util/HashSet;");
      add("Ljava/util/Set;");
      add("Ljava/util/Queue;");
      add("Ljava/util/concurrent/ArrayBlockingQueue;");
      add("Ljava/util/concurrent/ConcurrentLinkedQueue;");
      add("Ljava/util/concurrent/ConcurrentLinkedQueue;");
      add("Ljava/util/concurrent/DelayQueue;");
      add("Ljava/util/concurrent/LinkedBlockingQueue;");
      add("Ljava/util/concurrent/SynchronousQueue;");
      add("Ljava/util/concurrent/BlockingQueue;");
      add("Ljava/util/concurrent/BlockingDeque;");
      add("Ljava/util/concurrent/LinkedBlockingDeque;");
      add("Ljava/util/concurrent/ConcurrentLinkedDeque;");
      add("Ljava/util/Deque;");
      add("Ljava/util/ArrayDeque;");
      add("Ljava/lang/Iterable;");
    }
  };

  /**
   * Checks if a given descriptor is an iterable.
   * This descriptor has to be in the format "L.+;" in order to match,
   * however this method should accept any string not obeying the format (e.g. "F"),
   * though it strictly needs to return false in this case.
   *
   * By default this method uses a hardcoded set of implementations of the Iterable interface
   * that are present in Java 11. This includes interfaces and the Iterable interface itself.
   *
   * @param descriptor The descriptor to check
   * @return Whether the descriptor is an iterable
   */
  public boolean isIterable(String descriptor) {
    return ITERABLES.contains(descriptor);
  }

  public void guessGenerics(ClassesProcessor classProcessor) {

    Set<ClassNode> nodes = new HashSet<>();

    for (ClassNode node : classProcessor.getMapRootClasses().values()) {
      nodes.add(node);
      nodes.addAll(node.getAllNested());
    }

    Map<FieldReference, SimpleSignatureBuilder> newFieldSignatures = new HashMap<>();

    // index fields without signatures
    for (ClassNode node : nodes) {
      for (StructField field : node.classStruct.getFields()) {
        if (field.getSignature() == null && isIterable(field.getDescriptor())) {
          newFieldSignatures.put(new FieldReference(node, field), null);
        }
      }
    }

    // guess signatures based on iterators
    for (ClassNode node : nodes) {
      for (StructMethod method : node.classStruct.getMethods()) {
        if (!method.containsCode()) {
          continue; // Abstract method
        }
        // Decode the instructions, if they are not already decoded.
        boolean decodedInstructions = false;
        if (method.getInstructionSequence() == null) {
          try {
            method.expandData(node.classStruct);
          } catch (IOException e) {
            DecompilerContext.getLogger().writeMessage("Error while expanding data of method " + node.classStruct.qualifiedName + "." + method.getName() + method.getDescriptor(), Severity.ERROR, e);
            continue;
          }
          decodedInstructions = true;
        }

        InstructionSequence instructions = method.getInstructionSequence();
        if (instructions == null) {
          if (decodedInstructions) {
            method.releaseResources();
          }
          continue; // Don't ask me when this can happen, but it could
        }

        int sequenceLength = instructions.length() - 8;
        for (int i = 0; i < sequenceLength; i++) {
          // The real deal
          Instruction insn = instructions.getInstr(i);
          if (insn.opcode == opc_getfield || insn.opcode == opc_getstatic) {
            Instruction callIterator = instructions.getInstr(i + 1); // This instruction should represent Iterable#iterator
            Instruction storeIterator = instructions.getInstr(i + 2); // Afterwards Javac stores the obtained iterator
            // Pseudo-instruction: a label
            // Even more pseudo-instructions
            Instruction hasNextLoadIterator = instructions.getInstr(i + 3); // Obtain the iterator so Iterator#hasNext can be called
            Instruction hasNext = instructions.getInstr(i + 4); // Call Iterator#hasNext
            Instruction loopEndJumpInstruction = instructions.getInstr(i + 5); // If there are no further elements, then jump to the end of the loop
            Instruction nextLoadIterator = instructions.getInstr(i + 6); // Obtain the iterator so Iterator#next can be called
            Instruction getNextElement = instructions.getInstr(i + 7); // Call Iterator#next
            Instruction checkcast = instructions.getInstr(i + 8); // This is what we were digging for

            // Now we check the opcodes of all the instructions here
            if ((callIterator.opcode != opc_invokeinterface && callIterator.opcode != opc_invokespecial)
                || storeIterator.opcode != opc_astore
                || hasNextLoadIterator.opcode != opc_aload
                || hasNext.opcode != opc_invokeinterface
                || !(loopEndJumpInstruction instanceof JumpInstruction)
                || nextLoadIterator.opcode != opc_aload
                || getNextElement.opcode != opc_invokeinterface
                || checkcast.opcode != opc_checkcast) {
              continue; // At least 1 opcode that does not match our expectations, likely not a foreach loop
            }

            // All astore/aload have to use the same opcode.
            if (storeIterator.operand(0) != hasNextLoadIterator.operand(0) || storeIterator.operand(0) != nextLoadIterator.operand(0)) {
              continue;
            }

            ConstantPool constantPool = node.classStruct.getPool();

            FieldReference fieldReference = new FieldReference(constantPool.getLinkConstant(insn.operand(0)));
            if (!newFieldSignatures.containsKey(fieldReference)) {
              continue; // The signature of the field is already known, or the field itself is not known
            }

            LinkConstant callIteratorMethod = constantPool.getLinkConstant(callIterator.operand(0));
            LinkConstant hasNextMethod = constantPool.getLinkConstant(hasNext.operand(0));
            LinkConstant nextElementMethod = constantPool.getLinkConstant(getNextElement.operand(0));

            // Check whether calls to the iterator are all as expected.
            // While given the structure of the instructions it is more or less guaranteed to be as expected,
            // some code might trick the guesser in producing invalid guesses
            if (!(callIteratorMethod.descriptor.equals("()Ljava/util/Iterator;")
                && callIteratorMethod.elementname.equals("iterator")
                && hasNextMethod.classname.equals("java/util/Iterator")
                && hasNextMethod.elementname.equals("hasNext")
                && hasNextMethod.descriptor.equals("()Z")
                && nextElementMethod.classname.equals("java/util/Iterator")
                && nextElementMethod.elementname.equals("next")
                && nextElementMethod.descriptor.equals("()Ljava/lang/Object;"))) {
              continue; // At least one method call is not following the expected pattern.
            }

            String castedInternalName = constantPool.getPrimitiveConstant(checkcast.operand(0)).getString();
            String suggestedGeneric = 'L' + castedInternalName + ';';
            SimpleSignatureBuilder currentlySuggested = newFieldSignatures.get(fieldReference);

            if (currentlySuggested == null) {
              newFieldSignatures.put(fieldReference, new SimpleSignatureBuilder(fieldReference.getDesc(), suggestedGeneric));
            } else {
              if (!currentlySuggested.getGenerics().equals(suggestedGeneric)) {
                newFieldSignatures.remove(fieldReference); // Contested signature, unlikely to happen though
                continue;
              }
            }

            // optionally, we could add an LVT entry here.
            // An excerpt from one of my earlier rants that provides the reasons why doing this may be beneficial:
            // Quiltflower has a bug where it does not correctly identify LVT entries
            // and acts as if they weren't there. This precisely occurs as the decompiler
            // expects that the start label provided by of the LVT entry is equal to the first declaration of the
            // entry. While I have already brought forward a fix for this, unfortunately this results in a few other
            // (more serious) issues that result in formerly broken but technically correct and compilable code
            // being no longer compilable. This makes it unlikely that the fix would be pushed anytime soon.
            // My assumption is that this has something to do with another bug in the decompiler,
            // but in the meantime I guess that we will have to work around this bug by adding a LabelNode
            // just before the first astore operation.
          }
        }

        // Release the generated data
        if (decodedInstructions) {
          method.releaseResources();
        }
      }
    }

    // Set the guessed signatures
    for (ClassNode node : nodes) {
      for (StructField field : node.classStruct.getFields()) {
        if (field.getSignature() == null) {
          FieldReference key = new FieldReference(node, field);
          SimpleSignatureBuilder signature = newFieldSignatures.get(key);
          if (signature != null) {
            field.setSignature(GenericMain.parseFieldSignature(signature.toString()));
          }
        }
      }
    }
  }
}
