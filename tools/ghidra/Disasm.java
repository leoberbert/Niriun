import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import java.io.*;

public class Disasm extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        try (PrintWriter w = new PrintWriter(new FileWriter(a[0]))) {
            long lo = Long.parseLong(a[1], 16), hi = Long.parseLong(a[2], 16);
            Listing l = currentProgram.getListing();
            InstructionIterator it = l.getInstructions(toAddr(lo), true);
            while (it.hasNext()) {
                Instruction i = it.next();
                if (i.getAddress().getOffset() > hi) break;
                w.println(i.getAddress() + "  " + i.toString());
            }
        }
    }
}
