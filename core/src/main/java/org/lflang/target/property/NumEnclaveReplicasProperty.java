package org.lflang.target.property;

import org.lflang.MessageReporter;
import org.lflang.ast.ASTUtils;
import org.lflang.lf.Element;
import org.lflang.lf.LfPackage.Literals;
import org.lflang.target.TargetConfig;
import org.lflang.target.property.type.PrimitiveType;

/**
 * The number of pipeline replicas to generate. The default is zero, which indicates no
 * additional replicas are generated.
 */
public final class NumEnclaveReplicasProperty extends TargetProperty<Integer, PrimitiveType> {

  /** Singleton target property instance. */
  public static final NumEnclaveReplicasProperty INSTANCE = new NumEnclaveReplicasProperty();

  private NumEnclaveReplicasProperty() {
    super(PrimitiveType.NON_NEGATIVE_INTEGER);
  }

  @Override
  public Integer initialValue() {
    return 0;
  }

  @Override
  protected Integer fromString(String string, MessageReporter reporter) {
    return Integer.parseInt(string); // FIXME: check for exception
  }

  @Override
  public void validate(TargetConfig config, MessageReporter reporter) {
    if (config.isSet(this)
        && config.isSet(GenerateEnclavesProperty.INSTANCE)
        && config.get(GenerateEnclavesProperty.INSTANCE).equals(false)) {
      reporter
          .at(config.lookup(this), Literals.KEY_VALUE_PAIR__NAME)
          .error("Cannot specify numEnclaveReplicas if GenerateEnclaves is not set.");
    }
  }

  @Override
  protected Integer fromAst(Element node, MessageReporter reporter) {
    return ASTUtils.toInteger(node);
  }

  @Override
  public Element toAstElement(Integer value) {
    return ASTUtils.toElement(value);
  }

  @Override
  public String name() {
    return "numEnclaveReplicas";
  }
}
