import soot.*;
import soot.jimple.*;
import java.util.logging.Logger;

public class MethodHelper {
    private final Logger log;
    private final SootMethod method;
    private UnitPatchingChain units;
    private Value layout;
    public MethodHelper(Logger log, SootMethod sootMethod) {
        this.method = sootMethod;
        this.log = log;

        if (sootMethod.hasActiveBody()) {
            Body body = method.getActiveBody();
            if (body != null) {
                units = body.getUnits();
            }
        }
    }

    public boolean isEmpty() {
        return units == null;
    }

    public UnitPatchingChain getUnits() {
        return units;
    }

    public String getXmlIdFromUnit(Unit unit) {
        if (isEmpty()) return null;
        if (layout == null) {
            log.fine("layout val is null.");
            return null;
        }
        Unit last = units.getPredOf(unit);
        while (last != null) {
            // virtualinvoke $r2.<android.app.Dialog: void setContentView(int)>(xxx);
            Stmt s = (Stmt) last;
            if (s instanceof InvokeStmt) {
                InvokeStmt invokeStmt = (InvokeStmt) s;
                SootMethod invokeMethod = invokeStmt.getInvokeExpr().getMethod();
                if (invokeMethod.getName().contains("setContentView")) {
                    if (invokeStmt.getUseBoxes().get(0).getValue() == layout) {
                        Value var = invokeStmt.getInvokeExpr().getArg(0);
                        if (var instanceof IntConstant) {
                            return var.toString();
                        } else {
                            log.fine(String.format("unknown setContentView arg type: %s", var.getType()));
                            return null;
                        }
                    }
                }
            }
            last = units.getPredOf(last);
        }
        return null;
    }

    public String getStringFromUnit(Unit unit, Local val) {
        if (isEmpty()) return null;
        Unit last = units.getPredOf(unit);
        while (last != null) {
            Stmt s = (Stmt) last;
            if (s instanceof AssignStmt) {
                AssignStmt assignStmt = (AssignStmt) s;
                if (assignStmt.getLeftOp() == val) {
                    log.finest("[get str]: "+ s);
                    Value rightOp = assignStmt.getRightOp();
                    if (rightOp instanceof StringConstant) {
                        return ((StringConstant) rightOp).value;
                    } else if (rightOp instanceof Local) {
                        return getStringFromUnit(last, (Local) rightOp);
                    } else if (rightOp instanceof InvokeExpr) {
                        InvokeExpr invokeExprRight = ((InvokeExpr) rightOp);
                        SootMethod m = invokeExprRight.getMethod();
                        if (m.getName().contains("getString") && m.getParameterCount() == 2) {
                            // SharedPreferences: getString("key", "default")
                            return String.format("[p] %s %s",
                                    invokeExprRight.getArg(0), invokeExprRight.getArg(1));
                        }
                        Value arg = invokeExprRight.getArg(0);
                        if (arg instanceof StringConstant) {
                            return ((StringConstant) arg).value;
                        } else if (arg instanceof Local) {
                            return getStringFromUnit(last, (Local) arg);
                        }
                    } else {
                        log.fine(String.format("[get str] unknown right op: %s", rightOp.getClass()));
                        return null;
                    }
                }
            }
            last = units.getPredOf(last);
        }
        return null;
    }

    public String getUIIdFromUnit(Unit unit) {
        if (isEmpty()) return null;
        layout = null;
        Stmt s = (Stmt) unit;
        // first, we check whether the ui is specified
        // by a class field, if so, return the field name
        InvokeExpr invokeExpr = s.getInvokeExpr();
        Value value = invokeExpr.getUseBoxes().get(0).getValue();
        if (value instanceof FieldRef) {
            return value.toString();
        }
        // if not, search in local vars
        Unit last = units.getPredOf(unit);
        while (last != null) {
            s = (Stmt) last;
            if (s instanceof AssignStmt) {
                AssignStmt assignStmt = (AssignStmt) s;
                if (assignStmt.getLeftOp() == value) {
                    Value rightOp = assignStmt.getRightOp();
                    if (rightOp instanceof CastExpr) {
                        value = ((CastExpr) rightOp).getOp();
                    } else if (s.toString().contains("findViewById")) {
                        // $r1 = virtualinvoke $r2.<android.app.Dialog: android.view.View findViewById(int)>(xxx);
                        layout = rightOp.getUseBoxes().get(0).getValue();
                        return s.getInvokeExpr().getArg(0).toString();
                    }
                }
            }
            last = units.getPredOf(last);
        }
        return null;
    }

    @SuppressWarnings("All")
    public String getIdFromUnit(Unit unit, Local val) {
        // TODO
        log.warning("not implemented: get str id from local var");
        return null;
    }

    public String getClassName() {
        return method.getDeclaringClass().toString();
    }
}
