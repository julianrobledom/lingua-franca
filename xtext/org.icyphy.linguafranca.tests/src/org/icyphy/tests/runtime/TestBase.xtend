package org.icyphy.tests.runtime

import com.google.inject.Inject
import com.google.inject.Provider
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Set
import java.util.concurrent.TimeUnit
import org.eclipse.emf.common.util.URI
import org.eclipse.emf.ecore.resource.ResourceSet
import org.eclipse.xtext.diagnostics.Severity
import org.eclipse.xtext.generator.GeneratorDelegate
import org.eclipse.xtext.generator.JavaIoFileSystemAccess
import org.eclipse.xtext.testing.InjectWith
import org.eclipse.xtext.testing.extensions.InjectionExtension
import org.eclipse.xtext.util.CancelIndicator
import org.eclipse.xtext.validation.CheckMode
import org.eclipse.xtext.validation.IResourceValidator
import org.eclipse.xtext.xbase.lib.Functions.Function1
import org.icyphy.ASTUtils
import org.icyphy.Target
import org.icyphy.generator.StandaloneContext
import org.icyphy.linguaFranca.Reactor
import org.icyphy.tests.LFTest
import org.icyphy.tests.LFTest.Result
import org.icyphy.tests.LinguaFrancaInjectorProvider
import org.icyphy.tests.TestRegistry
import org.icyphy.tests.TestRegistry.TestCategory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.^extension.ExtendWith

import static extension org.junit.Assert.assertTrue
import org.icyphy.generator.GeneratorBase
import org.icyphy.TargetConfig
import org.icyphy.FileConfig
import org.eclipse.emf.ecore.resource.Resource
import java.util.Properties

@ExtendWith(InjectionExtension)
@InjectWith(LinguaFrancaInjectorProvider)

abstract class TestBase {
    
    @Inject IResourceValidator validator
    @Inject GeneratorDelegate generator
    @Inject JavaIoFileSystemAccess fileAccess
    @Inject Provider<ResourceSet> resourceSetProvider;
    

    static val out = System.out;
    static val err = System.err;
    
    final static long MAX_EXECUTION_TIME_SECONDS = 60
    
    public final static String NEW_LINE = System.getProperty("line.separator");
    
    public final static String DIVIDER = "+----------------------------------------------------------------------------+" + NEW_LINE;
    
    public final static String THIN_LINE = "------------------------------------------------------------------------------" + NEW_LINE;
    
    public final static String THICK_LINE = "==============================================================================" + NEW_LINE;
    
    public final static String EDGE_LINE = "+--------------------------------------------------------------------=-------+" + NEW_LINE;
    
    public final static String RUN_AS_FEDERATED_DESC = "Description: Run non-federated tests in federated mode.";
    
    protected Target target;
    
    protected boolean check = true;
    
    protected boolean run = true;
    
    protected boolean build = true;
    
    /**
     * Force the instantiation of the test registry.
     */
    new() {
        TestRegistry.initialize()
    }
    
    @Test
    def void runExampleTests() {
        printTestHeader("Description: Run example tests.")
        this.target.runTestsAndPrintResults([
            it === TestCategory.EXAMPLE_TEST
        ], null, false)
    }
    
    @Test
    def void compileExamples() {
        printTestHeader("Description: Compile examples.")
        this.run = false;
        this.target.runTestsAndPrintResults([
            it === TestCategory.EXAMPLE
        ], [
            return true
        ], 
        false)
        this.run = false;
    }
    
    @Test
    def void runGenericTests() {
        printTestHeader("Description: Run generic tests (threads = 0).")
        this.target.runTestsAndPrintResults([
            it === TestCategory.GENERIC
        ], [
            it.context.getArgs().setProperty("threads", "0")
            return true
        ],  
        false)
    }
    
    @Test
    def void runTargetSpecificTests() {
        printTestHeader("Description: Run target-specific tests (threads = 0).")
        this.target.runTestsAndPrintResults([it === TestCategory.TARGET], [
            it.context.getArgs().setProperty("threads", "0")
            return true
        ],
        false)
    }
    
    @Test
    def void runMultiportTests() {
        printTestHeader("Description: Run multiport tests (threads = 0).")
        this.target.runTestsAndPrintResults([it === TestCategory.MULTIPORT], [
            it.context.getArgs().setProperty("threads", "0")
            return true
        ],
        false)
    }
    
    @Test
    def void runAsFederated() {
        printTestHeader(RUN_AS_FEDERATED_DESC)
        this.target.runTestsAndPrintResults([
            it !== TestCategory.CONCURRENT && it !== TestCategory.FEDERATED &&
            it !== TestCategory.EXAMPLE && it !== TestCategory.EXAMPLE_TEST &&
            it !== TestCategory.MULTIPORT // FIXME: also run the multiport tests once these are supported.
        ], [ASTUtils.makeFederated(it.fileConfig.resource)], 
        true)
    }
    
    @Test
    def void runConcurrentTests() {
        printTestHeader("Description: Run concurrent tests.")
        this.target.runTestsAndPrintResults([it === TestCategory.CONCURRENT], null, false)
    }
    
    @Test
    def void runFederatedTests() {
        printTestHeader("Description: Run federated tests.")
        this.target.runTestsAndPrintResults([it === TestCategory.FEDERATED], null, false)
    }

    static def void restoreOutputs() {
        System.out.flush()
        System.err.flush()
        System.setOut(out)
        System.setErr(err)
    }

    static def void redirectOutputs(LFTest test) {
        System.setOut(new PrintStream(test.out))
        System.setErr(new PrintStream(test.err))
//        System.setErr(new PrintStream(new OutputStream() {
//            override write(int b) throws IOException {}
//        }));
    }

    def runTestsAndPrintResults(Target target, Function1<TestCategory, Boolean> selection, Function1<LFTest, Boolean> configuration, boolean copy) {
        val categories = TestCategory.values().filter(selection)
        for (category : categories) {
            println(category.header);
            val tests = TestRegistry.getRegisteredTests(target, category, copy)
            tests.validateAndRun(configuration)
            println(TestRegistry.getCoverageReport(target, category));
            if (check) {
                tests.checkAndReportFailures()
            }
        }
    }
    
    def void printTestHeader(String description) {
        print(TestBase.THICK_LINE)
        println("Target: " + this.target)
        println(description)
        println(TestBase.THICK_LINE)
    }

    def void checkAndReportFailures(Set<LFTest> registered) {
        var passed = registered.filter[!it.hasFailed].size
        
        print(THIN_LINE)
        println("Passing: " + passed + "/" + registered.size)
        print(THIN_LINE)
         
        for (test : registered) {
            print(test.reportErrors)
        }
        registered.forall[it.result === Result.TEST_PASS].assertTrue
    }

    def boolean configureAndValidate(LFTest test, Function1<LFTest, Boolean> configuration) {
        
        if (test.result == Result.PARSE_FAIL) {
            // Abort is parsing was unsuccessful.
            return false
        }
        
        redirectOutputs(test)
        
        val context = new StandaloneContext()
        // Update file config, which includes a fresh resource that has not
        // been tampered with using AST transformations.
        context.setCancelIndicator(CancelIndicator.NullImpl);
        context.setArgs(new Properties());
        context.setPackageRoot(test.packageRoot);
        context.setHierarchicalBin(true);
        
        val r = resourceSetProvider.get().getResource(
                    URI.createFileURI(test.srcFile.toFile().getAbsolutePath()),
                    true)
        
        if (r.errors.size > 0) {
            test.result = Result.PARSE_FAIL
            restoreOutputs()
            return false
        }
        fileAccess.outputPath = context.packageRoot.resolve(FileConfig.DEFAULT_SRC_GEN_DIR).toString();
        test.fileConfig = new FileConfig(r, fileAccess, context);
        
        // Set the no-compile flag if appropriate.
        if (!this.build) {
            context.getArgs().setProperty("no-compile", "")
        }
        
        // Validate the resource and store issues in the test object.
        try {
            val issues = validator.validate(test.fileConfig.resource,
                CheckMode.ALL, context.cancelIndicator)
            if (!issues.isNullOrEmpty) {
                test.issues.append(issues.join(NEW_LINE))
                if (issues.exists [
                    it.severity == Severity.ERROR
                ]) {
                    test.result = Result.VALIDATE_FAIL
                    restoreOutputs()
                    return false
                }
            }
        } catch(Exception e) {
            test.result = Result.VALIDATE_FAIL
            restoreOutputs()
            return false
        }
        
        // Update the test by applying the configuration. E.g., to carry out an AST transformation.
        if (configuration !== null && !configuration.apply(test)) {
            test.result = Result.CONFIG_FAIL
            restoreOutputs()
            return false
        }
        
        restoreOutputs()
        return true
    }

    /**
     * Invoke the code generator for the given test.
     * 
     * @return True if code was generated successfully, false otherwise.
     */
    def boolean generateCode(LFTest test) {
        if (test.fileConfig.resource !== null) {
            redirectOutputs(test)
            try {
                generator.generate(test.fileConfig.resource, fileAccess, test.fileConfig.context)
            } catch (Exception e) {
                e.printStackTrace()
                test.issues.append(e.message)
                test.result = Result.CODE_GEN_FAIL
                restoreOutputs()
                return false
            }
            
            restoreOutputs()
            return true
        }
        return false
    }

    def execute(LFTest test) {
        var ProcessBuilder pb;
        val nameWithExtension = test.srcFile.fileName.toString
        val nameOnly = nameWithExtension.substring(0, nameWithExtension.lastIndexOf('.'))
        
        // FIXME: we probably want to use the createCommand utility from GeneratorBase here.
        // Should it be made static? Factored out?
        switch(test.target) {
            case C,
            case CPP,
            case CCPP: {
                val binPath = test.fileConfig.binPath
                var binaryName = nameOnly
                // Adjust binary extension if running on Window
                if (System.getProperty("os.name").startsWith("Windows")) {
                    binaryName = nameOnly + ".exe"
                }

                val fullPath = binPath.resolve(binaryName)
                if (Files.exists(fullPath)) {
                    // Running the command as .\binary.exe does not work on Windows for
                    // some reason... Thus we simply pass the full path here, which
                    // should work across all platforms
                    pb = new ProcessBuilder(fullPath.toString)
                    pb.directory(binPath.toFile)
                } else {
                    test.issues.append(
                        fullPath + ": No such file or directory." + NEW_LINE)
                    test.result = Result.NO_EXEC_FAIL
                }
            }
            case Python: {
                val srcGen = test.fileConfig.getSrcGenPath
                val fullPath = srcGen.resolve(nameOnly + ".py")
                if (Files.exists(fullPath)) {
                    pb = new ProcessBuilder("python3", fullPath.toFile.name)
                    pb.directory(srcGen.toFile)
                } else {
                    test.result = Result.NO_EXEC_FAIL
                    if (pb !== null) {
                        test.issues.append("Process builder: " + pb.toString + NEW_LINE)
                    }
                    if (fullPath !== null) {
                        test.issues.append("File: " + fullPath + NEW_LINE)
                    }
                }
            }
            case TS: {
                val dist = test.fileConfig.getSrcGenPath.resolve("dist")
                val file = dist.resolve(nameOnly + ".js")
                if (Files.exists(file)) {
                    pb = new ProcessBuilder("node", file.toString)
                } else {
                    test.result = Result.NO_EXEC_FAIL
                    if (pb !== null) {
                        test.issues.append("Process builder: " + pb.toString + NEW_LINE)
                    }
                    if (file !== null) {
                        test.issues.append("File: " + file + NEW_LINE)
                    }
                }
            }
        }
        if (pb !== null) {
            try {
                val p = pb.start()
                val stdout = test.exec.recordStdOut(p)
                val stderr = test.exec.recordStdErr(p)
                if(!p.waitFor(MAX_EXECUTION_TIME_SECONDS, TimeUnit.SECONDS)) {
                    stdout.interrupt()
                    stderr.interrupt()
                    p.destroyForcibly()
                    test.result = Result.TEST_TIMEOUT
                } else {
                    if (p.exitValue == 0) {
                        test.result = Result.TEST_PASS
                    } else {
                        test.result = Result.TEST_FAIL
                    }
                }
                
            } catch(Exception e) {
                test.result = Result.TEST_FAIL
            }
            
        } else {
            test.result = Result.NO_EXEC_FAIL
        }

    }

    def validateAndRun(Set<LFTest> tests, Function1<LFTest, Boolean> configuration) { // FIXME change this into Consumer
        val x = 78f / tests.size()
        var marks = 0
        var done = 0
        for (test : tests) {
            if (test.configureAndValidate(configuration) && test.generateCode()) {
                if (run) {
                    test.execute()
                } else if (test.result == Result.UNKNOWN) {
                    test.result = Result.TEST_PASS;
                }
            }
            done++
            while (Math.floor(done * x) >= marks && marks < 78) {
                print("=")
                marks++
            }
        }
        while(marks < 78) {
            print("=")
            marks++
        }
        print(NEW_LINE)
    }
}
