package org.jetbrains.java.decompiler.modules.decompiler.deobfuscator.generics;

import java.util.Objects;

public final class SimpleSignatureBuilder {

  private String generic;
  private final String type;

  public SimpleSignatureBuilder(String type, String generics) {
      this.generic = generics;
      if (type.endsWith(";")) {
          type = type.substring(0, type.length() - 1);
      }
      this.type = type;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SimpleSignatureBuilder)) {
      return false;
    }
    SimpleSignatureBuilder other = (SimpleSignatureBuilder) obj;
    return other.type.equals(type)
        && ((this.generic == null && other.generic == null) || (this.generic != null && this.generic.equals(other.generic)));
  }

  public String getGenerics() {
    return generic;
  }

  @Override
  public int hashCode() {
    return Objects.hash(generic, type);
  }

  public void setGenerics(SimpleSignatureBuilder node) {
    if (node == null) {
      this.generic = null;
    } else {
      this.generic = node.toString();
    }
  }

  public void setGenerics(String generics) {
    this.generic = generics;
  }

  @Override
  public String toString() {
    if (generic == null) {
      return type + ';';
    }
    return type + '<' + generic + ">;";
  }
}
