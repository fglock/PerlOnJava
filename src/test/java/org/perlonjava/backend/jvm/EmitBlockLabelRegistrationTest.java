package org.perlonjava.backend.jvm;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.perlonjava.PerlRuntimeTestBase;
import org.perlonjava.app.cli.CompilerOptions;
import org.perlonjava.app.scriptengine.PerlLanguageProvider;
import org.perlonjava.runtime.runtimetypes.GlobalVariable;
import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeCode;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;
import org.perlonjava.backend.bytecode.InterpretedCode;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("unit")
class EmitBlockLabelRegistrationTest extends PerlRuntimeTestBase {

    @Test
    void statementLabelIsRegisteredOnceForControlFlowDispatch() throws Exception {
        CompilerOptions options = new CompilerOptions();
        options.fileName = "emit_block_label_registration.t";
        options.code = "package EmitBlockLabelRegistration;"
                + "sub is_rlocked { $_[0]{rprocess} }"
                + "sub dispatch_candidate {"
                + "  my ($self) = @_;"
                + "  if ($self->is_rlocked) {"
                + "    return if @{ $self->{wlock} };"
                + "    return unless @{ $self->{rlock} };"
                + "  }"
                + "  return;"
                + "  LOCK_RMUTEX: return;"
                + "}"
                + "my $mutex = bless { rprocess => 0, wlock => [], rlock => [[]] }, __PACKAGE__;"
                + "dispatch_candidate($mutex); 1;";
        PerlLanguageProvider.executePerlCode(options, false);

        RuntimeScalar codeRef = GlobalVariable.getGlobalCodeRef(
                "EmitBlockLabelRegistration::dispatch_candidate");
        RuntimeCode code = (RuntimeCode) codeRef.value;
        if (code.compilerSupplier != null) code.compilerSupplier.get();

        assertNotNull(code.codeObject);
        assertFalse(code.codeObject instanceof InterpretedCode,
                "array-dereference early returns and a later label must compile on the JVM");
    }
}
