import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.io.IOUtils;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.MethodImplementation;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.util.DexUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class OpCodeExtractor {
    String apk;
    String opCodePath;
    JSONObject object;

    public OpCodeExtractor(String apk, String opCodePath) {
        this.apk = apk;
        this.opCodePath = opCodePath;
        this.object = new JSONObject();
    }
    public void run() throws IOException {
        ArrayList<ZipEntry> dexEntries = new ArrayList<>();
        try(ZipFile zip = new ZipFile(apk)) {
            Enumeration<?> entries = zip.entries();
            // identify dex files
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                try (InputStream inputStream = new BufferedInputStream(zip.getInputStream(entry))) {
                    try {
                        DexUtil.verifyDexHeader(inputStream);
                        dexEntries.add(entry);
                    } catch (DexBackedDexFile.NotADexFile | DexUtil.InvalidFile
                             | DexUtil.UnsupportedFile ignored) {
                    }
                }
            }

            // parse dex file one by one
            for (ZipEntry dexEntry : dexEntries) {
                InputStream inputStream = zip.getInputStream(dexEntry);
                File dexTempFile = File.createTempFile(dexEntry.getName(), ".tmp");
                try(OutputStream outputStream = Files.newOutputStream(dexTempFile.toPath())){
                    IOUtils.copy(inputStream, outputStream);
                } catch (IOException ignored1) {
                    boolean ignored2 = dexTempFile.delete();
                }
                DexFile dexFile = DexFileFactory.loadDexFile(dexTempFile, Opcodes.getDefault());
                handleDexFile(dexFile);
                boolean ignored3 = dexTempFile.delete();
            }

            // write results
            Path path = Paths.get(this.apk);
            String apkName = path.getFileName().toString();
            if (apkName.endsWith(".apk")) {
                apkName = apkName.substring(0, apkName.length() - 4);
            }
            String fileName = Paths.get(this.opCodePath, apkName) + ".json";
            String results = object.toJSONString();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
                writer.write(results);
            }
        }
    }

    private void handleDexFile(DexFile dexFile) {
        Set<? extends ClassDef> classes = dexFile.getClasses();
        System.out.printf("found %d classes%n", classes.size());
        for (ClassDef classDef : classes) {
            String klassKey = classDef.toString();
            JSONObject classObj = new JSONObject();
            for (Method method : classDef.getMethods()) {
                String methodKey = method.toString().split("->")[1];
                MethodImplementation implementation = method.getImplementation();
                if (implementation == null) continue;
                Iterable<? extends Instruction> instructions = implementation.getInstructions();
                ArrayList<Integer> opCodes = new ArrayList<>();
                for (Instruction instruction : instructions) {
                    opCodes.add(instruction.getOpcode().ordinal());
                }
                classObj.put(methodKey, opCodes);
            }
            object.put(klassKey, classObj);
        }
    }

    @SuppressWarnings({"unused", "SpellCheckingInspection"})
    public enum OpCode {
        NOP,
        MOVE,
        MOVE_FROM16,
        MOVE_16,
        MOVE_WIDE,
        MOVE_WIDE_FROM16,
        MOVE_WIDE_16,
        MOVE_OBJECT,
        MOVE_OBJECT_FROM16,
        MOVE_OBJECT_16,
        MOVE_RESULT,
        MOVE_RESULT_WIDE,
        MOVE_RESULT_OBJECT,
        MOVE_EXCEPTION,
        RETURN_VOID,
        RETURN,
        RETURN_WIDE,
        RETURN_OBJECT,
        CONST_4,
        CONST_16,
        CONST,
        CONST_HIGH16,
        CONST_WIDE_16,
        CONST_WIDE_32,
        CONST_WIDE,
        CONST_WIDE_HIGH16,
        CONST_STRING,
        CONST_STRING_JUMBO,
        CONST_CLASS,
        MONITOR_ENTER,
        MONITOR_EXIT,
        CHECK_CAST,
        INSTANCE_OF,
        ARRAY_LENGTH,
        NEW_INSTANCE,
        NEW_ARRAY,
        FILLED_NEW_ARRAY,
        FILLED_NEW_ARRAY_RANGE,
        FILL_ARRAY_DATA,
        THROW,
        GOTO,
        GOTO_16,
        GOTO_32,
        PACKED_SWITCH,
        SPARSE_SWITCH,
        CMPL_FLOAT,
        CMPG_FLOAT,
        CMPL_DOUBLE,
        CMPG_DOUBLE,
        CMP_LONG,
        IF_EQ,
        IF_NE,
        IF_LT,
        IF_GE,
        IF_GT,
        IF_LE,
        IF_EQZ,
        IF_NEZ,
        IF_LTZ,
        IF_GEZ,
        IF_GTZ,
        IF_LEZ,
        AGET,
        AGET_WIDE,
        AGET_OBJECT,
        AGET_BOOLEAN,
        AGET_BYTE,
        AGET_CHAR,
        AGET_SHORT,
        APUT,
        APUT_WIDE,
        APUT_OBJECT,
        APUT_BOOLEAN,
        APUT_BYTE,
        APUT_CHAR,
        APUT_SHORT,
        IGET,
        IGET_WIDE,
        IGET_OBJECT,
        IGET_BOOLEAN,
        IGET_BYTE,
        IGET_CHAR,
        IGET_SHORT,
        IPUT,
        IPUT_WIDE,
        IPUT_OBJECT,
        IPUT_BOOLEAN,
        IPUT_BYTE,
        IPUT_CHAR,
        IPUT_SHORT,
        SGET,
        SGET_WIDE,
        SGET_OBJECT,
        SGET_BOOLEAN,
        SGET_BYTE,
        SGET_CHAR,
        SGET_SHORT,
        SPUT,
        SPUT_WIDE,
        SPUT_OBJECT,
        SPUT_BOOLEAN,
        SPUT_BYTE,
        SPUT_CHAR,
        SPUT_SHORT,
        INVOKE_VIRTUAL,
        INVOKE_SUPER,
        INVOKE_DIRECT,
        INVOKE_STATIC,
        INVOKE_INTERFACE,
        INVOKE_VIRTUAL_RANGE,
        INVOKE_SUPER_RANGE,
        INVOKE_DIRECT_RANGE,
        INVOKE_STATIC_RANGE,
        INVOKE_INTERFACE_RANGE,
        NEG_INT,
        NOT_INT,
        NEG_LONG,
        NOT_LONG,
        NEG_FLOAT,
        NEG_DOUBLE,
        INT_TO_LONG,
        INT_TO_FLOAT,
        INT_TO_DOUBLE,
        LONG_TO_INT,
        LONG_TO_FLOAT,
        LONG_TO_DOUBLE,
        FLOAT_TO_INT,
        FLOAT_TO_LONG,
        FLOAT_TO_DOUBLE,
        DOUBLE_TO_INT,
        DOUBLE_TO_LONG,
        DOUBLE_TO_FLOAT,
        INT_TO_BYTE,
        INT_TO_CHAR,
        INT_TO_SHORT,
        ADD_INT,
        SUB_INT,
        MUL_INT,
        DIV_INT,
        REM_INT,
        AND_INT,
        OR_INT,
        XOR_INT,
        SHL_INT,
        SHR_INT,
        USHR_INT,
        ADD_LONG,
        SUB_LONG,
        MUL_LONG,
        DIV_LONG,
        REM_LONG,
        AND_LONG,
        OR_LONG,
        XOR_LONG,
        SHL_LONG,
        SHR_LONG,
        USHR_LONG,
        ADD_FLOAT,
        SUB_FLOAT,
        MUL_FLOAT,
        DIV_FLOAT,
        REM_FLOAT,
        ADD_DOUBLE,
        SUB_DOUBLE,
        MUL_DOUBLE,
        DIV_DOUBLE,
        REM_DOUBLE,
        ADD_INT_2ADDR,
        SUB_INT_2ADDR,
        MUL_INT_2ADDR,
        DIV_INT_2ADDR,
        REM_INT_2ADDR,
        AND_INT_2ADDR,
        OR_INT_2ADDR,
        XOR_INT_2ADDR,
        SHL_INT_2ADDR,
        SHR_INT_2ADDR,
        USHR_INT_2ADDR,
        ADD_LONG_2ADDR,
        SUB_LONG_2ADDR,
        MUL_LONG_2ADDR,
        DIV_LONG_2ADDR,
        REM_LONG_2ADDR,
        AND_LONG_2ADDR,
        OR_LONG_2ADDR,
        XOR_LONG_2ADDR,
        SHL_LONG_2ADDR,
        SHR_LONG_2ADDR,
        USHR_LONG_2ADDR,
        ADD_FLOAT_2ADDR,
        SUB_FLOAT_2ADDR,
        MUL_FLOAT_2ADDR,
        DIV_FLOAT_2ADDR,
        REM_FLOAT_2ADDR,
        ADD_DOUBLE_2ADDR,
        SUB_DOUBLE_2ADDR,
        MUL_DOUBLE_2ADDR,
        DIV_DOUBLE_2ADDR,
        REM_DOUBLE_2ADDR,
        ADD_INT_LIT16,
        RSUB_INT,
        MUL_INT_LIT16,
        DIV_INT_LIT16,
        REM_INT_LIT16,
        AND_INT_LIT16,
        OR_INT_LIT16,
        XOR_INT_LIT16,
        ADD_INT_LIT8,
        RSUB_INT_LIT8,
        MUL_INT_LIT8,
        DIV_INT_LIT8,
        REM_INT_LIT8,
        AND_INT_LIT8,
        OR_INT_LIT8,
        XOR_INT_LIT8,
        SHL_INT_LIT8,
        SHR_INT_LIT8,
        USHR_INT_LIT8,
        IGET_VOLATILE,
        IPUT_VOLATILE,
        SGET_VOLATILE,
        SPUT_VOLATILE,
        IGET_OBJECT_VOLATILE,
        IGET_WIDE_VOLATILE,
        IPUT_WIDE_VOLATILE,
        SGET_WIDE_VOLATILE,
        SPUT_WIDE_VOLATILE,
        THROW_VERIFICATION_ERROR,
        EXECUTE_INLINE,
        EXECUTE_INLINE_RANGE,
        INVOKE_DIRECT_EMPTY,
        INVOKE_OBJECT_INIT_RANGE,
        RETURN_VOID_BARRIER,
        RETURN_VOID_NO_BARRIER,
        IGET_QUICK,
        IGET_WIDE_QUICK,
        IGET_OBJECT_QUICK,
        IPUT_QUICK,
        IPUT_WIDE_QUICK,
        IPUT_OBJECT_QUICK,
        IPUT_BOOLEAN_QUICK,
        IPUT_BYTE_QUICK,
        IPUT_CHAR_QUICK,
        IPUT_SHORT_QUICK,
        IGET_BOOLEAN_QUICK,
        IGET_BYTE_QUICK,
        IGET_CHAR_QUICK,
        IGET_SHORT_QUICK,
        INVOKE_VIRTUAL_QUICK,
        INVOKE_VIRTUAL_QUICK_RANGE,
        INVOKE_SUPER_QUICK,
        INVOKE_SUPER_QUICK_RANGE,
        IPUT_OBJECT_VOLATILE,
        SGET_OBJECT_VOLATILE,
        SPUT_OBJECT_VOLATILE,
        PACKED_SWITCH_PAYLOAD,
        SPARSE_SWITCH_PAYLOAD,
        ARRAY_PAYLOAD,
        INVOKE_POLYMORPHIC,
        INVOKE_POLYMORPHIC_RANGE,
        INVOKE_CUSTOM,
        INVOKE_CUSTOM_RANGE,
        CONST_METHOD_HANDLE,
        CONST_METHOD_TYPE
    }
}