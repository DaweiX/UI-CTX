import com.alibaba.fastjson2.JSON;
import scala.Tuple2;
import scala.Tuple4;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JAssignStmt;
import soot.jimple.internal.JLookupSwitchStmt;
import soot.util.Chain;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

public class CodeParser {
    static Logger log;
    private HashMap<String, Set<String>> findEdges;
    private HashMap<String, Set<String>> useEdges;
    // ui_id@xml: class, method, type, value
    private Map<String, Tuple4<String, String, String, String>> hardcodeStrings;
    private Map<String, Set<Tuple2<String, String>>> switchEdges;
    private Map<String, Set<Tuple2<String, String>>> threadEdges;
    private final Chain<SootClass> appClasses;
    // Class: Set(idHex, fieldName)
    private Map<String, Set<Tuple2<String, String>>> class2IdName;
    private List<UIContext.ArscObj> arscArray;
    private final String arscJsonPath;

    public CodeParser(Logger logger, Chain<SootClass> appClasses,
                      String arscJsonPath) {
        log = logger;
        this.appClasses = appClasses;
        this.arscJsonPath = arscJsonPath;
    }

    private static final String LINK_FIELD = "field";
    private static final String LINK_FIND = "find";
    static final String METHOD_START_PREFIX = "void start(";
    static final String THREAD_INIT_PREFIX = "<java.lang.Thread: void <init>(";
    static final String CLASS_RUNNABLE = "java.lang.Runnable";
    static final String CLASS_THREAD = "java.lang.Thread";

    /**
     * Identify UI id in a soot method, then search for the name
     * of the corresponding local field, helping to build use edges
     * @param method Soot method
     */
    @SuppressWarnings("SpellCheckingInspection")
    public void getIdNameInMethod(SootMethod method) {
        List<Tuple4<String, String, String, String>> mapIdNameClass = new ArrayList<>();
        Body body = method.getActiveBody();
        if (body == null) {
            return;
        }
        Value currentVarName = null;
        String idDeg = null;
        for (Unit unit : body.getUnits()) {
            Stmt s = (Stmt) unit;
            if (s.containsInvokeExpr()) {
                InvokeExpr invokeExpr = s.getInvokeExpr();
                String methodName = invokeExpr.getMethod().getName();
                /* ----------reminder----------
                jimple is a 3-address code, so the ref name appears
                in the next 1 or 2 (or even more) statements, before
                the next findViewById invoke
                // one ui ref
                $r4 = virtualinvoke r0.<XXActivity: android.view.View findViewById(int)>(2131034590)
                r0.<XXActivity: android.view.View mainLayout> = $r4
                // another ui ref
                $r4 = virtualinvoke r0.<XXActivity: android.view.View findViewById(int)>(2131034593)
                $r5 = (android.widget.ImageView) $r4
                r0.<XXActivity: android.widget.ImageView imgView> = $r5
                 ------------------------------*/

                // find invoke (the first ui hit)
                if (methodName.equals("findViewById")) {
                    log.finest("statement: " + s);
                    idDeg = invokeExpr.getArg(0).toString();
                    log.finer("id: "+ idDeg);
                    try {
                        mapIdNameClass.add(new Tuple4<>(
                                String.format("%08x", Integer.parseInt(idDeg)), "", "", "find")
                        );
                    } catch (NumberFormatException ignored) {
                        log.fine(String.format("control id is in a variable (%s)", idDeg));
                    }
                    if (!s.getDefBoxes().isEmpty()) {
                        currentVarName = s.getDefBoxes().get(0).getValue();
                        log.finer("var name: " + currentVarName);
                    } else {
                        log.fine("no def box in: " + s);
                    }
                }
            } else {
                if (currentVarName != null) {
                    if (!(s instanceof JAssignStmt)) {
                        log.fine("not assign statement!");
                        continue;
                    }
                    List<ValueBox> useBoxes = ((JAssignStmt) s).getUseBoxes();
                    // we get the last box considering the type cast case like
                    // $r5 = (android.widget.ImageView) $r4
                    log.finest("statement: " + s);
                    log.finest("use boxes: " + Arrays.toString(useBoxes.toArray()));
                    Value rop = useBoxes.get(useBoxes.size() - 1).getValue();
                    log.finest("rop: " + rop);
                    if (!rop.equals(currentVarName)) {
                        log.finest(String.format("rop (%s) not equals to current var name (%s)",
                                rop, currentVarName));
                    }
                    // access ui via class fields
                    if (s.containsFieldRef()) {
                        FieldRef field = s.getFieldRef();
                        String refName = field.getFieldRef().name();
                        String refClass = field.getFieldRef().declaringClass().getName();
                        log.finest("ref name: " + refName);
                        log.finest("ref class: " + refClass);
                        try {
                            String idHex = String.format("%08x", Integer.parseInt(idDeg));
                            mapIdNameClass.add(new Tuple4<>(idHex, refName, refClass, LINK_FIELD));
                            log.finer(String.format("id: %s, refname: %s, class: %s", idHex, refName, refClass));
                        } catch (NumberFormatException ignored) {
                            log.fine(String.format("control id is in a variable (%s)", idDeg));
                        }
                        currentVarName = null;
                    } else {
                        // update var name
                        currentVarName = ((JAssignStmt) s).getLeftOpBox().getValue();
                        log.finer("var name: " + currentVarName);
                    }
                }
            }
        }

        for (Tuple4<String, String, String, String> item: mapIdNameClass) {
            String idHex = item._1();
            String varName = item._2();
            String jimpleClass = item._3();
            String type = item._4();

            if (type.equals(LINK_FIELD)) {
                /* TODO: complex field ref like
                   String trim = MainActivity.AnonymousClass100000027.
                     access$0(r22).edit1.getText().toString().trim() */
                Tuple2<String, String> tuple = new Tuple2<>(idHex, varName);
                if (!class2IdName.containsKey(jimpleClass)) {
                    class2IdName.put(jimpleClass, Collections.singleton(tuple));
                } else {
                    Set<Tuple2<String, String>> tmp = new HashSet<>(class2IdName.get(jimpleClass));
                    tmp.add(tuple);
                    class2IdName.remove(jimpleClass);
                    class2IdName.put(jimpleClass, tmp);
                }
            }

            // save find edge
            else if (type.equals(LINK_FIND)) {
                if (!findEdges.containsKey(idHex)) {
                    findEdges.put(idHex, Collections.singleton(method.toString()));
                } else {
                    Set<String> tmp = new HashSet<>(findEdges.get(idHex));
                    tmp.add(method.toString());
                    findEdges.remove(idHex);
                    findEdges.put(idHex, tmp);
                }
            }
        }

        if (!mapIdNameClass.isEmpty()) {
            log.fine(String.format("T in %s (size: %d): %s",
                    method.getName(), mapIdNameClass.size(),
                    Arrays.toString(mapIdNameClass.toArray())));
        }
    }

    @SuppressWarnings("SpellCheckingInspection")
    public void extractStrings(SootMethod method) {
        /* we need to go backwards to match a string to UI
        * $r11 = staticinvoke <android.text.Html: android.text.Spanned fromHtml(java.lang.String)>
        ("something.");
        *
        virtualinvoke $r8.<android.widget.TextView: void setText(java.lang.CharSequence)>($r11);
        * */
        MethodHelper methodHelper = new MethodHelper(log, method);
        // unlike flowcog, we identify strings in each method instead of taking a
        // complete cfg as input (which increases the search space significantly)
        if (methodHelper.isEmpty()) return;
        for (Unit unit : methodHelper.getUnits()) {
            Stmt s = (Stmt) unit;
            if (s.containsInvokeExpr()) {
                InvokeExpr invokeExpr = s.getInvokeExpr();
                String methodName = invokeExpr.getMethod().getName();
                String SET_TITLE_API = "setTitle";
                String SET_TEXT_API = "setText";
                String SET_HINT_API = "setHint";
                if (methodName.equals(SET_TEXT_API) || methodName.equals(SET_TITLE_API)
                    || methodName.equals(SET_HINT_API)) {
                    log.finer(String.format("get string from: %s (class: %s, method: %s)",
                            s, method.getDeclaringClass(), method.getName()));
                    String ui = methodHelper.getUIIdFromUnit(unit);
                    String xml = methodHelper.getXmlIdFromUnit(unit);
                    if (ui == null) {
                        log.fine("connot get the ui");
                        continue;
                    }
                    if (xml == null) {
                        log.fine("connot get the xml");
                        continue;
                    }
                    if (!ui.chars().allMatch(Character::isDigit)) {
                        // if is a field name, we should turn it to numeric id
                        Set<Tuple2<String, String>> tuple2s =
                                class2IdName.get(methodHelper.getClassName());
                        if (tuple2s == null) {
                            log.fine("tuple is null");
                            continue;
                        }
                        for (Tuple2<String, String> tuple2 : tuple2s) {
                            String id = tuple2._1();
                            String name = tuple2._2();
                            if (ui.equals(name)) {
                                ui = id;
                                break;
                            }
                        }
                    }

                    if (invokeExpr.getMethod().getParameterCount() < 1) {
                        log.fine("no parameter for set text APIs");
                        continue;
                    }
                    // get the string value
                    String text = null;
                    String type = invokeExpr.getMethod().getParameterType(0).toString();
                    Value arg = invokeExpr.getArg(0);
                    if (type.equals("int")) {
                        // string is given by an id
                        String id;
                        if (arg instanceof IntConstant) {
                            id = String.valueOf(((IntConstant) arg).value);
                        } else if (arg instanceof Local) {
                            id = methodHelper.getIdFromUnit(unit, (Local) arg);
                        } else {
                            log.fine("arg is neither an int nor a local var");
                            continue;
                        }
                        text = getStringFromId(id);
                        if (text == null) {
                            log.fine(String.format("unresolved text id: %s", id));
                        }
                    } else if (type.contains("String") || type.contains("Char")) {
                        // string is directly given
                        if (arg instanceof StringConstant) {
                            text = ((StringConstant) arg).value;
                        } else if (arg instanceof Local) {
                            text = methodHelper.getStringFromUnit(unit, (Local) arg);
                        }
                    } else {
                        log.fine(String.format("unknown arg type: %s", type));
                    }
                    if (text != null) {
                        // ui_id@xml: class, method, type, value
                        hardcodeStrings.put(String.format("%s@%s", ui, xml), new Tuple4<>(
                                methodHelper.getClassName(),
                                method.getName(),
                                methodName,
                                text));
                    }
                }
            }
        }
    }

    private String getStringFromId(String id) {
        for (UIContext.ArscObj arscObj : getArscArray()) {
            if (String.valueOf(arscObj.resourceID).equals(id)) {
                return arscObj.value;
            }
        }
        return null;
    }

    private List<UIContext.ArscObj> getArscArray() {
        if (arscArray != null) {
            return arscArray;
        }
        // load arsc from json
        String line;
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(arscJsonPath))) {
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
        } catch (IOException e) {
            log.severe("error when reading arsc json:" + e.getMessage());
        }
        String jsonString = stringBuilder.toString();
        arscArray = JSON.parseArray(jsonString, UIContext.ArscObj.class);
        return arscArray;
    }

    public void run() {
        findEdges = new HashMap<>();
        useEdges = new HashMap<>();
        hardcodeStrings = new HashMap<>();
        class2IdName = new HashMap<>();

        // Searching UI-code links
        for (SootClass sootClass: appClasses) {
            List<SootMethod> sootMethods = sootClass.getMethods();
            List<SootMethod> methods = new ArrayList<>(sootMethods);
            for (SootMethod sootMethod : methods) {
                log.finest(String.format(String.format("method class: %s (%s)",
                        sootMethod.getDeclaringClass().getName(), sootMethod.hasActiveBody())));
                // filter out native API
                if (sootMethod.hasActiveBody() && !sootMethod.isJavaLibraryMethod()) {
                    // isJavaLibraryMethod: java, javax, sun
                    // we do not dive into android api calls either
                    String className = sootMethod.getDeclaringClass().getName();
                    if (className.startsWith("android.") ||
                            className.startsWith("androidx.")) {
                        continue;
                    }
                    // extract R.id and name of UI object
                    getIdNameInMethod(sootMethod);
                    // and extract string values for UIs
                    extractStrings(sootMethod);
                }
            }
        }
    }

    public HashMap<String, Set<String>> getFindEdges() {
        return findEdges;
    }

    public HashMap<String, Set<String>> getUseEdges() {
        return useEdges;
    }

    public Map<String, Set<Tuple2<String, String>>> getThreadEdges() {
        return threadEdges;
    }

    public Map<String, Set<Tuple2<String, String>>> getSwitchEdges() {
        return switchEdges;
    }

    public void parseMethods(
            Map<String, HashMap<String, HashSet<String>>> events) {
        switchEdges = new HashMap<>();
        threadEdges = new HashMap<>();
        for (String xml : events.keySet()) {
            HashMap<String, HashSet<String>> id2Handlers = events.get(xml);
            for (String uid : id2Handlers.keySet()) {
                HashSet<String> handlers = id2Handlers.get(uid);
                for (String handler : handlers) {
                    handler = handler.substring(4, handler.length() - 4);
                    String[] splits = handler.split(": ");
                    String className = splits[0];
                    String methodName = splits[1];
                    SootClass klass = Scene.v().getSootClass(className);
                    if (klass == null) {
                        log.warning(String.format("cannot find soot class: %s", className));
                        continue;
                    }
                    SootMethod method = klass.getMethodUnsafe(methodName);
                    if (method == null) continue;
                    if (!method.hasActiveBody()) continue;
                    Set<Tuple2<String, String>> targetIdNames = class2IdName.get(className);
                    if (targetIdNames == null) {
                        targetIdNames = new HashSet<>();
                    }
                    Set<String> targetIds = new HashSet<>();
                    for (Tuple2<String, String> tin : targetIdNames) {
                        targetIds.add(tin._1());
                    }
                    Body body = method.getActiveBody();
                    UnitPatchingChain units = body.getUnits();
                    List<Unit> caseUnits = new ArrayList<>();
                    Set<String> caseCalls = new HashSet<>();

                    // identify ui-related switch blocks
                    identifySwitch(uid, units, caseUnits);

                    // identify ui-related if blocks
                    identifyIf(uid, units, caseUnits);

                    // add a use edge between a UI and the method which uses the UI
                    if (caseUnits.isEmpty()) {
                        // case 1: the method does not contain switch branches, or contains but no matters with UI
                        for (Unit unit: units) {
                            // handle thread
                            String thread = threadParser(method, unit);
                            if (thread != null) {
                                String uidXml = uid + "$" + xml;
                                Tuple2<String, String> threadEdge = new Tuple2<>(method.getSignature(), thread);
                                if (!threadEdges.containsKey(uidXml)) {
                                    threadEdges.put(uidXml, new HashSet<>(Collections.singleton(threadEdge)));
                                } else {
                                    Set<Tuple2<String, String>> tmp = threadEdges.get(uidXml);
                                    tmp.add(threadEdge);
                                    threadEdges.remove(uidXml);
                                    threadEdges.put(uidXml, tmp);
                                }
                            }
                            // the ui object may appear in both left op (e.g., set attributes)
                            // and right op (e.g., get the object), so we simply detect ui name in string
                            for (Tuple2<String, String> tuple: targetIdNames) {
                                if (unit.toString().contains(String.format(" %s>", tuple._2()))) {
                                    String id = tuple._1();
                                    if (!useEdges.containsKey(id)) {
                                        useEdges.put(id, new HashSet<>(Collections.singleton(method.getSignature())));
                                    } else {
                                        Set<String> tmp = useEdges.get(id);
                                        tmp.add(method.getSignature());
                                        useEdges.remove(id);
                                        useEdges.put(id, tmp);
                                    }
                                }
                            }
                        }
                    } else {
                        // case 2: the method contains switch branches, and the branches is related to UI
                        for (Unit u : caseUnits) {
                            // fill in case-call dict
                            Set<String> calls = new HashSet<>();
                            if (!((Stmt) u).containsInvokeExpr()) continue;
                            calls.add(((Stmt) u).getInvokeExpr().toString());
                            String thread = threadParser(method, u);
                            if (thread != null) {
                                String uidXml = uid + "$" + xml;
                                Tuple2<String, String> threadEdge = new Tuple2<>(method.getSignature(), thread);
                                if (!threadEdges.containsKey(uidXml)) {
                                    threadEdges.put(uidXml, new HashSet<>(Collections.singleton(threadEdge)));
                                } else {
                                    Set<Tuple2<String, String>> tmp = threadEdges.get(uidXml);
                                    tmp.add(threadEdge);
                                    threadEdges.remove(uidXml);
                                    threadEdges.put(uidXml, tmp);
                                }
                            }
                            caseCalls.addAll(calls);
                            // add use edges
                            if (targetIds.contains(uid)) {
                                if (!useEdges.containsKey(uid)) {
                                    useEdges.put(uid, new HashSet<>(Collections.singleton(method.getSignature())));
                                } else {
                                    Set<String> tmp = useEdges.get(uid);
                                    tmp.add(method.getSignature());
                                    useEdges.remove(uid);
                                    useEdges.put(uid, tmp);
                                }
                            }
                        }
                        // second, note down all the calls in each branches
                        for (String call : caseCalls) {
                            String uidXml = uid + "$" + xml;
                            Tuple2<String, String> callEdge = new Tuple2<>(method.getSignature(), call);
                            if (!switchEdges.containsKey(uidXml)) {
                                switchEdges.put(uidXml, new HashSet<>(Collections.singleton(callEdge)));
                            } else {
                                Set<Tuple2<String, String>> tmp = switchEdges.get(uidXml);
                                tmp.add(callEdge);
                                switchEdges.remove(uidXml);
                                switchEdges.put(uidXml, tmp);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void identifyIf(String uid, UnitPatchingChain units, List<Unit> caseUnits) {
        ArrayList<Unit> targets = new ArrayList<>();
        // first, identify all targets
        for (Unit unit : units) {
            Stmt s = (Stmt) unit;
            if (!(s instanceof IfStmt)) continue;
            targets.add(((IfStmt) s).getTarget());
        }

        for (Unit unit : units) {
            Stmt s = (Stmt) unit;
            if (!(s instanceof IfStmt)) continue;
            if(!(s.toString().contains(uid))) continue;
            IfStmt ifs = (IfStmt) s;
            Unit target = ifs.getTarget();
            List<Unit> ifBlock = new ArrayList<>();
            ifBlock.add(target);
            log.finest("start target: " + target.toString());
            Unit next = units.getSuccOf(target);
            if (next != null) {
                target = next;
            }

            // exit when no succeed call or enter other cases
            while (!targets.contains(target)) {
                if (target == null) {
                    break;
                }
                log.finer(target.toString());
                ifBlock.add(target);
                target = units.getSuccOf(target);
            }

            // fill in the cases with units.
            // note: there may be many switches with same lookup values (case ids) in one method
            // hence we merge them together, make them indexed via case id
            caseUnits.addAll(ifBlock);
        }
    }

    private static void identifySwitch(String uid, UnitPatchingChain units, List<Unit> caseUnits) {
        for (Unit unit : units) {
            Stmt s = (Stmt) unit;
            // id is usually not continuous, so we do not consider table switch
            if (s instanceof JLookupSwitchStmt) {
                List<IntConstant> lookupValues = ((JLookupSwitchStmt) s).getLookupValues();
                boolean hasUId = false;
                for (IntConstant vh: lookupValues) {
                    if (vh.toString().equals(uid)) {
                        hasUId = true;
                        break;
                    }
                }

                if (!hasUId) {
                    // normal switch cases, not related to specific UIs (e.g., 0, 1, 2...)
                    return;
                }

                ArrayList<Unit> targets = new ArrayList<>();

                // we include all the calls in the case block
                // hence, first note down the start stats of other cases
                for (int i = 0; i < lookupValues.size(); i++) {
                    targets.add(((JLookupSwitchStmt) s).getTarget(i));
                }

                for (int i = 0; i < lookupValues.size(); i++) {
                    IntConstant lookupValue = lookupValues.get(i);
                    if (!(lookupValue.toString().equals(uid))) continue;
                    Unit target = ((JLookupSwitchStmt) s).getTarget(i);
                    List<Unit> switchBlock = new ArrayList<>();
                    switchBlock.add(target);
                    log.finest("start target: " + target.toString());
                    Unit next = units.getSuccOf(target);
                    if (next != null) {
                        target = next;
                    }

                    // exit when no succeed call or enter other cases
                    while (!targets.contains(target)) {
                        if (target == null) {
                            break;
                        }
                        log.finer(target.toString());
                        switchBlock.add(target);
                        target = units.getSuccOf(target);
                    }

                    // fill in the cases with units.
                    // note: there may be many switches with same lookup values (case ids) in one method
                    // hence we merge them together, make them indexed via case id
                    caseUnits.addAll(switchBlock);
                }
            }
        }
    }

    public Map<String, Tuple4<String, String, String, String>> getHardcodeStrings() {
        return hardcodeStrings;
    }

    public String threadParser(SootMethod method, Unit unit) {
        Stmt s = (Stmt) unit;
        if (!(s.containsInvokeExpr())) return null;
        InvokeExpr ie = s.getInvokeExpr();
        SootMethod callee = ie.getMethod();
        String subSig = callee.getSubSignature();
        if (!(subSig.startsWith(METHOD_START_PREFIX))) return null;
        Value thread = ie.getUseBoxes().get(0).getValue();
        UnitPatchingChain units = method.getActiveBody().getUnits();
        // case 1: <class xx: start()>
        Unit last = units.getPredOf(unit);
        while (last != null) {
            Stmt ss = (Stmt) last;
            if (ss instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) ss;
                if (as.getLeftOp() == thread) {
                    Value rightOp = as.getRightOp();
                    if (rightOp instanceof NewExpr) {
                        NewExpr newExpr = (NewExpr) rightOp;
                        String className = newExpr.getBaseType().toString();
                        if (!Objects.equals(className, CLASS_THREAD)) {
                            String result = String.format("&lt;%s: void run()&gt;", className);
                            log.fine(String.format("[thread] %s", result));
                            return result;
                        }
                        break;
                    }
                }
            }
            last = units.getPredOf(last);
        }
        // case 2: <Thread: start()> followed by <Thread: void <init>(java.lang.Runnable)>(xx)
        last = units.getPredOf(unit);
        while (last != null) {
            Stmt ss = (Stmt) last;
            if (ss.containsInvokeExpr()) {
                InvokeExpr as = ss.getInvokeExpr();
                if (as.getUseBoxes().get(0).getValue() == thread) {
                    if (as.getMethod().getSignature().startsWith(THREAD_INIT_PREFIX)) {
                        int i = 0;
                        for (Type type : as.getMethodRef().getParameterTypes()) {
                            if (Objects.equals(type.toString(), CLASS_RUNNABLE)) {
                                break;
                            }
                            i += 1;
                        }
                        Value arg = as.getArgs().get(i);
                        String className = arg.getType().toString();
                        String result = String.format("&lt;%s: void run()&gt;", className);
                        log.fine(String.format("[thread] %s", result));
                        return result;
                    }
                }
            }
            last = units.getPredOf(last);
        }
        return null;
    }
}
