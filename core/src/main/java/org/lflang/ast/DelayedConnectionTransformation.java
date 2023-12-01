package org.lflang.ast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.eclipse.xtext.xbase.lib.IteratorExtensions;
import org.lflang.InferredType;
import org.lflang.generator.DelayBodyGenerator;
import org.lflang.generator.TargetTypes;
import org.lflang.lf.Action;
import org.lflang.lf.ActionOrigin;
import org.lflang.lf.Assignment;
import org.lflang.lf.Code;
import org.lflang.lf.Connection;
import org.lflang.lf.Initializer;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.LfFactory;
import org.lflang.lf.Mode;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Parameter;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.Time;
import org.lflang.lf.Type;
import org.lflang.lf.TypeParm;
import org.lflang.lf.VarRef;
import org.lflang.lf.WidthSpec;
import org.lflang.lf.WidthTerm;
import org.lflang.util.Pair;

/**
 * This class implements AST transformations for delayed connections. There are two types of delayed
 * connections: 1) Connections with {@code after}-delays 2) Physical connections
 */
public class DelayedConnectionTransformation implements AstTransformation {

  /** The Lingua Franca factory for creating new AST nodes. */
  public static final LfFactory factory = ASTUtils.factory;

  /** A code generator used to insert reaction bodies for the generated delay reactors. */
  private final DelayBodyGenerator generator;

  /**
   * A target type instance that is used during the transformation to manage target specific types
   */
  private final TargetTypes targetTypes;

  /** The Eclipse eCore view of the main LF file. */
  private final Resource mainResource;

  private boolean transformAfterDelays = false;
  private boolean transformPhysicalConnection = false;
  /** Collection of generated delay classes. */
  private final LinkedHashSet<Reactor> delayClasses = new LinkedHashSet<>();

  public DelayedConnectionTransformation(
      DelayBodyGenerator generator,
      TargetTypes targetTypes,
      Resource mainResource,
      boolean transformAfterDelays,
      boolean transformPhysicalConnections) {
    this.generator = generator;
    this.targetTypes = targetTypes;
    this.mainResource = mainResource;
    this.transformAfterDelays = transformAfterDelays;
    this.transformPhysicalConnection = transformPhysicalConnections;
  }

  /** Transform all after delay connections by inserting generated delay reactors. */
  @Override
  public void applyTransformation(List<Reactor> reactors) {
    insertGeneratedDelays(reactors);
  }

  /**
   * Find connections in the given resource that have a delay associated with them, and reroute them
   * via a generated delay reactor.
   *
   * @param reactors A list of reactors to apply the transformation to.
   */
  private void insertGeneratedDelays(List<Reactor> reactors) {
    List<Pair<Connection, Instantiation>> toReroute = new ArrayList<>();

    // Iterate over the connections in the tree.
    for (Reactor container : reactors) {
      for (Connection connection : ASTUtils.allConnections(container)) {
        if (transformAfterDelays && connection.getDelay() != null
            || transformPhysicalConnection && connection.isPhysical()) {
          EObject parent = connection.eContainer();
          // Assume all the types are the same, so just use the first on the right.
          Type type = ((Port) connection.getRightPorts().get(0).getVariable()).getType();
          var resource =
              connection.getLeftPorts().size() > 0
                      && connection.getLeftPorts().get(0).getContainer() != null
                  ? connection.getLeftPorts().get(0).getContainer().getReactorClass().eResource()
                  : mainResource;
          Reactor delayClass = getDelayClass(resource, type, connection.isPhysical());
          String generic = targetTypes.supportsGenerics() ? targetTypes.getTargetType(type) : null;

          Instantiation delayInstance =
              getDelayInstance(
                  delayClass,
                  connection,
                  generic,
                  !generator.generateAfterDelaysWithVariableWidth(),
                  connection.isPhysical());
          // Give it an unique name.
          if (parent instanceof Reactor) {
            delayInstance.setName(ASTUtils.getUniqueIdentifier((Reactor) parent, "delay"));
          } else if (parent instanceof Mode) {
            delayInstance.setName(
                ASTUtils.getUniqueIdentifier((Reactor) parent.eContainer(), "delay"));
          }

          toReroute.add(new Pair<>(connection, delayInstance));
        }
      }
    }
    ASTUtils.rerouteViaInstance(toReroute);
  }

  /**
   * Create a new instance delay instances using the given reactor class. The supplied time value is
   * used to override the default interval (which is zero). If the target supports parametric
   * polymorphism, then a single class may be used for each instantiation, in which case a non-empty
   * string must be supplied to parameterize the instance. A default name ("delay") is assigned to
   * the instantiation, but this name must be overridden at the call site, where checks can be done
   * to avoid name collisions in the container in which the instantiation is to be placed. Such
   * checks (or modifications of the AST) are not performed in this method in order to avoid causing
   * concurrent modification exceptions.
   *
   * @param delayClass The class to create an instantiation for
   * @param connection The connection to create a delay instantiation foe
   * @param genericArg A string that denotes the appropriate type parameter, which should be null or
   *     empty if the target does not support generics.
   * @param defineWidthFromConnection If this is true and if the connection is a wide connection,
   *     then instantiate a bank of delays where the width is given by ports involved in the
   *     connection. Otherwise, the width will be unspecified indicating a variable length.
   * @param isPhysical Is this a delay instance using a physical action. These are used for
   *     implementing Physical Connections. If true we will accept zero delay on the connection.
   */
  private static Instantiation getDelayInstance(
      Reactor delayClass,
      Connection connection,
      String genericArg,
      Boolean defineWidthFromConnection,
      Boolean isPhysical) {
    Instantiation delayInstance = factory.createInstantiation();
    delayInstance.setReactorClass(delayClass);
    if (genericArg != null) {
      Code code = factory.createCode();
      code.setBody(genericArg);
      Type type = factory.createType();
      type.setCode(code);
      delayInstance.getTypeArgs().add(type);
    }
    if (ASTUtils.hasMultipleConnections(connection)) {
      WidthSpec widthSpec = factory.createWidthSpec();
      if (defineWidthFromConnection) {
        // Add all left ports of the connection to the WidthSpec of the generated delay instance.
        // This allows the code generator to later infer the width from the involved ports.
        // We only consider the left ports here, as they could be part of a broadcast. In this case,
        // we want
        // to delay the ports first, and then broadcast the output of the delays.
        for (VarRef port : connection.getLeftPorts()) {
          WidthTerm term = factory.createWidthTerm();
          term.setPort(EcoreUtil.copy(port));
          widthSpec.getTerms().add(term);
        }
      } else {
        widthSpec.setOfVariableLength(true);
      }
      delayInstance.setWidthSpec(widthSpec);
    }
    // Allow physical connections with no after delay
    //  they will use the default min_delay of 0.
    if (!isPhysical || connection.getDelay() != null) {
      Assignment assignment = factory.createAssignment();
      assignment.setLhs(delayClass.getParameters().get(0));
      Initializer init = factory.createInitializer();
      init.getExprs().add(Objects.requireNonNull(connection.getDelay(), "null delay"));
      assignment.setRhs(init);
      delayInstance.getParameters().add(assignment);
    }

    delayInstance.setName("delay"); // This has to be overridden.
    return delayInstance;
  }

  /**
   * Return a synthesized AST node that represents the definition of a delay reactor. Depending on
   * whether the target supports generics, either this method will synthesize a generic definition
   * and keep returning it upon subsequent calls, or otherwise, it will synthesize a new definition
   * for each new type it hasn't yet created a compatible delay reactor for.
   *
   * @param type The type the delay class must be compatible with.
   * @param isPhysical Is this delay reactor using a physical action.
   */
  private Reactor getDelayClass(Resource resource, Type type, boolean isPhysical) {
    String className;
    if (targetTypes.supportsGenerics()) {
      className = DelayBodyGenerator.GEN_DELAY_CLASS_NAME;
    } else {
      String id = Integer.toHexString(InferredType.fromAST(type).toText().hashCode());
      className = String.format("%s_%s", DelayBodyGenerator.GEN_DELAY_CLASS_NAME, id);
    }

    // Only add class definition if it is not already there.
    Reactor classDef = findDelayClass(className);
    if (classDef != null) {
      return classDef;
    }

    Reactor delayClass = factory.createReactor();
    Parameter delayParameter = factory.createParameter();
    Action action = factory.createAction();
    VarRef triggerRef = factory.createVarRef();
    VarRef effectRef = factory.createVarRef();
    Input input = factory.createInput();
    Output output = factory.createOutput();
    VarRef inRef = factory.createVarRef();
    VarRef outRef = factory.createVarRef();

    Reaction r1 = factory.createReaction();
    Reaction r2 = factory.createReaction();

    delayParameter.setName("delay");
    delayParameter.setType(factory.createType());
    delayParameter.getType().setId("time");
    delayParameter.getType().setTime(true);
    Time defaultTime = factory.createTime();
    defaultTime.setUnit(null);
    defaultTime.setInterval(0);
    Initializer init = factory.createInitializer();
    init.setParens(true);
    init.setBraces(false);
    init.getExprs().add(defaultTime);
    delayParameter.setInit(init);

    // Name the newly created action; set its delay and type.
    action.setName("act");
    var paramRef = factory.createParameterReference();
    paramRef.setParameter(delayParameter);
    action.setMinDelay(paramRef);
    if (isPhysical) {
      action.setOrigin(ActionOrigin.PHYSICAL);
    } else {
      action.setOrigin(ActionOrigin.LOGICAL);
    }

    if (targetTypes.supportsGenerics()) {
      action.setType(factory.createType());
      action.getType().setId("T");
    } else {
      action.setType(EcoreUtil.copy(type));
    }

    input.setName("inp");
    input.setType(EcoreUtil.copy(action.getType()));

    output.setName("out");
    output.setType(EcoreUtil.copy(action.getType()));

    // Establish references to the involved ports.
    inRef.setVariable(input);
    outRef.setVariable(output);

    // Establish references to the action.
    triggerRef.setVariable(action);
    effectRef.setVariable(action);

    // Add the action to the reactor.
    delayClass.setName(className);
    delayClass.getActions().add(action);

    // Configure the second reaction, which reads the input.
    r1.getTriggers().add(inRef);
    r1.getEffects().add(effectRef);
    r1.setCode(factory.createCode());
    r1.getCode().setBody(generator.generateDelayBody(action, inRef));

    // Configure the first reaction, which produces the output.
    r2.getTriggers().add(triggerRef);
    r2.getEffects().add(outRef);
    r2.setCode(factory.createCode());
    r2.getCode().setBody(generator.generateForwardBody(action, outRef));

    generator.finalizeReactions(r1, r2);

    // Add the reactions to the newly created reactor class.
    // These need to go in the opposite order in case
    // a new input arrives at the same time the delayed
    // output is delivered!
    delayClass.getReactions().add(r2);
    delayClass.getReactions().add(r1);

    // Add a type parameter if the target supports it.
    if (targetTypes.supportsGenerics()) {
      TypeParm parm = factory.createTypeParm();
      parm.setLiteral(generator.generateDelayGeneric());
      delayClass.getTypeParms().add(parm);
    }
    delayClass.getInputs().add(input);
    delayClass.getOutputs().add(output);
    delayClass.getParameters().add(delayParameter);
    addDelayClass(resource, delayClass);
    return delayClass;
  }

  /**
   * Store the given reactor in the collection of generated delay classes and insert it in the AST
   * under the top-level reactor's node.
   */
  private void addDelayClass(Resource resource, Reactor generatedDelay) {
    // Record this class, so it can be reused.
    delayClasses.add(generatedDelay);
    // And hook it into the AST.
    EObject node = IteratorExtensions.findFirst(resource.getAllContents(), Model.class::isInstance);
    ((Model) node).getReactors().add(generatedDelay);
  }

  /**
   * Return the generated delay reactor that corresponds to the given class name if it had been
   * created already, {@code null} otherwise.
   */
  private Reactor findDelayClass(String className) {
    return IterableExtensions.findFirst(delayClasses, it -> it.getName().equals(className));
  }
}
