import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.*;
import java.io.*;

/** args: outDir. Writes funcs.txt (addr, size, name), exports.txt, strings.txt (addr, text, xref function entries). */
public class ListAll extends GhidraScript {
    @Override
    public void run() throws Exception {
        String dir = getScriptArgs()[0];
        try (PrintWriter w = new PrintWriter(new FileWriter(dir + "/funcs.txt"))) {
            FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
            while (it.hasNext()) { Function f = it.next(); w.println(f.getEntryPoint() + "\t" + f.getBody().getNumAddresses() + "\t" + f.getName(true)); }
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(dir + "/exports.txt"))) {
            SymbolIterator si = currentProgram.getSymbolTable().getAllSymbols(true);
            for (Symbol s : currentProgram.getSymbolTable().getExternalEntryPointIterator() == null ? new java.util.ArrayList<Symbol>() : new java.util.ArrayList<Symbol>()) {}
            AddressIterator ai = currentProgram.getSymbolTable().getExternalEntryPointIterator();
            while (ai.hasNext()) { Address a = ai.next(); Symbol s = currentProgram.getSymbolTable().getPrimarySymbol(a); w.println(a + "\t" + (s == null ? "?" : s.getName())); }
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(dir + "/strings.txt"))) {
            DataIterator di = currentProgram.getListing().getDefinedData(true);
            while (di.hasNext()) {
                Data d = di.next();
                if (!d.hasStringValue()) continue;
                StringBuilder refs = new StringBuilder();
                ReferenceIterator ri = currentProgram.getReferenceManager().getReferencesTo(d.getAddress());
                while (ri.hasNext()) { Reference r = ri.next(); Function f = currentProgram.getFunctionManager().getFunctionContaining(r.getFromAddress()); refs.append(f == null ? r.getFromAddress().toString() : f.getEntryPoint().toString()).append(' '); }
                w.println(d.getAddress() + "\t" + String.valueOf(d.getValue()).replace("\n", "\\n") + "\t" + refs);
            }
        }
    }
}
