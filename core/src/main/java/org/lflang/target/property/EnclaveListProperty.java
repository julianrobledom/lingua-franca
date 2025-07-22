package org.lflang.target.property;

import java.util.ArrayList;
import java.util.List;
import org.lflang.MessageReporter;
import org.lflang.ast.ASTUtils;
import org.lflang.lf.Element;
import org.lflang.target.TargetConfig;
import org.lflang.target.property.type.PrimitiveType;
//import org.lflang.target.property.type.UnionType;

public final class EnclaveListProperty extends StringProperty {

  public static final EnclaveListProperty INSTANCE = new EnclaveListProperty();

  private EnclaveListProperty() {
    super();
  }

  @Override
  public String name() {
    return "enclave-list";
  }

  @Override
  public String initialValue() {
    return new String();
  }

  //@Override
  public List<String> toList(String enclaves) {
    // Regex: starts with {, then one or more groups of [item(,item)*], separated by commas, ends with }
    String pattern = "\\{(\\[(\\s*\\w+\\s*(,\\s*\\w+\\s*)*)?\\],?)+\\}";
    if (enclaves == null || !enclaves.matches(pattern)) {
        throw new IllegalArgumentException(
            "Input must have the form {[reactor1, reactor2,...],[reactor3, reactor4,...],...}"
        );
    }
    // Remove the outer braces
    String trimmed = enclaves.substring(1, enclaves.length() - 1);
    List<String> enclaveList = new ArrayList<>();
    // Split by "],[" and remove brackets
    for (String group : trimmed.split("\\],\\[")) {
        String clean = group.replace("[", "").replace("]", "").trim();
        if (!clean.isEmpty()) {
            enclaveList.add(clean);
            System.out.println("Enclave: " + clean);
        }
    }

    return enclaveList;
  }

  /*@Override
  public void update(TargetConfig config, List<String> value) {
    var files = new ArrayList<>(value);
    var existing = config.get(this);
    if (config.isSet(this)) {
      existing.forEach(
          f -> {
            if (!files.contains(f)) {
              files.add(f);
            }
          });
    }
    config.set(this, files.stream().sorted(String::compareTo).toList());
  }*/

  /*@Override
  protected String fromAst(Element node, MessageReporter reporter) {
    //return ASTUtils.elementToListOfStrings(node);
    return ASTUtils.toString(node);
  }*/

  /*@Override
  protected String fromString(String string, MessageReporter reporter) {
    throw new UnsupportedOperationException("Not supported yet.");
  }*/

  @Override
  public Element toAstElement(String value) {
    return ASTUtils.toElement(value);
  }

  @Override
  public boolean loadFromImport() {
    return true;
  }
}
