package archfixtures.engine;

import com.crisil.apiorch.support.h2.H2Functions;

/** Violates NOTHING_IMPORTS_THE_H2_SHIM. */
public class EngineImportsH2Shim {
    Object now() {
        return H2Functions.sysUtcDateTime();
    }
}
