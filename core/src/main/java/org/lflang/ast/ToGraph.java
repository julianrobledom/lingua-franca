package org.lflang.ast;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.xbase.lib.IterableExtensions;
import org.eclipse.xtext.xbase.lib.IteratorExtensions;
import org.eclipse.xtext.xbase.lib.StringExtensions;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.lflang.DefaultMessageReporter;
import org.lflang.InferredType;
import org.lflang.MessageReporter;
import org.lflang.ast.MalleableString.Builder;
import org.lflang.ast.MalleableString.Joiner;
import org.lflang.generator.DelayBodyGenerator;
import org.lflang.generator.PortInstance;
import org.lflang.generator.ReactorInstance;
import org.lflang.generator.RuntimeRange;
import org.lflang.generator.TargetTypes;
import org.lflang.lf.Action;
import org.lflang.lf.Array;
import org.lflang.lf.Assignment;
import org.lflang.lf.AttrParm;
import org.lflang.lf.Attribute;
import org.lflang.lf.BracedListExpression;
import org.lflang.lf.BracketListExpression;
import org.lflang.lf.BuiltinTriggerRef;
import org.lflang.lf.CStyleArraySpec;
import org.lflang.lf.Code;
import org.lflang.lf.CodeExpr;
import org.lflang.lf.Connection;
import org.lflang.lf.Deadline;
import org.lflang.lf.Element;
import org.lflang.lf.Expression;
import org.lflang.lf.Host;
import org.lflang.lf.IPV4Host;
import org.lflang.lf.IPV6Host;
import org.lflang.lf.Import;
import org.lflang.lf.ImportedReactor;
import org.lflang.lf.Initializer;
import org.lflang.lf.Input;
import org.lflang.lf.Instantiation;
import org.lflang.lf.KeyValuePair;
import org.lflang.lf.KeyValuePairs;
import org.lflang.lf.Literal;
import org.lflang.lf.Method;
import org.lflang.lf.MethodArgument;
import org.lflang.lf.Mode;
import org.lflang.lf.Model;
import org.lflang.lf.NamedHost;
import org.lflang.lf.Output;
import org.lflang.lf.Parameter;
import org.lflang.lf.ParameterReference;
import org.lflang.lf.ParenthesisListExpression;
import org.lflang.lf.Port;
import org.lflang.lf.Preamble;
import org.lflang.lf.Reaction;
import org.lflang.lf.Reactor;
import org.lflang.lf.ReactorDecl;
import org.lflang.lf.STP;
import org.lflang.lf.Serializer;
import org.lflang.lf.StateVar;
import org.lflang.lf.TargetDecl;
import org.lflang.lf.Time;
import org.lflang.lf.Timer;
import org.lflang.lf.TriggerRef;
import org.lflang.lf.Type;
import org.lflang.lf.TypeParm;
import org.lflang.lf.TypedVariable;
import org.lflang.lf.VarRef;
import org.lflang.lf.Variable;
import org.lflang.lf.Visibility;
import org.lflang.lf.Watchdog;
import org.lflang.lf.WidthSpec;
import org.lflang.lf.WidthTerm;
import org.lflang.lf.util.LfSwitch;
import org.lflang.util.StringUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import org.lflang.lf.LfFactory;
import org.lflang.lf.Port;
import org.lflang.lf.Reaction;
import org.lflang.lf.Action;



public class ToGraph extends ToLf {

    private MessageReporter reporter = new DefaultMessageReporter();
    private static LfFactory factory = LfFactory.eINSTANCE;
    public Reactor mainReactor;
    public MessageReporter messageReporter;

    //@Override
    public MalleableString getTopLevelGraph(Model object) {

    try {


      BufferedWriter writer = new BufferedWriter(new FileWriter("src/adj_matrix.py"));

      // declares an Array of integers.
      int matSize;

      // connections
      int src_index = 0;
      int dst_index = 0;

      for (Reactor r : object.getReactors() ){
        if(r.isMain()){
            mainReactor = r;
            // initialize adjacent matrix
            matSize = r.getInstantiations().size();
            int[][] adj_mat = new int[matSize][matSize];

            for(Connection c: r.getConnections()){
                List<Integer> src_indexes = new ArrayList<>();
                List<Integer> dst_indexes = new ArrayList<>();

                if(!c.getLeftPorts().isEmpty()) {
                    for(VarRef p: c.getLeftPorts()){
                        Port port = (Port) p.getVariable();
                        src_indexes.add(r.getInstantiations().indexOf(
                            p.getContainer()
                        ));
                    }
                }

                if(!c.getRightPorts().isEmpty()) {
                    for(VarRef p: c.getRightPorts()){
                        Port port = (Port) p.getVariable();
                        dst_indexes.add(r.getInstantiations().indexOf(
                            p.getContainer()
                        ));
                    }
                }

                for(int i: src_indexes){
                    for (int j: dst_indexes){
                        adj_mat[i][j] = 1;
                    }
                }
            }

            writer.write("adj_mat = ");
            writer.append(Arrays.deepToString(adj_mat));
        }
      }

      writer.close();
    }
    catch (IOException e) {
      System.err.println("Error: " + e.getMessage());
    }

    // Run python script
    try{
        Process p = new ProcessBuilder("python3", 
            "src/dse.py")
            .redirectErrorStream(true)
            .start();
        p.getInputStream().transferTo(System.out);
        //int rc = p.waitFor();
    } catch (IOException e) {
        System.err.println("Error: " + e.getMessage());
    }


    List<List<List<Integer>>> enclaves = getEnclavesFromCsv();

    Integer program_counter = 0;
    for (List<List<Integer>> list: enclaves){
        generateLF(object, list, program_counter);
        program_counter++;
    }

    return doSwitch(object.getTarget());
  }


  public MalleableString generateLF(
    Model object2, 
    List<List<Integer>> list,
    Integer program_counter
  ) {
      Model object = EcoreUtil.copy(object2);
      Reactor mainReactor2 = factory.createReactor();

      for (Reactor r : object.getReactors() ){
        if(r.isMain()){
            mainReactor2 = r;
            break;
        }
      }

      // -------------------------------------------------------
      int counter = 0;
      List<Reactor> newReactors = new ArrayList<Reactor>();
      
      List<Instantiation> alist = new ArrayList<Instantiation>();
      System.out.println("Hello ");

      Map<Integer, Instantiation> map = new HashMap<Integer, Instantiation>();
      for (Instantiation instance: mainReactor2.getInstantiations()){
        map.put(counter, instance);
        counter++;
      }

      Map<Instantiation, Reactor> reactor_to_enclaves = new HashMap<Instantiation, Reactor>();
      Map<Reactor, Instantiation> reactor_to_instantiation = new HashMap<Reactor, Instantiation>();
      counter = 0;

      for (List<Integer> enclave : list){
        Reactor enclaveClass = factory.createReactor();
        Parameter enclaveParameter = factory.createParameter();

        enclaveClass.setName("enclave" + counter);

        Instantiation d = factory.createInstantiation();
        d.setReactorClass(enclaveClass);
        d.setName("encl" + counter);

        mainReactor2.getInstantiations().add(d);
        object.getReactors().add(enclaveClass);
        newReactors.add(enclaveClass);
        reactor_to_instantiation.put(enclaveClass, d);

        counter++;

        for (int e: enclave) {
          // get parameteres and add to the enclave instantiations
          List<Assignment> att = map.get(e).getParameters();
          List<Parameter> params = d.getParameters().stream().map(Assignment::getLhs).collect(Collectors.toList());
          List<String> paramsNames = params.stream().map(Parameter::getName).collect(Collectors.toList());
          for(Assignment assignment: att){
            if(!paramsNames.contains(assignment.getLhs().getName())){
              d.getParameters().add(EcoreUtil.copy(assignment));
            }
          }
          
          enclaveClass.getInstantiations().add(map.get(e));

          // get parameters and add to the enclave reactor class
          Reactor r2 = factory.createReactor();
          for(Reactor r: object.getReactors()){if(r.getName() == map.get(e).getReactorClass().getName()){ r2 = r;}}
          List<String> params2 = enclaveClass.getParameters().stream().map(Parameter::getName).collect(Collectors.toList());
          for(Parameter parameter: r2.getParameters()){
            if(!params2.contains(parameter.getName())){
              System.out.println("parameter " + parameter.getName());
              enclaveClass.getParameters().add(EcoreUtil.copy(parameter));
            }
          }

          // create map
          reactor_to_enclaves.put(map.get(e), enclaveClass);
        }
      }

      // Add connections inside enclaves
      List<Connection> cc = new ArrayList<Connection>();
      List<Reactor> re = new ArrayList<Reactor>();
      for(Connection c: mainReactor2.getConnections()){
        for(VarRef left: c.getLeftPorts()){
          for(VarRef right: c.getRightPorts()){
            for(Reactor enclave: newReactors){
              System.out.println("enclave " + enclave.getName());
              if(enclave.getInstantiations().contains(right.getContainer()) &&
                  enclave.getInstantiations().contains(left.getContainer())){
                cc.add(c);
                re.add(enclave);
              }
            }
          }
        }
      }
      for (int i = 0; i < cc.size(); i++) {
        re.get(i).getConnections().add(cc.get(i));
      }

      // Add connections between enclaves
      List<Connection> connectionsBetweenEnclaves = new ArrayList<Connection>();
      List<Connection> connectionsRemove = new ArrayList<Connection>();

      for(Connection c: mainReactor2.getConnections()){
        Connection myconnection = factory.createConnection();

        for(VarRef left: c.getLeftPorts()){
          Reactor myEnclave = factory.createReactor();
          Output output = factory.createOutput();
          VarRef outputVar = factory.createVarRef();

          // create port
          myEnclave = reactor_to_enclaves.get(left.getContainer());
          Instantiation myEnclaveInst = reactor_to_instantiation.get(myEnclave);
          outputVar.setVariable(EcoreUtil.copy(left.getVariable()));
          Port port = (Port) outputVar.getVariable();
          if(left.getContainer().getWidthSpec() != null){
            port.setWidthSpec(EcoreUtil.copy(left.getContainer().getWidthSpec()));
          }
          else if(((Port) left.getVariable()).getWidthSpec() != null){
            port.setWidthSpec(EcoreUtil.copy(((Port) left.getVariable()).getWidthSpec()));
          }
          output = (Output) port;
          reactor_to_enclaves.get(left.getContainer()).getOutputs().add(output);

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
          myconnection.getLeftPorts().add(outputVar2);
        }
        for(VarRef right: c.getRightPorts()){
          Reactor myEnclave = factory.createReactor();
          Input input = factory.createInput();
          VarRef inputVar = factory.createVarRef();

          // create port
          myEnclave = reactor_to_enclaves.get(right.getContainer());
          Instantiation myEnclaveInst = reactor_to_instantiation.get(myEnclave);
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
          reactor_to_enclaves.get(right.getContainer()).getInputs().add(input);

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
          myconnection.getRightPorts().add(inputVar2);
        }
        connectionsRemove.add(c);
        connectionsBetweenEnclaves.add(myconnection);
      }

      mainReactor2.getConnections().removeAll(connectionsRemove);
      for (Connection c: connectionsBetweenEnclaves){
        mainReactor2.getConnections().add(c);
      }

      try{
        BufferedWriter writer = new BufferedWriter(new FileWriter("enclaves/enclaves_lf_" + program_counter + ".py"));
        writer.append(caseModel(object).toString());
        writer.close();
      }
      catch (IOException e) {
        System.err.println("Error: " + e.getMessage());
      }

      return caseModel(object);
  }


  public List<List<List<Integer>>> getEnclavesFromJava() {
    List<List<List<Integer>>> listOfListOfLists = new ArrayList<List<List<Integer>>>();
    List<List<Integer>> listOfLists = new ArrayList<List<Integer>>();
    List<Integer> singleList = new ArrayList<Integer>();

    listOfLists = new ArrayList<List<Integer>>();
    singleList = new ArrayList<Integer>();
    singleList.add(0);
    singleList.add(1);
    listOfLists.add(singleList);
    singleList = new ArrayList<Integer>();
    singleList.add(2);
    listOfLists.add(singleList);
    listOfListOfLists.add(listOfLists);

    listOfLists = new ArrayList<List<Integer>>();
    singleList = new ArrayList<Integer>();
    singleList.add(0);
    listOfLists.add(singleList);
    singleList = new ArrayList<Integer>();
    singleList.add(1);
    singleList.add(2);
    listOfLists.add(singleList);
    listOfListOfLists.add(listOfLists);

    listOfLists = new ArrayList<List<Integer>>();
    singleList = new ArrayList<Integer>();
    singleList.add(0);
    singleList.add(1);
    listOfLists.add(singleList);
    singleList = new ArrayList<Integer>();
    singleList.add(2);
    listOfLists.add(singleList);
    listOfListOfLists.add(listOfLists);

    listOfLists = new ArrayList<List<Integer>>();
    singleList = new ArrayList<Integer>();
    singleList.add(0);
    listOfLists.add(singleList);
    singleList = new ArrayList<Integer>();
    singleList.add(1);
    singleList.add(2);
    listOfLists.add(singleList);
    listOfListOfLists.add(listOfLists);

    return listOfListOfLists;
  }

  public List<List<List<Integer>>> getEnclavesFromCsv() {

    List<List<List<Integer>>> listOfListOfLists = new ArrayList<List<List<Integer>>>();
    List<List<Integer>> listOfLists = new ArrayList<List<Integer>>();
    List<Integer> singleList = new ArrayList<Integer>();

    try (BufferedReader br = new BufferedReader(new FileReader("enclaves.csv"))) {

        String line;
        int number;

        while ((line = br.readLine()) != null) {
            listOfLists = new ArrayList<List<Integer>>();
            String[] partition = line.split(Pattern.quote("|"));

            for(String i: partition){
                singleList = new ArrayList<Integer>();
                String[] values = i.split(",");

                for(int j = 0; j < values.length; j++) {
                    if (!values[j].trim().isEmpty()){
                        number = Integer.parseInt(values[j]);
                        singleList.add(number);
                        System.out.println(number);
                    }
                }
                listOfLists.add(singleList);
            }
            listOfListOfLists.add(listOfLists);
        }
    } catch (IOException e) {
        System.err.println("Error: " + e.getMessage());
    }

    return listOfListOfLists;
  }
}







