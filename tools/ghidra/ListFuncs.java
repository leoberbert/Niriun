import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.io.*;

public class ListFuncs extends GhidraScript {
    @Override
    public void run() throws Exception {
        String dir = getScriptArgs()[0];
        try (PrintWriter w = new PrintWriter(new FileWriter(dir + "/funcs.txt"))) {
            FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
            while (it.hasNext()) {
                Function f = it.next();
                w.println(f.getEntryPoint() + "\t" + f.getBody().getNumAddresses() + "\t" + f.isThunk() + "\t" + f.getName(true));
            }
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(dir + "/externs.txt"))) {
            for (Symbol s : currentProgram.getSymbolTable().getExternalSymbols()) {
                ReferenceIterator ri = currentProgram.getReferenceManager().getReferencesTo(s.getAddress());
                int n = 0; while (ri.hasNext()) { ri.next(); n++; }
                w.println(s.getName(true) + "\t" + s.getAddress() + "\trefs=" + n);
            }
        }
    }
}
