import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import java.io.*;

public class GetStr extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        try (PrintWriter w = new PrintWriter(new FileWriter(a[0]))) {
            for (int i = 1; i < a.length; i++) {
                long base = Long.parseLong(a[i], 16);
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < 200; k++) {
                    byte b = currentProgram.getMemory().getByte(toAddr(base + k));
                    if (b == 0) break;
                    sb.append((b >= 32 && b < 127) ? (char) b : '.');
                }
                w.println(a[i] + ": " + sb);
            }
        }
    }
}
