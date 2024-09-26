package org.lflang.ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.xtext.xbase.lib.IteratorExtensions;
import org.eclipse.emf.ecore.util.EcoreUtil;
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

      if(!connection.getLeftPorts().isEmpty()) {
          for(VarRef portVar: connection.getLeftPorts()){
              srcIndex.add(mainReactor.getInstantiations().indexOf(
                  portVar.getContainer()
              ));
          }
      }
      if(!connection.getRightPorts().isEmpty()) {
          for(VarRef portVar: connection.getRightPorts()){
              dstIndex.add(mainReactor.getInstantiations().indexOf(
                  portVar.getContainer()
              ));
          }
      }

      for(int src: srcIndex){
          for (int dst: dstIndex){
              adjMat[src][dst] = 1;
          }
      }
    }

    return adjMat;
  }


  /*Generate output file for a given partitioning */
  public MalleableString generateLF(
    Model model, 
    List<List<Integer>> partitioning,
    Integer programCounter
  ) {
      Model object = EcoreUtil.copy(model);
      Reactor mainReactor = findMainReactor(object);

      // Useful maps for swithching between reactor classes and their corresponding instantiations
      int counter = 0;
      List<Reactor> newReactors = new ArrayList<Reactor>();
      Map<Instantiation, Reactor> mapReactorInstToEnclave = new HashMap<Instantiation, Reactor>();
      Map<Reactor, Instantiation> mapEnclaveReactorToEnclaveInst = new HashMap<Reactor, Instantiation>();
      Map<Integer, Instantiation> mapReactorIndexToReactorInst = new HashMap<Integer, Instantiation>();

      for (int i=0; i< mainReactor.getInstantiations().size(); i++){
        mapReactorIndexToReactorInst.put(i, mainReactor.getInstantiations().get(i));
      }

      for (List<Integer> enclave : partitioning){
        // keep count of created enclaves
        counter++;

        // create Reactor class and instantiation for enclave
        Reactor enclaveClass = factory.createReactor();
        enclaveClass.setName("enclave" + counter);
        Instantiation enclaveInst = factory.createInstantiation();
        enclaveInst.setReactorClass(enclaveClass);
        enclaveInst.setName("encl" + counter);

        // Add new reactor to the model
        mainReactor.getInstantiations().add(enclaveInst);
        object.getReactors().add(enclaveClass);

        // Add new reactor to useful lists
        newReactors.add(enclaveClass);
        mapEnclaveReactorToEnclaveInst.put(enclaveClass, enclaveInst);


        for (int reactorIndex: enclave) {
          // get parameteres of reactors inside enclave and add them to the enclave instantiation
          List<Assignment> assignmentList = mapReactorIndexToReactorInst.get(reactorIndex).getParameters();
          List<Parameter> paramList = enclaveInst.getParameters().stream().map(Assignment::getLhs).collect(Collectors.toList());
          List<String> paramsNames = paramList.stream().map(Parameter::getName).collect(Collectors.toList());
          for(Assignment assignment: assignmentList){
            if(!paramsNames.contains(assignment.getLhs().getName())){
              enclaveInst.getParameters().add(EcoreUtil.copy(assignment));
            }
          }

          enclaveClass.getInstantiations().add(mapReactorIndexToReactorInst.get(reactorIndex));

          // get parameteres of reactors inside enclave and add them to the enclave class
          // 
          Reactor reactor = factory.createReactor();
          for(Reactor r: object.getReactors()){
            if(r.getName() == mapReactorIndexToReactorInst.get(reactorIndex).getReactorClass().getName()){
              reactor = r;
            }
          }
          paramsNames = enclaveClass.getParameters().stream().map(Parameter::getName).collect(Collectors.toList());
          for(Parameter parameter: reactor.getParameters()){
            if(!paramsNames.contains(parameter.getName())){
              enclaveClass.getParameters().add(EcoreUtil.copy(parameter));
            }
          }

          // create map
          mapReactorInstToEnclave.put(mapReactorIndexToReactorInst.get(reactorIndex), enclaveClass);
        }
      }

      // Add connections inside enclaves
      for (int i = 0; i < mainReactor.getConnections().size(); i++) {
        Connection connection = mainReactor.getConnections().get(i);
        for(VarRef left: connection.getLeftPorts()){
          for(VarRef right: connection.getRightPorts()){
            for(Reactor enclave: newReactors){
              if(enclave.getInstantiations().contains(right.getContainer()) &&
                  enclave.getInstantiations().contains(left.getContainer())){
                enclave.getConnections().add(connection);
              }
            }
          }
        }
      }

      // Add connections between enclaves
      List<Connection> connectionsBetweenEnclaves = new ArrayList<Connection>();
      List<Connection> connectionsRemove = new ArrayList<Connection>();

      for(Connection connection: mainReactor.getConnections()){
        Connection newConnection = factory.createConnection();

        for(VarRef left: connection.getLeftPorts()){
          Reactor myEnclave = factory.createReactor();
          Output output = factory.createOutput();
          VarRef outputVar = factory.createVarRef();

          // create port
          myEnclave = mapReactorInstToEnclave.get(left.getContainer());
          Instantiation myEnclaveInst = mapEnclaveReactorToEnclaveInst.get(myEnclave);
          outputVar.setVariable(EcoreUtil.copy(left.getVariable()));
          Port port = (Port) outputVar.getVariable();
          if(left.getContainer().getWidthSpec() != null){
            port.setWidthSpec(EcoreUtil.copy(left.getContainer().getWidthSpec()));
          }
          else if(((Port) left.getVariable()).getWidthSpec() != null){
            port.setWidthSpec(EcoreUtil.copy(((Port) left.getVariable()).getWidthSpec()));
          }
          output = (Output) port;
          mapReactorInstToEnclave.get(left.getContainer()).getOutputs().add(output);

          // ----------------------------------------------------
          if(output.getWidthSpec() != null){
            List<Parameter> paramsLeft = myEnclaveInst.getParameters().stream().map(Assignment::getLhs).collect(Collectors.toList());
            List<String> paramsNamesLeft = paramsLeft.stream().map(Parameter::getName).collect(Collectors.toList());

            List<Initializer> paramsRight = myEnclaveInst.getParameters().stream().map(Assignment::getRhs).collect(Collectors.toList());
            List<Expression> expressions = paramsRight.stream().map(Initializer::getExpr).collect(Collectors.toList());

            List<Parameter> params2 = output.getWidthSpec().getTerms().stream().map(WidthTerm::getParameter).collect(Collectors.toList());
            List<String> paramsNames2 = params2.stream().map(Parameter::getName).collect(Collectors.toList());


            for(String s: paramsNames2){
              Integer i = 0;
              for(Expression exp: expressions){
                if(exp instanceof ParameterReference){
                  ParameterReference exp2 = (ParameterReference) exp;
                  String name = exp2.getParameter().getName();
                  if(name == s){
                    i = expressions.indexOf(exp);
                  }
                }
              }
              Integer j = paramsNames2.indexOf(s);
              Parameter jjj = params2.get(j);
              jjj.setName(paramsLeft.get(i).getName());
            }
          }
          // -------------------------------------------------------

          // create connection inside enclave
          Connection conn = factory.createConnection();
          conn.getLeftPorts().add(EcoreUtil.copy(left));
          conn.getRightPorts().add(outputVar);
          myEnclave.getConnections().add(conn);

          // create connection between enclaves
          VarRef outputVar2 = EcoreUtil.copy(outputVar);
          outputVar2.setContainer(myEnclaveInst);
          newConnection.getLeftPorts().add(outputVar2);
        }
        for(VarRef right: connection.getRightPorts()){
          Reactor myEnclave = factory.createReactor();
          Input input = factory.createInput();
          VarRef inputVar = factory.createVarRef();

          // create port
          myEnclave = mapReactorInstToEnclave.get(right.getContainer());
          Instantiation myEnclaveInst = mapEnclaveReactorToEnclaveInst.get(myEnclave);
          inputVar.setVariable(EcoreUtil.copy(right.getVariable()));
          Port port = (Port) inputVar.getVariable();
          if(right.getContainer().getWidthSpec() != null){
            port.setWidthSpec(EcoreUtil.copy(right.getContainer().getWidthSpec()));
          }
          else if(((Port) right.getVariable()).getWidthSpec() != null){
            port.setWidthSpec(EcoreUtil.copy(((Port) right.getVariable()).getWidthSpec()));
          }
          //port.setWidthSpec(EcoreUtil.copy(right.getContainer().getWidthSpec()));
          input = (Input) port;
          mapReactorInstToEnclave.get(right.getContainer()).getInputs().add(input);

          // ----------------------------------------------------
          if(input.getWidthSpec() != null){
            List<Parameter> paramsLeft = myEnclaveInst.getParameters().stream().map(Assignment::getLhs).collect(Collectors.toList());
            List<String> paramsNamesLeft = paramsLeft.stream().map(Parameter::getName).collect(Collectors.toList());

            List<Initializer> paramsRight = myEnclaveInst.getParameters().stream().map(Assignment::getRhs).collect(Collectors.toList());
            List<Expression> expressions = paramsRight.stream().map(Initializer::getExpr).collect(Collectors.toList());

            List<Parameter> params2 = input.getWidthSpec().getTerms().stream().map(WidthTerm::getParameter).collect(Collectors.toList());
            List<String> paramsNames2 = params2.stream().map(Parameter::getName).collect(Collectors.toList());


            for(String s: paramsNames2){
              Integer i = 0;
              for(Expression exp: expressions){
                if(exp instanceof ParameterReference){
                  ParameterReference exp2 = (ParameterReference) exp;
                  String name = exp2.getParameter().getName();
                  if(name == s){
                    i = expressions.indexOf(exp);
                  }
                }
              }
              Integer j = paramsNames2.indexOf(s);
              Parameter jjj = params2.get(j);
              jjj.setName(paramsLeft.get(i).getName());
            }
          }
          // -------------------------------------------------------

          Connection conn = factory.createConnection();
          conn.getRightPorts().add(EcoreUtil.copy(right));
          conn.getLeftPorts().add(inputVar);
          myEnclave.getConnections().add(conn);

          VarRef inputVar2 = EcoreUtil.copy(inputVar);
          inputVar2.setContainer(myEnclaveInst);
          newConnection.getRightPorts().add(inputVar2);
        }
        connectionsRemove.add(connection);
        connectionsBetweenEnclaves.add(newConnection);
      }

      mainReactor.getConnections().removeAll(connectionsRemove);
      for (Connection c: connectionsBetweenEnclaves){
        mainReactor.getConnections().add(c);
      }

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

}







