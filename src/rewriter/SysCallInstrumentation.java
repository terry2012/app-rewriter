package rewriter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.json.JsonObject;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
// import org.apache.commons.cli.Option;
// import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;

import edu.gatech.gtisc.legoandroid.permission.PSCout;
import soot.Body;
import soot.BodyTransformer;
import soot.Local;
import soot.PackManager;
import soot.PatchingChain;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Transform;
import soot.Unit;
import soot.Value;
import soot.jimple.AbstractStmtSwitch;
import soot.jimple.AssignStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.NewExpr;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;
import soot.options.Options;


public class SysCallInstrumentation {
	public static org.apache.commons.cli.Options options = null;
	public static String apkPath = null;
	public static String androidJarDirPath = null;
	public static String outDir = null;	
	// The system call APIs that is interesting to us.
	public static PSCout psCout = null;
	// The methods that we want to instrument.
	public static List<JsonObject> instMethods = null;
	public static Set<String> addedClasses = null;

	private static void buildOptions() {
		options = new org.apache.commons.cli.Options();
		
		options.addOption("apk", true, "apk file");
		options.addOption("androidJarDir", true, "android jars directory");
		options.addOption("outDir", true, "out dir");
		options.addOption("instClasses", true, "the classes to instrument");
	}
	
	private static void parseOptions(String[] args) {
		Locale locale = new Locale("en", "US");
		Locale.setDefault(locale);
		
		CommandLineParser parser = new PosixParser();
		CommandLine commandLine;
		
		try {
			commandLine = parser.parse(options, args);
			
			commandLine.getArgs();
			org.apache.commons.cli.Option[] clOptions = commandLine.getOptions();
			
			for (int i = 0; i < clOptions.length; i++) {
				org.apache.commons.cli.Option option = clOptions[i];
				String opt = option.getOpt();
				
				if (opt.equals("apk")){
					apkPath = commandLine.getOptionValue("apk");
				} else if (opt.equals("androidJarDir")) {
					androidJarDirPath = commandLine.getOptionValue("androidJarDir");
				} else if (opt.equals("outDir")) {
					outDir = commandLine.getOptionValue("outDir");
				} else if (opt.equals("instMethods")) {
					instMethods = SysCallUtil.readJsonFromFile(commandLine.getOptionValue("instMethods"));
				}
			}
		} catch (ParseException ex) {
			ex.printStackTrace();
			return;
		}
	}
	
	public static void main(String[] args) {
		//enable assertion
		//ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
		
		buildOptions();
		parseOptions(args);
		addedClasses = new HashSet<String>();
		for (JsonObject c : instMethods) {
			if (c.keySet().size() > 1) {
				break;
			}
			addedClasses.add(c.keySet().iterator().next());
		}
		
		/* Analyze permission of the added module.
		 * 1. Analyze the permissions for the repackaged application.
		 * 2. Get the added module. So we have permissions related to the added part. 
		 */
		// Get the methods to permission mapping		
		// initialize PSCout
		String dataDir = System.getProperty("user.dir") + File.separator + "data";
		psCout = new PSCout(dataDir + File.separator + "jellybean_allmappings", dataDir + File.separator + "jellybean_intentpermissions");
		
		// prefer Android APK files// -src-prec apk
		Options.v().set_src_prec(Options.src_prec_apk);
		
		// output as APK, too//-f J
		Options.v().set_output_format(Options.output_format_dex);
		// Options.v().set_output_format(Options.output_format_jimple);
        
        // Borrowed from CallTracer.
        Options.v().set_allow_phantom_refs(true);
		Options.v().set_whole_program(true);
		Scene.v().addBasicClass("java.lang.StringBuilder", SootClass.SIGNATURES);
		Scene.v().addBasicClass("android.util.Log", SootClass.SIGNATURES);

        PackManager.v().getPack("jtp").add(new Transform("jtp.sysCallInstrumenter", new BodyTransformer() {

			@Override
			protected void internalTransform(final Body b, String phaseName, @SuppressWarnings("rawtypes") Map options) {				
				// Initialize the essential variables.
				final SootMethod currMethod = b.getMethod();
				final String className = b.getMethod().getDeclaringClass().getName();
				final String currSubSig = currMethod.getSubSignature();
				final String currSig = currMethod.getSignature();
				final PatchingChain<Unit> units = b.getUnits();
				
				// If there is filter for the class names and current class is not in the remaining list, skip.
				if (addedClasses != null && !addedClasses.contains(className)) {
					return;
				}

				// StringBuilder callTracerSB;
				final Local sbRef = addTmpRef(b, "callTracerSB", "java.lang.StringBuilder");
				final Local contentStr = addTmpRef(b, "callTracerContent", "java.lang.String");
				
				// important to use snapshotIterator here
				for (Iterator<Unit> iter = units.snapshotIterator(); iter.hasNext();) {
			        final Unit u = iter.next();
			        u.apply(new AbstractStmtSwitch() {
		                
		                public void caseInvokeStmt(InvokeStmt stmt) {
	                        InvokeExpr invokeExpr = stmt.getInvokeExpr();
	                        addLogForSysCall(invokeExpr, null, b, units, u, sbRef, contentStr, currSig, currSubSig);
		                }
		                
		                public void caseAssignStmt(AssignStmt stmt) {
							Value rValue = stmt.getRightOp();
							Value lValue = stmt.getLeftOp();
							InvokeExpr invokeExpr = null;
							if (rValue instanceof InvokeExpr) {
								invokeExpr = (InvokeExpr) rValue;
							    addLogForSysCall(invokeExpr, lValue, b, units, u, sbRef, contentStr, currSig, currSubSig);
							}
		                }
						
			        });
				}
			}
		}));
        
		String[] tokens = apkPath.split("/");
		String apkName = tokens[tokens.length - 1];
		String[] sootArgs = new String[]{
			"-android-jars",
			androidJarDirPath,
			"-process-dir",
			apkPath, 
			"-d",
			outDir + File.separator + apkName,
			"-force-android-jar",
			androidJarDirPath + "/android-22/android.jar"
		};        
		
		soot.Main.main(sootArgs);
	}

    private static Local addTmpRef(Body body, String name, String type)
    {
        Local tmpRef = Jimple.v().newLocal(name, RefType.v(type));
        body.getLocals().add(tmpRef);
        return tmpRef;
    }
    
    private static Local addTmpString(Body body)
    {
        Local tmpString = Jimple.v().newLocal("tmpString", RefType.v("java.lang.String")); 
        body.getLocals().add(tmpString);
        return tmpString;
    }
    
    private static void sbToStringAndLog(List<Unit> stmts, Local sbRef, Local contentStr) {
		// contentStr = sb.toString()
		SootMethod sbToStr = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.String toString()");
		stmts.add(Jimple.v().newAssignStmt(contentStr, Jimple.v().newVirtualInvokeExpr(sbRef, sbToStr.makeRef())));
		
		// Log.e("CallTracer", contentStr)
		SootMethod logE = Scene.v().getSootClass("android.util.Log").getMethod("int e(java.lang.String,java.lang.String)");
		stmts.add(Jimple.v().newInvokeStmt(Jimple.v().newStaticInvokeExpr(logE.makeRef(), StringConstant.v("CallTracer"), contentStr)));
    }
    
    private static void addValueString(Value arg, List<Unit> stmts, Local sbRef) {
    	// Convert Value to String, and add them to the String Builder
		String typeStr = arg.getType().toString();
		SootMethod sbAppend = null;
		boolean logArg = false;
		String strValue = null;
		if (typeStr.equals("byte")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(int)");
			logArg = true;
		} else if (typeStr.equals("short")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(int)");
			logArg = true;
		} else if (typeStr.equals("char")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(char)");
			logArg = true;
		} else if (typeStr.equals("boolean")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(boolean)");
			logArg = true;			
		} else if (typeStr.equals("double")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(double)");
			logArg = true;
		} else if (typeStr.equals("float")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(float)");
			logArg = true;			
		} else if (typeStr.equals("int")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(int)");
			logArg = true;			
		} else if (typeStr.equals("long")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(long)");
			logArg = true;			
		} else if (typeStr.equals("java.lang.String")) {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.String)");
			logArg = true;
		} else if (typeStr.equals("null_type")){
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.String)");		
			strValue = "null";
		} else {
			sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.Object)");
			logArg = true;			
		}
		
		// Update the statements.
		if (logArg) {
			stmts.add(Jimple.v().newAssignStmt(sbRef, Jimple.v().newVirtualInvokeExpr(sbRef, sbAppend.makeRef(), arg)));
		}
		if (strValue != null) {
			stmts.add(Jimple.v().newAssignStmt(sbRef,
					Jimple.v().newVirtualInvokeExpr(sbRef, sbAppend.makeRef(), StringConstant.v(strValue))));
		}
		sbAppend = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.String)");
		stmts.add(Jimple.v().newAssignStmt(sbRef, Jimple.v().newVirtualInvokeExpr(sbRef, sbAppend.makeRef(), StringConstant.v(", "))));
    }
    
    private static void addLogForSysCall(InvokeExpr invokeExpr, Value returnValue, Body body,
    		PatchingChain<Unit> units, Unit currentUnit,
    		Local sbRef, Local contentStr, String methodSig, String methodSubsig) {
		/* This function does the following changes to the original function body.
		 * 1. Print class name, method name, permission
		 * 2a. If argument is primitive type, log Argument value, else Argument type
		 * 2b. If argument is non-primitive, call toString method, print toString()
		 * 3. If return value is not void, follow the similar procedure to insert expression after a unit.
		 */
    	// Insert the required logic before each API call invocation and after each API call invocation.
		SootMethod targetMethod = invokeExpr.getMethod();
		String targetClassName = targetMethod.getDeclaringClass().getName();
		String targetMethodName = targetClassName + "." + targetMethod.getName();
		String permission = psCout.getApiPermission(targetMethodName);
		if (permission == null) {
			// If current method doesn't require permission, skip it.
			return;
		}
		System.out.println("Instrumenting:[method]" + methodSig + ", [permission]" + permission);
		List<Unit> before = new ArrayList<Unit>();
		List<Unit> after = new ArrayList<Unit>();
		
		// ============================= Step 1: print class name, method name, permission =============================
		// callTracerSB = new java.lang.StringBuilder
		{
			SootMethod sbAppendO = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.Object)");		
			NewExpr newSBExpr = Jimple.v().newNewExpr(RefType.v("java.lang.StringBuilder"));
			before.add(Jimple.v().newAssignStmt(sbRef, newSBExpr));
			
			//specialinvoke callTracerSB.init<>
			SootMethod sbConstructor = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("void <init>(java.lang.String)");
			before.add(
					Jimple.v().newInvokeStmt(
						Jimple.v().newSpecialInvokeExpr(sbRef, sbConstructor.makeRef(), 
								StringConstant.v("[Permission]#" + permission + "[Signature]#" + methodSig + "@"
										+ "[Target]#" + targetMethod.isStatic() + "#" + targetMethod.isJavaLibraryMethod() +
										"#" + targetMethod.getSignature() + "("))));
		}
		// ============================= Step 2: print argument values based on their types =============================
		// callTracerSB = new java.lang.StringBuilder
		{
			// this
			if ((invokeExpr instanceof InstanceInvokeExpr) && !(invokeExpr instanceof SpecialInvokeExpr)) {
				Value baseValue = ((InstanceInvokeExpr) invokeExpr).getBase();
				SootMethod sbAppendO = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.Object)");
				before.add(Jimple.v().newAssignStmt(sbRef, Jimple.v().newVirtualInvokeExpr(sbRef, sbAppendO.makeRef(), baseValue)));
				SootMethod sbAppendS = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.String)");
				before.add(Jimple.v().newAssignStmt(sbRef, Jimple.v().newVirtualInvokeExpr(sbRef, sbAppendS.makeRef(), StringConstant.v(", "))));
			}
			
			// append the parameters
			List<Value> args = invokeExpr.getArgs();
			for (Value arg : args) {
				addValueString(arg, before, sbRef);
			}
			SootMethod sbAppendS = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("java.lang.StringBuilder append(java.lang.String)");
			before.add(Jimple.v().newAssignStmt(sbRef, Jimple.v().newVirtualInvokeExpr(sbRef, sbAppendS.makeRef(), StringConstant.v(")"))));
			
			// Log.e("CallTracer", callTracerSB.toString())
			sbToStringAndLog(before, sbRef, contentStr);
		}
		// ============================= Step 3: print return values based on their types =============================
		{
			if (returnValue != null) {
				// callTracerSB = new java.lang.StringBuilder
				NewExpr newSBExpr = Jimple.v().newNewExpr(RefType.v("java.lang.StringBuilder"));
				after.add(Jimple.v().newAssignStmt(sbRef, newSBExpr));
				//specialinvoke callTracerSB.init<>
				SootMethod sbConstructor = Scene.v().getSootClass("java.lang.StringBuilder").getMethod("void <init>(java.lang.String)");
				after.add(Jimple.v().newInvokeStmt(
						Jimple.v().newSpecialInvokeExpr(sbRef, sbConstructor.makeRef(), StringConstant.v("[Return]#"))));
				
				// What are the possible left value types?
				
				// append the return value
				addValueString(returnValue, after, sbRef);
				// Log.e("CallTracer", callTracerSB.toString())
				sbToStringAndLog(after, sbRef, contentStr);
			}
		}
		units.insertBefore(before, currentUnit);		
		units.insertAfter(after, currentUnit);
		body.validate();
    }
}