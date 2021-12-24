package org.jetbrains.java.decompiler.modules.decompiler.deobfuscator.generics;

import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.consts.LinkConstant;

/**
 * An object that represents a reference to a field.
 */
public final class FieldReference {

  private final String desc;
  private final String name;
  private final String owner;

  public FieldReference(ClassNode owner, StructField field) {
    this(owner.classStruct.qualifiedName, field);
  }

  public FieldReference(LinkConstant linkConstant) {
    this(linkConstant.classname, linkConstant.elementname, linkConstant.descriptor);
  }

  public FieldReference(String owner, String name, String desc) {
    this.owner = owner;
    this.name = name;
    this.desc = desc;
  }

  public FieldReference(String owner, StructField field) {
    this(owner, field.getName(), field.getDescriptor());
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof FieldReference)) {
      return false;
    }
    FieldReference other = (FieldReference) obj;
    return other.name.equals(this.name) && other.desc.equals(this.desc) && other.owner.equals(this.owner);
  }

  /**
   * Obtains the field descriptor of the referred field
   *
   * @return The field descriptor
   */
  public String getDesc() {
    return this.desc;
  }

  public String getName() {
    return this.name;
  }

  /**
   * Obtains the simple internal name of the owner. This means it will be like
   * this: "org/example/ClassName"
   *
   * @return The simple internal name of the owner
   */
  public String getOwner() {
    return this.owner;
  }

  @Override
  public int hashCode() {
    return (this.owner.hashCode() & 0xFFFF0000 | this.name.hashCode() & 0x0000FFFF) ^ this.desc.hashCode();
  }

  @Override
  public String toString() {
    return "FieldReference[owner=\"" + this.owner + "\", name=\"" + this.name + "\", desc=\"" + this.desc + "\"";
  }
}
