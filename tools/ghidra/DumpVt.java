import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import java.io.*;

public class DumpVt extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        try (PrintWriter w = new PrintWriter(new FileWriter(a[0]))) {
            for (int i = 1; i < a.length; i += 2) {
                long base = Long.parseLong(a[i], 16);
                int n = Integer.parseInt(a[i+1]);
                w.println("== vtable @ " + Long.toHexString(base));
                for (int k = 0; k < n; k++) {
                    Address ad = toAddr(base + 8L*k);
                    long v = currentProgram.getMemory().getLong(ad);
                    Address t = toAddr(v);
                    Function f = getFunctionAt(t);
                    w.println(String.format("  [0x%x] %x %s", 8*k, v, f == null ? "?" : f.getName()));
                }
            }
        }
    }
}
