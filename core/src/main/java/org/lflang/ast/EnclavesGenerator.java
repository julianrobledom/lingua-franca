package org.lflang.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

import org.eclipse.xtext.nodemodel.INode;
import java.util.stream.Collectors;
import org.eclipse.xtext.xbase.lib.IteratorExtensions;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.lflang.lf.Attribute;
import org.lflang.lf.AttrParm;
import org.lflang.lf.Assignment;
import org.lflang.lf.Connection;
import org.lflang.lf.Expression;
import org.lflang.lf.Initializer;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.Model;
import org.lflang.lf.Output;
import org.lflang.lf.Parameter;
import org.lflang.lf.ParameterReference;
import org.lflang.lf.Port;
import org.lflang.lf.Reactor;
import org.lflang.lf.VarRef;
import org.lflang.lf.WidthTerm;
import org.lflang.lf.LfFactory;

import com.google.common.collect.Iterators;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;


public class EnclavesGenerator {

  private static LfFactory factory = LfFactory.eINSTANCE;
  private ToLf lf = new ToLf();
  private GraphPartitioning gp = new GraphPartitioning();
  private int enclaveCounter = 0;

  // Useful maps for swithching between reactor classes and their corresponding instantiations
  private Map<Integer, Instantiation> mapReactorIndexToReactorInst = new HashMap<Integer, Instantiation>();

  /* Generate a LF file for each generated enclave partitioning */
  public void generateAll(Model object) {
    int[][] adjMat = generateAdjacencyMatrix(object);
    List<List<List<Integer>>> enclaves = gp.findPartitions(adjMat);

    for (int i=0; i < enclaves.size(); i++){
        generateLF(object, enclaves.get(i), i);
    }
    return;
  }

  public Reactor findMainReactor(Model object){
    return IteratorExtensions.findFirst(
      Iterators.filter(object.getReactors().iterator(), Reactor.class),
      Reactor::isMain);
  }


  /* Generate adjacency matrix for a given graph */
  public int[][] generateAdjacencyMatrix(Model object) {
    Reactor mainReactor = findMainReactor(object);
      
    // initialize adjacent matrix
    int adjMatSize = mainReactor.getInstantiations().size();
    int[][] adjMat = new int[adjMatSize][adjMatSize];

    for(Connection connection: mainReactor.getConnections()){
      List<Integer> srcIndex = new ArrayList<>();
      List<Integer> dstIndex = new ArrayList<>();

      int src = mainReactor.getInstantiations().indexOf(connection.getLeftPorts().get(0).getContainer());
      int dst = mainReactor.getInstantiations().indexOf(connection.getRightPorts().get(0).getContainer());
      adjMat[src][dst] = 1;
    }

    return adjMat;
  }


  /* Generate output file for a given partitioning */
  public MalleableString generateLF(
    Model model, 
    List<List<Integer>> partitioning,
    Integer programCounter
  ) {
      // create deep copy of the model
      Model object = EcoreUtil.copy(model);
      enclaveCounter = 0;

      // this is useful because as we switch instantiations from main class to a given enclave
      // the index of instantiations change
      for (int i=0; i< findMainReactor(object).getInstantiations().size(); i++){
        mapReactorIndexToReactorInst.put(i, findMainReactor(object).getInstantiations().get(i));
      }

      List<Reactor> enclaveClasses = new ArrayList<Reactor>();
      List<Instantiation> enclaveInstances = new ArrayList<Instantiation>();
      for (List<Integer> enclave : partitioning){
        Instantiation enclaveInst = createEnclaveReactor(object);
        Reactor enclaveClass = (Reactor) enclaveInst.getReactorClass();
        List<Instantiation> reactorInstList = new ArrayList<Instantiation>();
        for (int reactorIndex: enclave) {
          reactorInstList.add(mapReactorIndexToReactorInst.get(reactorIndex));
        }
        addReactorsToEnclave(enclaveClass, enclaveInst, reactorInstList, object);
        addConnectionsInsideEnclave(enclaveClass, enclaveInst, reactorInstList, object);

        enclaveClasses.add(enclaveClass);
        enclaveInstances.add(enclaveInst);
      }
      addConnectionsBetweenEnclave(enclaveClasses, enclaveInstances, object);

      try{
        BufferedWriter writer = new BufferedWriter(new FileWriter("enclaves/enclaves_lf_" + programCounter + ".py"));
        writer.append(lf.caseModel(object).toString());
        writer.close();
      }
      catch (IOException e) {
        System.err.println("Error: " + e.getMessage());
      }

      return lf.caseModel(object);
  }

  public Instantiation createEnclaveReactor(Model object){
    // create Reactor class and instantiation for enclave
    Reactor enclaveClass = factory.createReactor();
    enclaveClass.setName("enclave" + enclaveCounter);
    Instantiation enclaveInst = factory.createInstantiation();
    enclaveInst.setReactorClass(enclaveClass);
    enclaveInst.setName("encl" + enclaveCounter);

    // add enclave attribute
    Attribute attribute = factory.createAttribute();
    attribute.setAttrName("enclave");
    AttrParm attrParm = factory.createAttrParm();
    attrParm.setName("each");
    attrParm.setValue("false");
    attribute.getAttrParms().add(attrParm);
    enclaveInst.getAttributes().add(attribute);

    // Add new enclave reactor to the model
    Reactor mainReactor = findMainReactor(object);
    mainReactor.getInstantiations().add(enclaveInst);
    object.getReactors().add(enclaveClass);

    // keep count of created enclaves
    enclaveCounter++;

    return enclaveInst;
  }


  public void addReactorsToEnclave(Reactor enclaveClass, Instantiation enclaveInst, List<Instantiation> reactorInst, Model object) {

    List<Parameter> parameters = new ArrayList<Parameter>();
    List<Assignment> assignments = new ArrayList<Assignment>();
    List<ParameterReference> parRefs = new ArrayList<ParameterReference>();
    List<Parameter> parRight = new ArrayList<Parameter>();

    for(int i=0; i < reactorInst.size(); i++){
      List<Parameter> paramsClass = ((Reactor) reactorInst.get(i).getReactorClass()).getParameters();
      List<Assignment> assignmentList = reactorInst.get(i).getParameters();
      List<Parameter> paramsLeft = reactorInst.get(i).getParameters().stream().map(Assignment::getLhs).collect(Collectors.toList());
      List<Initializer> paramsRight = reactorInst.get(i).getParameters().stream().map(Assignment::getRhs).collect(Collectors.toList());
      List<Expression> exps = paramsRight.stream().map(Initializer::getExpr).collect(Collectors.toList());

      for(int j=0; j < exps.size(); j++){
        if(exps.get(j) instanceof ParameterReference){
          ParameterReference pr = (ParameterReference) exps.get(j);
          if(!parRefs.contains(pr)){
            assignments.add(assignmentList.get(j));
            parRefs.add(pr);
            parRight.add(pr.getParameter());
            parameters.add(paramsLeft.get(j));
          }

          Parameter newParam = EcoreUtil.copy(paramsLeft.get(j));
          Assignment newAssignment = factory.createAssignment();
          Initializer newInit = factory.createInitializer();
          ParameterReference newPr = factory.createParameterReference();
          newPr.setParameter(paramsLeft.get(j));
          newInit.setAssign(true);
          newInit.setExpr((Expression) newPr);
          newAssignment.setLhs(newParam);
          newAssignment.setRhs(newInit);

          ((Reactor) reactorInst.get(i).getReactorClass()).getParameters().add(newParam);
          reactorInst.get(i).getParameters().add(newAssignment);
        }
      }
    }

    for(int i=0; i < reactorInst.size(); i++){
      if(reactorInst.get(i).getWidthSpec() != null){
        List<Parameter> paramsWidth = reactorInst.get(i).getWidthSpec().getTerms().stream()
          .map(WidthTerm::getParameter).collect(Collectors.toList());
        Parameter p = paramsWidth.get(0);
        if(!parRight.contains(p)){

          Parameter newParam = EcoreUtil.copy(p);
          Assignment assignment = factory.createAssignment();
          Initializer initializer = factory.createInitializer();
          ParameterReference paramRef = factory.createParameterReference();
          paramRef.setParameter(p);
          initializer.setAssign(true);
          initializer.setExpr((Expression) paramRef);
          assignment.setLhs(newParam);
          assignment.setRhs(initializer);

          assignments.add(assignment);
          parameters.add(newParam);
        }
        else{
          int index = parRight.indexOf(p);
          reactorInst.get(i).getWidthSpec().getTerms().get(0).setParameter(parameters.get(index));
        }
      }
    }

    for(int i=0; i < parameters.size(); i++){
      enclaveClass.getParameters().add(parameters.get(i));
      enclaveInst.getParameters().add(assignments.get(i));
    }

    for(Instantiation inst: reactorInst){
      enclaveClass.getInstantiations().add(inst);
    }
  }


  public void addConnectionsInsideEnclave(Reactor enclaveClass, Instantiation enclaveInst, List<Instantiation> reactorInst, Model object){
    // Add connections between inner reactors
    Reactor mainReactor = findMainReactor(object);
    List<Input> connectedInputs = new ArrayList<Input>();
    List<Output> connectedOutputs = new ArrayList<Output>();

    for (int i=0; i< mainReactor.getConnections().size(); i++) {
      Connection connection = mainReactor.getConnections().get(i);
      VarRef left = connection.getLeftPorts().get(0);
      VarRef right = connection.getRightPorts().get(0);
      if(reactorInst.contains(right.getContainer()) && reactorInst.contains(left.getContainer())){
        enclaveClass.getConnections().add(connection);
        connectedOutputs.add((Output) left.getVariable());
        connectedInputs.add((Input) right.getVariable());
      }
    }

    // create new ports and connections for enclave
    for(Instantiation inst: reactorInst){
      Reactor reactor = (Reactor) inst.getReactorClass();
      for(Input input: reactor.getInputs()){
        if(!connectedInputs.contains(input)){
          Input newInput = EcoreUtil.copy(input);
          if(inst.getWidthSpec() != null){
            newInput.setWidthSpec(inst.getWidthSpec());
          }
          enclaveClass.getInputs().add(newInput);

          // create connection between enclave's ports and reactor
          Connection newConnection = factory.createConnection();
          VarRef left = factory.createVarRef();
          left.setVariable(newInput);
          newConnection.getLeftPorts().add(left);

          VarRef right = factory.createVarRef();
          right.setContainer(inst);
          right.setVariable(input);
          newConnection.getRightPorts().add(right);

          enclaveClass.getConnections().add(newConnection);
        }
      }
      for(Output output: reactor.getOutputs()){
        if(!connectedOutputs.contains(output)){
          Output newOutput = EcoreUtil.copy(output);
          if(inst.getWidthSpec() != null){
            newOutput.setWidthSpec(inst.getWidthSpec());
          }
          enclaveClass.getOutputs().add(newOutput);

          // create connection between enclave's ports and reactor
          Connection newConnection = factory.createConnection();
          VarRef left = factory.createVarRef();
          left.setContainer(inst);
          left.setVariable(output);
          newConnection.getLeftPorts().add(left);

          VarRef right = factory.createVarRef();
          right.setVariable(newOutput);
          newConnection.getRightPorts().add(right);

          enclaveClass.getConnections().add(newConnection);
        }
      }
    }
  }


  public void addConnectionsBetweenEnclave(List<Reactor> enclaveClasses, List<Instantiation> enclaveInstances, Model object) {
    // Add connections between enclaves
    Reactor mainReactor = findMainReactor(object);
    List<Connection> newConnections = new ArrayList<Connection>();
    List<Connection> connectionsRemove = new ArrayList<Connection>();

    for(int i=0; i<mainReactor.getConnections().size(); i++){
      Connection newConnection = factory.createConnection();
      Reactor leftEnclaveClass;
      VarRef left= mainReactor.getConnections().get(i).getLeftPorts().get(0);
      for(Instantiation enclaveInst: enclaveInstances){
        if(((Reactor) enclaveInst.getReactorClass()).getInstantiations().contains(left.getContainer())){
          leftEnclaveClass = (Reactor) enclaveInst.getReactorClass();

          for(Output output: leftEnclaveClass.getOutputs()){
            if(output.getName() == left.getVariable().getName()){
              VarRef newLeft = factory.createVarRef();
              newLeft.setContainer(enclaveInst);
              newLeft.setVariable(output);
              newConnection.getLeftPorts().add(newLeft);
            }
          }
        }
      }

      Reactor rightEnclaveClass;
      VarRef right= mainReactor.getConnections().get(i).getRightPorts().get(0);
      for(Instantiation enclaveInst: enclaveInstances){
        if(((Reactor) enclaveInst.getReactorClass()).getInstantiations().contains(right.getContainer())){
          rightEnclaveClass = (Reactor) enclaveInst.getReactorClass();

          for(Input input: rightEnclaveClass.getInputs()){
            if(input.getName() == right.getVariable().getName()){
              VarRef newRight = factory.createVarRef();
              newRight.setContainer(enclaveInst);
              newRight.setVariable(input);
              newConnection.getRightPorts().add(newRight);
            }
          }
        }
      }
      newConnections.add(newConnection);
      connectionsRemove.add(mainReactor.getConnections().get(i));
    }
    mainReactor.getConnections().addAll(newConnections);
    mainReactor.getConnections().removeAll(connectionsRemove);
  }
}







