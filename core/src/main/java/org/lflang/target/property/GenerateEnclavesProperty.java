package org.lflang.target.property;


/** Generate enclave versions */
public final class GenerateEnclavesProperty extends BooleanProperty {

  /** Singleton target property instance. */
  public static final GenerateEnclavesProperty INSTANCE = new GenerateEnclavesProperty();

  private GenerateEnclavesProperty() {
    super();
  }

  @Override
  public String name() {
    return "generate-enclaves";
  }
}