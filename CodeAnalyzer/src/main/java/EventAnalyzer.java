import soot.*;
import soot.jimple.*;
import soot.tagkit.ConstantValueTag;
import soot.tagkit.Tag;
import soot.toolkits.scalar.Pair;

import java.util.*;
import java.util.logging.Logger;

public class EventAnalyzer {
    // event register and callback, add more if needed
    static Map<String, String> reg2handler;
    static {
        reg2handler = new HashMap<>();
        reg2handler.put("void setOnClickListener(android.view.View$OnClickListener)",
                "void onClick(android.view.View)");
        reg2handler.put("void setOnLongClickListener(android.view.View$OnLongClickListener)",
                "boolean onLongClick(android.view.View)");
        reg2handler.put("void setOnTouchListener(android.view.View$OnTouchListener)",
                "boolean onTouch(android.view.View,android.view.MotionEvent)");
        reg2handler.put("void setOnDragListener(android.view.View$OnDragListener)",
                "boolean onDrag(android.view.View,android.view.DragEvent)");
        reg2handler.put("void setOnFocusChangeListener(android.view.View$OnFocusChangeListener)",
                "void onFocusChange(android.view.View,boolean)");
        reg2handler.put("void setOnKeyListener(android.view.View$OnKeyListener)",
                "boolean onKey(android.view.View,int,android.view.KeyEvent)");
    }
    static String CALL_INFLATE_1 = "android.view.View inflate(int,android.view.ViewGroup,boolean)";
    static String CALL_INFLATE_2 = "android.view.View inflate(int,android.view.ViewGroup)";
    static String CALL_SET_CONTENT_VIEW = "void setContentView(int)";
    static String CLASS_DIALOG = "android.app.Dialog";
    static String CLASS_ACTIVITY = "android.app.Activity";
    static String CALL_FIND_VIEW_BY_ID = "android.view.View findViewById(int)";

    Logger logger;
    /* ------------------
       xml_1: { uid_1: [e1, e2, ...], uid_2: ...},
       xml_2: { uid_3: [e3, e4, ...], uid_4: ...},
    ------------------- */
    Map<String, HashMap<String, HashSet<String>>> results;
    Map<SootClass, String> classToXML;
    Set<SootClass> activityClasses;
    Set<SootClass> dialogClasses;
    // view/inflate can be a field of a class instead of a local value of a method
    // we save <field, layoutId> for inflate, and <field, viewID> for view
    Map<SootClass, HashMap<SootField, String>> classFieldsView;
    Map<SootClass, HashMap<SootField, String>> classFieldsInflate;
    Map<SootMethod, HashSet<Unit>> allEvents;
    String apk;
    Map<String, Set<String>> layoutToViews;

    public EventAnalyzer(String apk, Logger logger, Map<String, Set<String>> layoutViewMap) {
        this.logger = logger;
        this.apk = apk;
        layoutToViews = layoutViewMap;
        results = new HashMap<>();
        classToXML = new HashMap<>();
        activityClasses = new HashSet<>();
        dialogClasses = new HashSet<>();
        classFieldsView = new HashMap<>();
        classFieldsInflate = new HashMap<>();
        allEvents  = new HashMap<>();
    }

    public void run() {
        // find all subclasses of activity
        getLayoutClasses();
        // round 1, search event invoke, layout xml, and field (view/inflate)
        identifyInfo();
        // round 2, deal with set event listeners
        resolveEvents();
    }

    private void resolveEvents() {
        logger.info(String.format("resolve events in %s methods", allEvents.size()));
        int regCount = 0;
        float e = 0;
        float u = 0;
        int success = 0;

        // method contains a set listener register
        for (Map.Entry<SootMethod, HashSet<Unit>> entry : allEvents.entrySet()) {
            SootMethod method = entry.getKey();
            HashSet<Unit> regUnits = entry.getValue();
            regCount += regUnits.size();
            for (Unit regUnit : regUnits) {
                // 1. get event callback name
                String event = getEvent(method, regUnit);
                if (Objects.equals(event, "null")) continue;
                if (event == null) continue;
                e ++;
                // 2. get id and layout of the view
                Pair<String, String> view = getView(method, regUnit);
                if (view != null) {
                    u ++;
                    String uid = view.getO1();
                    String xml = view.getO2();
                    if (xml == null) xml = "NO_ID";

                    // currently we do not parse dialogs
                    if (Objects.equals(xml, CLASS_DIALOG)) continue;
                    HashMap<String, HashSet<String>> map;
                    if (results.containsKey(xml)) {
                        map = results.get(xml);
                    } else {
                        map = new HashMap<>();
                    }
                    HashSet<String> events;
                    if (map.containsKey(uid)) {
                        events = map.get(uid);
                    } else {
                        events = new HashSet<>();
                    }
                    events.add(event);
                    map.put(uid, events);
                    results.put(xml, map);
                    success ++;
                }
                else {
                    if (logger.getLevel().intValue() < 600) {
                        printError(method.getActiveBody().getUnits(),
                                String.format("method: %s, reg: %s", method.getSignature(), regUnit));
                    }
                }
            }
        }
        logger.info(String.format("get %d events (ui: %.2f, event: %.2f)", success, u / regCount, e / regCount));
    }

    private String getEvent(SootMethod method, Unit eventUnit) {
        UnitPatchingChain units = method.getActiveBody().getUnits();
        InvokeExpr invoke = ((Stmt) eventUnit).getInvokeExpr();
        Value listener = invoke.getArg(0);
        String subSig = invoke.getMethod().getSubSignature();
        if (listener instanceof NullConstant) {
            return "null";
        } else {
            // setListener(this)
            String className = listener.getType().toString();
            SootClass klass = Scene.v().getSootClass(className);
            if (klass == method.getDeclaringClass()) {
                String handler = reg2handler.get(subSig);
                return String.format("&lt;%s: %s&gt;", className, handler);
            }
        }
        Unit last = units.getPredOf(eventUnit);
        while (last != null) {
            Stmt s = (Stmt) last;
            if (s instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) s;
                if (as.getLeftOp() == listener) {
                    Value rightOp = as.getRightOp();
                    if (rightOp instanceof NewExpr) {
                        NewExpr newExpr = (NewExpr) rightOp;
                        String className = newExpr.getBaseType().toString();
                        String handler = reg2handler.get(subSig);
                        return String.format("&lt;%s: %s&gt;", className, handler);
                    }
                }
            }
            last = units.getPredOf(last);
        }
        return null;
    }

    private String getXML(SootMethod method, Unit unit) {
        UnitPatchingChain units = method.getActiveBody().getUnits();
        Stmt stmt = (Stmt) unit;
        Value xml = stmt.getInvokeExpr().getUseBoxes().get(0).getValue();
        Unit last = units.getPredOf(unit);
        while (last != null) {
            Stmt s = (Stmt) last;
            last = units.getPredOf(last);
            if (!(s instanceof AssignStmt)) continue;
            AssignStmt as = (AssignStmt) s;
            if (as.getLeftOp() != xml) continue;
            // two cases, one is ui is a field, the other is a local
            xml = as.getRightOp();
            if (xml instanceof FieldRef) {
                SootField field = ((FieldRef) xml).getField();
                SootClass klass = method.getDeclaringClass();
                HashMap<SootField, String> map2 = classFieldsInflate.get(klass);
                if (map2 != null) {
                    String str = map2.get(field);
                    if (str != null) return str;
                }
                Type fieldType = field.getType();
                String className = fieldType.toString();
                SootClass fieldClass = Scene.v().getSootClass(className);
                if (activityClasses.contains(fieldClass)) {
                    return className;
                }
                if (dialogClasses.contains(fieldClass) ||
                        Objects.equals(className, CLASS_DIALOG)) {
                    return CLASS_DIALOG;
                }
                logger.fine(String.format("[get xml] unresolved field %s", field));
                return null;
            } else if (xml instanceof InvokeExpr) {
                InvokeExpr ie = (InvokeExpr) xml;
                SootMethod callee = ie.getMethod();
                String subSig = callee.getSubSignature();
                if (Objects.equals(subSig, CALL_INFLATE_1) || Objects.equals(subSig, CALL_INFLATE_2)) {
                    return getIdFromInvokeUnit(s, units);
                }
            }
        }
        return null;
    }

    private void identifyInfo() {
        for (SootClass klass : Scene.v().getApplicationClasses()) {
            // we do not analysis android sdk uis
            if (klass.getName().startsWith("androidx.")) continue;
            if (klass.getName().startsWith("android.support.")) continue;
            if (klass.getName().startsWith("com.google.")) continue;
            List<SootMethod> methods = klass.getMethods();
            for (SootMethod method : methods) {
                if (!(method.hasActiveBody())) continue;
                Body body = method.getActiveBody();
                for (Unit unit: body.getUnits()) {
                    Stmt s = (Stmt) unit;
                    if (!(s.containsInvokeExpr())) continue;
                    InvokeExpr ie = s.getInvokeExpr();
                    SootMethod callee = ie.getMethod();
                    String subSig = callee.getSubSignature();
                    if (reg2handler.containsKey(subSig)) {
                        HashSet<Unit> events;
                        if (allEvents.containsKey(method)) {
                            events = allEvents.get(method);
                        } else {
                            events = new HashSet<>();
                        }
                        events.add(unit);
                        allEvents.put(method, events);
                    } else if (Objects.equals(subSig, CALL_SET_CONTENT_VIEW)) {
                        String xml = handleSetContentView(method, unit);
                        classToXML.put(klass, xml);
                        logger.fine(String.format("[setContentView] %s - %s", xml, klass.getName()));
                    } else if (Objects.equals(subSig, CALL_FIND_VIEW_BY_ID)) {
                        handleFindViewByID(method, unit);
                    } else if (Objects.equals(subSig, CALL_INFLATE_1)
                            || Objects.equals(subSig, CALL_INFLATE_2)) {
                        handleInflate(method, unit);
                    }
                }
            }
        }
    }

    private void handleInflate(SootMethod method, Unit unit) {
        if (!(unit instanceof AssignStmt)) return;
        AssignStmt assignStmt = (AssignStmt) unit;
        /*
         $r3 = virtualinvoke $r2.<android.view.LayoutInflater:
               android.view.View inflate(int,android.view.ViewGroup)>(0x7f0a0000, null);
         r0.<com.example.myapp.ActivityX: android.view.View b> = $r3;
        */
        Value inflation = assignStmt.getLeftOp();
        UnitPatchingChain units = method.getActiveBody().getUnits();
        // get layout id
        String layoutIdLiteral = getIdFromInvokeUnit(unit, units);
        // get inflate field
        if (layoutIdLiteral != null) {
            Unit next = units.getSuccOf(unit);
            while (next != null) {
                Stmt s = (Stmt) next;
                if (s instanceof AssignStmt) {
                    AssignStmt s1 = (AssignStmt) s;
                    if (s1.getRightOp() == inflation) {
                        Value leftOp = s1.getLeftOp();
                        if (leftOp instanceof FieldRef) {
                            SootField field = ((FieldRef) leftOp).getField();
                            SootClass klass = method.getDeclaringClass();
                            HashMap<SootField, String> map = classFieldsInflate.get(klass);
                            if (map == null) {
                                map = new HashMap<>();
                            }
                            map.put(field, layoutIdLiteral);
                            classFieldsInflate.put(klass, map);
                            logger.fine(String.format("[handle inf] %s: %s", field, layoutIdLiteral));
                            break;
                        }
                    }
                }
                next = units.getSuccOf(next);
            }
        }
    }

    private String getIdFromInvokeUnit(Unit unit, UnitPatchingChain units) {
        InvokeExpr invokeExpr = ((Stmt) unit).getInvokeExpr();
        String idLiteral = null;
        Value layoutId = invokeExpr.getArg(0);
        if (layoutId instanceof IntConstant) {
            idLiteral = String.valueOf(((IntConstant) layoutId).value);
        } else if (layoutId instanceof Local) {
            // backward search
            Unit last = units.getPredOf(unit);
            while (last != null) {
                Stmt s = (Stmt) last;
                if (s instanceof AssignStmt) {
                    AssignStmt as = (AssignStmt) s;
                    if (as.getLeftOp() == layoutId) {
                        Value rightOp = as.getRightOp();
                        if (rightOp instanceof FieldRef) {
                            SootField field = ((FieldRef) rightOp).getField();
                            // try to get the literal value from soot field object
                            for (Tag tag : field.getTags()) {
                                if (tag instanceof ConstantValueTag) {
                                    idLiteral = ((ConstantValueTag) tag).getConstant().toString();
                                    break;
                                }
                            }
                            if (idLiteral != null) break;
                            // is assigned at runtime (e.g., an arg of <clinit> -> local)
                            logger.fine(String.format("cannot locate constant value for field: %s", field));
                            break;
                        } else if (rightOp instanceof IntConstant) {
                            idLiteral = String.valueOf(((IntConstant) rightOp).value);
                            break;
                        } else {
                            printError(units, String.format("unknown right op type: %s", rightOp.getClass()));
                            break;
                        }
                    }
                }
                last = units.getPredOf(last);
            }
        }
        if (idLiteral == null) {
            printError(units, String.format("uid is null (in: %s)", unit));
        }
        return idLiteral;
    }

    private void handleFindViewByID(SootMethod method, Unit unit) {
        Stmt stmt = (Stmt) unit;
        if (!(stmt instanceof AssignStmt)) return;
        Value var = ((AssignStmt) stmt).getLeftOp();
        UnitPatchingChain units = method.getActiveBody().getUnits();
        // first, get uid
        String viewIdLiteral = getIdFromInvokeUnit(unit, units);
        // then, get field
        Unit next = units.getSuccOf(unit);
        while (next != null) {
            Stmt s = (Stmt) next;
            if (s instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) s;
                Value rightOp = as.getRightOp();
                Value leftOp = as.getLeftOp();
                if (rightOp instanceof CastExpr) {
                    if (((CastExpr) rightOp).getOp() == var) {
                        var = leftOp;
                    }
                } else if (rightOp instanceof Local) {
                    if (rightOp == var) {
                        if (leftOp instanceof FieldRef) {
                            SootField field = ((FieldRef) leftOp).getField();
                            SootClass klass = method.getDeclaringClass();
                            HashMap<SootField, String> map = classFieldsView.get(klass);
                            if (map == null) {
                                map = new HashMap<>();
                            }
                            map.put(field, viewIdLiteral);
                            classFieldsView.put(klass, map);
                            logger.fine(String.format("[handle find] %s: %s", field, viewIdLiteral));
                            break;
                        } else if (leftOp instanceof Local) {
                            var = leftOp;
                        }
                    }
                }
            }
            next = units.getSuccOf(next);
        }
    }

    private Pair<String, String> getView(SootMethod method, Unit unit) {
        String viewLiteral = null;
        String layoutLiteral = null;
        Stmt stmt = (Stmt) unit;
        InvokeExpr invokeExpr = stmt.getInvokeExpr();
        Value ui = invokeExpr.getUseBoxes().get(0).getValue();
        UnitPatchingChain units = method.getActiveBody().getUnits();
        Unit callSite = null;
        Unit last = units.getPredOf(unit);
        while (last != null) {
            Stmt s = (Stmt) last;
            if (s instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) s;
                if (as.getLeftOp() == ui) {
                    Value rightOp = as.getRightOp();
                    if (rightOp instanceof CastExpr) {
                        ui = ((CastExpr) rightOp).getOp();
                    } else if (rightOp instanceof FieldRef) {
                        SootField field = ((FieldRef) rightOp).getField();
                        SootClass klass = method.getDeclaringClass();
                        HashMap<SootField, String> map = classFieldsView.get(klass);
                        if (map != null) {
                            String str = map.get(field);
                            if (str != null) {
                                viewLiteral = str;
                                break;
                            }
                        }
                        for (Tag tag : field.getTags()) {
                            if (tag instanceof ConstantValueTag) {
                                viewLiteral = ((ConstantValueTag) tag).getConstant().toString();
                                break;
                            }
                        }
                    } else if (rightOp instanceof IntConstant) {
                        viewLiteral = String.valueOf(((IntConstant) rightOp).value);
                        break;
                    } else if (rightOp instanceof InvokeExpr) {
                        InvokeExpr expr = ((InvokeExpr) rightOp);
                        String subSignature = expr.getMethod().getSubSignature();
                        if (!Objects.equals(subSignature, CALL_FIND_VIEW_BY_ID)) {
                            logger.fine(String.format("[get view] unknown call: %s (in %s)",
                                    rightOp.getClass(), s));
                            return null;
                        }
                        ui = expr.getArg(0);
                        callSite = last;
                        if (ui instanceof IntConstant) {
                            viewLiteral = String.valueOf(((IntConstant) ui).value);
                            break;
                        }
                    } else if (rightOp instanceof Local) {
                        ui = rightOp;
                    } else {
                        printError(units, String.format("[get view] unknown right op type %s (in %s)",
                                rightOp.getClass(), s));
                        break;
                    }
                }
            }
            last = units.getPredOf(last);
        }
        if (callSite != null) {
            layoutLiteral = getXML(method, callSite);
        }
        // if we cannot get layout id (e.g., from inflate)
        // then use the layout id from set content view()
        if (viewLiteral != null) {
            if (layoutLiteral == null) {
                layoutLiteral = classToXML.get(method.getDeclaringClass());
            }
            return new Pair<>(viewLiteral, layoutLiteral);
        }
        return null;
    }

    private String handleSetContentView(SootMethod method, Unit unit) {
        UnitPatchingChain units = method.getActiveBody().getUnits();
        Stmt s = (Stmt) unit;
        // first, we check whether the ui is specified
        // by a class field, if so, return the field name
        InvokeExpr invokeExpr = s.getInvokeExpr();
        Value value = invokeExpr.getArg(0);
        if (value instanceof FieldRef) {
            SootField field = ((FieldRef) value).getField();
            // try to get the literal value from soot field object
            for (Tag tag : field.getTags()) {
                if (tag instanceof ConstantValueTag) {
                    return ((ConstantValueTag) tag).getConstant().toString();
                }
            }
            printError(units, "cannot handle field var for SetContentView");
        } else if (value instanceof IntConstant) {
            return value.toString();
        } else {
            printError(units, "cannot handle local var for SetContentView");
        }
        return null;
    }

    private void getLayoutClasses() {
        logger.finer("get layout classes");
        SootClass activityClass = Scene.v().getSootClass(CLASS_ACTIVITY);
        SootClass dialogClass = Scene.v().getSootClass(CLASS_DIALOG);
        if (activityClass == null) {
            throw new RuntimeException("cannot find activity class");
        }
        for (SootClass klass : Scene.v().getApplicationClasses()) {
            SootClass currentClass = klass;
            while (currentClass.hasSuperclass()) {
                currentClass = currentClass.getSuperclass();
                if (currentClass == activityClass) {
                    activityClasses.add(klass);
                    break;
                } else if (currentClass == dialogClass) {
                    dialogClasses.add(klass);
                    break;
                }
            }
        }
    }

    public Map<String, HashMap<String, HashSet<String>>> getResults() {
        return results;
    }

    private void printError(UnitPatchingChain units, String msg) {
        String bar = "-------------------------";
        System.out.println(bar);
        System.out.println(msg);
        System.out.println(bar);
        for (Unit unit : units) {
            System.out.println(unit);
        }
    }
}
