#!/usr/bin/env perl
use strict;
use warnings;
use File::Path qw(make_path);

# Configuration
my $operator_handler_file = 'src/main/java/org/perlonjava/operators/OperatorHandler.java';
my $opcodes_file = 'src/main/java/org/perlonjava/interpreter/Opcodes.java';
my $bytecode_interpreter_file = 'src/main/java/org/perlonjava/interpreter/BytecodeInterpreter.java';
my $interpreted_code_file = 'src/main/java/org/perlonjava/interpreter/InterpretedCode.java';
my $bytecode_compiler_file = 'src/main/java/org/perlonjava/interpreter/CompileOperator.java';  # Changed from BytecodeCompiler.java
my $output_dir = 'src/main/java/org/perlonjava/interpreter';

# Read existing opcodes and LASTOP from Opcodes.java
print "Reading existing opcodes...\n";
my %existing_opcodes = read_existing_opcodes($opcodes_file);
my $OPCODE_START = $existing_opcodes{__LASTOP__} + 1;
print "  Starting new opcodes at $OPCODE_START (LASTOP + 1)\n";

# Read OperatorHandler.java
open my $fh, '<', $operator_handler_file or die "Cannot open $operator_handler_file: $!";
my $content = do { local $/; <$fh> };
close $fh;

# Parse operators
my %operators_by_sig;

print "\nParsing OperatorHandler.java...\n";
while ($content =~ /put\("([^"]+)",\s*"(\w+)",\s*"([^"]+)"(?:,\s*"([^"]+)")?\)/g) {
    my ($op_name, $method, $class_path, $descriptor) = ($1, $2, $3, $4);

    # Skip operators with special characters that are already handled
    next if $op_name =~ /^[+\-*\/%&|^<>=!.~]+$/;
    next if $op_name =~ /^(binary|unaryMinus|xor|not|\.\.)$/;

    # Skip operators with known signature issues
    next if $op_name eq 'getc';  # varargs signature: (int, RuntimeBase...)

    # Default descriptor for binary scalar operators
    $descriptor //= "(Lorg/perlonjava/runtime/RuntimeScalar;Lorg/perlonjava/runtime/RuntimeScalar;)Lorg/perlonjava/runtime/RuntimeScalar;";

    my $class = $class_path =~ s|.*/||r;
    my $sig_type = classify_signature($descriptor);

    # Skip already implemented or complex signatures
    next if $sig_type eq 'skip';

    my $opcode_const = opcode_name($op_name);

    # Check if opcode already exists
    if (exists $existing_opcodes{$opcode_const}) {
        print "  Skipping $op_name ($opcode_const) - already exists as opcode $existing_opcodes{$opcode_const}\n";
        next;
    }

    my $op = {
        name => $op_name,
        opcode_name => $opcode_const,
        method => $method,
        class => $class,
        class_path => $class_path,
        descriptor => $descriptor,
    };

    push @{$operators_by_sig{$sig_type}}, $op;
}

# Now assign contiguous opcode numbers by signature type
my $opcode_num = $OPCODE_START;

for my $sig_type (sort keys %operators_by_sig) {
    for my $op (@{$operators_by_sig{$sig_type}}) {
        $op->{opcode_num} = $opcode_num++;
    }
}

print "\nParsed operators by signature:\n";
for my $sig (sort keys %operators_by_sig) {
    printf "  %-20s: %d operators\n", $sig, scalar @{$operators_by_sig{$sig}};
}
print "\n";

# Generate handler for each signature type
for my $sig_type (sort keys %operators_by_sig) {
    generate_handler($sig_type, $operators_by_sig{$sig_type});
}

# Update source files with generated code
print "\nUpdating source files...\n";
update_opcodes_file(\%operators_by_sig, $opcode_num);
update_bytecode_interpreter(\%operators_by_sig);
update_interpreted_code(\%operators_by_sig);
update_bytecode_compiler(\%operators_by_sig);

print "\nGeneration complete!\n";
print "Next opcode available: $opcode_num\n";

sub read_existing_opcodes {
    my ($filename) = @_;

    open my $fh, '<', $filename or die "Cannot open $filename: $!";
    my $content = do { local $/; <$fh> };
    close $fh;

    my %opcodes;

    # Remove the GENERATED section to avoid reading our own generated opcodes
    $content =~ s/\/\/ GENERATED_OPCODES_START.*?\/\/ GENERATED_OPCODES_END//s;

    # Match: public static final short OPCODE_NAME = value;
    while ($content =~ /public\s+static\s+final\s+short\s+(\w+)\s*=\s*(\d+);/g) {
        my ($name, $value) = ($1, $2);
        $opcodes{$name} = $value;
    }

    # Match: private static final short LASTOP = value;
    if ($content =~ /private\s+static\s+final\s+short\s+LASTOP\s*=\s*(\d+);/) {
        $opcodes{__LASTOP__} = $1;
    } else {
        die "Cannot find LASTOP in $filename\n";
    }

    print "  Found " . (scalar(keys %opcodes) - 1) . " existing manual opcodes, LASTOP = $opcodes{__LASTOP__}\n";
    return %opcodes;
}

sub opcode_name {
    my ($name) = @_;

    # Handle special operator names
    my %special = (
        'binary&' => 'BINARY_AND',
        'binary|' => 'BINARY_OR',
        'binary^' => 'BINARY_XOR',
        'binary~' => 'BINARY_NOT',
    );

    return $special{$name} if exists $special{$name};

    # Convert camelCase to UPPER_CASE
    $name =~ s/([a-z])([A-Z])/$1_$2/g;  # insert underscore before caps
    $name = uc($name);

    return $name;
}

sub classify_signature {
    my ($desc) = @_;

    # Extract parameter types and return type
    my ($params) = $desc =~ /\(([^)]*)\)/;
    my ($return) = $desc =~ /\)(.+)$/;

    # Count parameter types
    my @param_types = $params =~ /(L[^;]+;|I|Z)/g;
    my $param_count = scalar @param_types;

    # Check for special types
    my $has_list = $params =~ /RuntimeList/;
    my $has_array = $params =~ /RuntimeArray/;
    my $has_base = $params =~ /RuntimeBase/;
    my $has_int_param = $params =~ /\bI/;
    my $has_varargs = $params =~ /\[L/;

    # Classify by signature pattern
    if ($has_varargs || $params =~ /\[Lorg/) {
        return 'skip';  # Variable args need special handling
    }

    # Scalar unary: (RuntimeScalar) -> RuntimeScalar
    if ($param_count == 1 && $params =~ /RuntimeScalar/ && $return =~ /RuntimeScalar/) {
        return 'scalar_unary';
    }

    # Scalar binary: (RuntimeScalar, RuntimeScalar) -> RuntimeScalar
    if ($param_count == 2 && !$has_list && !$has_array && !$has_int_param
        && $return =~ /RuntimeScalar/ && !$has_varargs) {
        return 'scalar_binary';
    }

    # Scalar ternary: (RuntimeScalar, RuntimeScalar, RuntimeScalar) -> RuntimeScalar
    if ($param_count == 3 && $params =~ /^(Lorg\/perlonjava\/runtime\/RuntimeScalar;){3}$/
        && $return =~ /RuntimeScalar/) {
        return 'scalar_ternary';
    }

    return 'skip';
}

sub generate_handler {
    my ($sig_type, $ops) = @_;

    return unless $ops && @$ops;

    # Generate class name
    my %sig_to_class = (
        scalar_unary => 'ScalarUnaryOpcodeHandler',
        scalar_binary => 'ScalarBinaryOpcodeHandler',
        scalar_ternary => 'ScalarTernaryOpcodeHandler',
    );

    my $class_name = $sig_to_class{$sig_type} or return;
    my $output_file = "$output_dir/$class_name.java";

    print "Generating $class_name with " . scalar(@$ops) . " operators...\n";

    my $java_code = generate_java_class($class_name, $sig_type, $ops);

    open my $out, '>', $output_file or die "Cannot write $output_file: $!";
    print $out $java_code;
    close $out;

    print "  Generated: $output_file\n";
}

sub generate_java_class {
    my ($class_name, $sig_type, $ops) = @_;

    # Collect imports - convert Java internal path format to dotted format
    my %classes;
    for my $op (@$ops) {
        my $import_path = $op->{class_path};
        $import_path =~ s|/|.|g;  # Convert / to .
        $classes{$op->{class}} = $import_path;
    }

    my $imports = join "\n", map { "import $_;" } sort values %classes;

    # Generate register loading code
    my ($register_load, $register_list, $disasm_regs) = get_register_code($sig_type);

    # Generate switch cases
    my @switch_cases;
    my @disasm_cases;

    for my $op (@$ops) {
        my $call = generate_method_call($op, $sig_type);
        push @switch_cases, "            case Opcodes.$op->{opcode_name} -> $call;";

        my $disasm = generate_disasm_case($op, $sig_type);
        push @disasm_cases, $disasm;
    }

    my $switch_cases_str = join "\n", @switch_cases;
    my $disasm_cases_str = join "\n", @disasm_cases;

    my $description = get_signature_description($sig_type);

    return qq{package org.perlonjava.interpreter;

import org.perlonjava.runtime.RuntimeBase;
import org.perlonjava.runtime.RuntimeScalar;
$imports

/**
 * Handler for $description
 * Generated by dev/tools/generate_opcode_handlers.pl
 * DO NOT EDIT MANUALLY - regenerate using the tool
 */
public class $class_name {

    /**
     * Execute $description operation.
     */
    public static int execute(int opcode, short[] bytecode, int pc,
                              RuntimeBase[] registers) {
        // Read registers (shared by all opcodes in this group)
$register_load

        // Dispatch based on specific opcode
        registers[rd] = switch (opcode) {
$switch_cases_str
            default -> throw new IllegalStateException("Unknown opcode in $class_name: " + opcode);
        };

        return pc;
    }

    /**
     * Disassemble $description operation.
     */
    public static int disassemble(int opcode, short[] bytecode, int pc,
                                   StringBuilder sb) {
$disasm_regs

        switch (opcode) {
$disasm_cases_str
            default -> sb.append("UNKNOWN_").append(opcode).append("\\n");
        }

        return pc;
    }
}
};
}

sub get_register_code {
    my ($sig_type) = @_;

    if ($sig_type eq 'scalar_unary') {
        return (
            "        int rd = bytecode[pc++];\n        int rs = bytecode[pc++];",
            "registers[rs]",
            "        int rd = bytecode[pc++];\n        int rs = bytecode[pc++];"
        );
    } elsif ($sig_type eq 'scalar_binary') {
        return (
            "        int rd = bytecode[pc++];\n        int rs1 = bytecode[pc++];\n        int rs2 = bytecode[pc++];",
            "registers[rs1], registers[rs2]",
            "        int rd = bytecode[pc++];\n        int rs1 = bytecode[pc++];\n        int rs2 = bytecode[pc++];"
        );
    } elsif ($sig_type eq 'scalar_ternary') {
        return (
            "        int rd = bytecode[pc++];\n        int rs1 = bytecode[pc++];\n        int rs2 = bytecode[pc++];\n        int rs3 = bytecode[pc++];",
            "registers[rs1], registers[rs2], registers[rs3]",
            "        int rd = bytecode[pc++];\n        int rs1 = bytecode[pc++];\n        int rs2 = bytecode[pc++];\n        int rs3 = bytecode[pc++];"
        );
    }
}

sub generate_method_call {
    my ($op, $sig_type) = @_;

    if ($sig_type eq 'scalar_unary') {
        return "$op->{class}.$op->{method}((RuntimeScalar) registers[rs])";
    } elsif ($sig_type eq 'scalar_binary') {
        return "$op->{class}.$op->{method}((RuntimeScalar) registers[rs1], (RuntimeScalar) registers[rs2])";
    } elsif ($sig_type eq 'scalar_ternary') {
        return "$op->{class}.$op->{method}((RuntimeScalar) registers[rs1], (RuntimeScalar) registers[rs2], (RuntimeScalar) registers[rs3])";
    }
}

sub generate_disasm_case {
    my ($op, $sig_type) = @_;

    my $name = uc($op->{name});

    if ($sig_type eq 'scalar_unary') {
        return qq{            case Opcodes.$op->{opcode_name} -> sb.append("$op->{opcode_name} r").append(rd).append(" = $op->{name}(r").append(rs).append(")\\n");};
    } elsif ($sig_type eq 'scalar_binary') {
        return qq{            case Opcodes.$op->{opcode_name} -> sb.append("$op->{opcode_name} r").append(rd).append(" = $op->{name}(r").append(rs1).append(", r").append(rs2).append(")\\n");};
    } elsif ($sig_type eq 'scalar_ternary') {
        return qq{            case Opcodes.$op->{opcode_name} -> sb.append("$op->{opcode_name} r").append(rd).append(" = $op->{name}(r").append(rs1).append(", r").append(rs2).append(", r").append(rs3).append(")\\n");};
    }
}

sub get_signature_description {
    my ($sig_type) = @_;

    my %descriptions = (
        scalar_unary => 'scalar unary operations (chr, ord, abs, sin, cos, lc, uc, etc.)',
        scalar_binary => 'scalar binary operations (atan2, eq, ne, lt, le, gt, ge, cmp, etc.)',
        scalar_ternary => 'scalar ternary operations (substr with position)',
    );

    return $descriptions{$sig_type} || $sig_type;
}

sub generate_update_instructions {
    my ($operators_by_sig) = @_;

    print "\n" . "="x70 . "\n";
    print "UPDATE INSTRUCTIONS\n";
    print "="x70 . "\n\n";

    # 1. Opcodes.java additions
    print "1. ADD TO Opcodes.java (at marker: // GENERATED_OPCODES_START):\n\n";

    for my $sig_type (sort keys %$operators_by_sig) {
        my $desc = get_signature_description($sig_type);
        print "    // $desc\n";

        for my $op (@{$operators_by_sig->{$sig_type}}) {
            printf "    public static final short %s = %d;\n",
                $op->{opcode_name}, $op->{opcode_num};
        }
        print "\n";
    }

    # 2. BytecodeInterpreter.java additions
    print "2. ADD TO BytecodeInterpreter.java (at marker: // GENERATED_HANDLERS_START):\n\n";

    for my $sig_type (sort keys %$operators_by_sig) {
        my %sig_to_class = (
            scalar_unary => 'ScalarUnaryOpcodeHandler',
            scalar_binary => 'ScalarBinaryOpcodeHandler',
            scalar_ternary => 'ScalarTernaryOpcodeHandler',
        );
        my $handler = $sig_to_class{$sig_type};

        print "                    // $sig_type\n";
        for my $op (@{$operators_by_sig->{$sig_type}}) {
            print "                    case Opcodes.$op->{opcode_name}:\n";
        }
        print "                        pc = $handler.execute(opcode, bytecode, pc, registers);\n";
        print "                        break;\n\n";
    }

    # 3. InterpretedCode.java disassembly
    print "3. ADD TO InterpretedCode.java disassemble() (at marker: // GENERATED_DISASM_START):\n\n";

    for my $sig_type (sort keys %$operators_by_sig) {
        my %sig_to_class = (
            scalar_unary => 'ScalarUnaryOpcodeHandler',
            scalar_binary => 'ScalarBinaryOpcodeHandler',
            scalar_ternary => 'ScalarTernaryOpcodeHandler',
        );
        my $handler = $sig_to_class{$sig_type};

        print "                // $sig_type\n";
        for my $op (@{$operators_by_sig->{$sig_type}}) {
            print "                case Opcodes.$op->{opcode_name}:\n";
        }
        print "                    pc = $handler.disassemble(opcode, bytecode, pc, sb);\n";
        print "                    break;\n\n";
    }

    # 4. BytecodeCompiler.java additions
    print "4. ADD TO BytecodeCompiler.java visit(OperatorNode) (at marker: // GENERATED_OPERATORS_START):\n\n";
    print "Add cases for each operator following the pattern:\n";
    print "} else if (op.equals(\"chr\")) {\n";
    print "    // chr(\$x) - convert codepoint to character\n";
    print "    if (node.operand instanceof ListNode) {\n";
    print "        ListNode list = (ListNode) node.operand;\n";
    print "        if (!list.elements.isEmpty()) {\n";
    print "            list.elements.get(0).accept(this);\n";
    print "        } else {\n";
    print "            throwCompilerException(\"chr requires an argument\");\n";
    print "        }\n";
    print "    } else {\n";
    print "        node.operand.accept(this);\n";
    print "    }\n";
    print "    int argReg = lastResultReg;\n";
    print "    int rd = allocateRegister();\n";
    print "    emit(Opcodes.CHR);\n";
    print "    emitReg(rd);\n";
    print "    emitReg(argReg);\n";
    print "    lastResultReg = rd;\n";
    print "}\n\n";

    print "\nNext opcode available: $opcode_num\n";
    print "\nOperators to add in BytecodeCompiler:\n";
    for my $sig_type (sort keys %$operators_by_sig) {
        for my $op (@{$operators_by_sig->{$sig_type}}) {
            print "  - $op->{name}\n";
        }
    }
}

sub update_file_at_markers {
    my ($filename, $start_marker, $end_marker, $new_content) = @_;

    # Read file
    open my $fh, '<', $filename or die "Cannot open $filename: $!";
    my @lines = <$fh>;
    close $fh;

    # Find markers
    my ($start_idx, $end_idx);
    for my $i (0 .. $#lines) {
        if ($lines[$i] =~ /\Q$start_marker\E/) {
            $start_idx = $i;
        }
        if ($lines[$i] =~ /\Q$end_marker\E/) {
            $end_idx = $i;
            last if defined $start_idx;
        }
    }

    unless (defined $start_idx && defined $end_idx) {
        die "Cannot find markers $start_marker and $end_marker in $filename\n";
    }

    # Replace content between markers
    splice @lines, $start_idx + 1, $end_idx - $start_idx - 1, $new_content;

    # Write file
    open my $out, '>', $filename or die "Cannot write $filename: $!";
    print $out @lines;
    close $out;

    print "  Updated $filename\n";
}

sub update_opcodes_file {
    my ($operators_by_sig, $next_opcode) = @_;

    my @content;

    for my $sig_type (sort keys %$operators_by_sig) {
        my $desc = get_signature_description($sig_type);
        push @content, "\n    // $desc\n";

        for my $op (@{$operators_by_sig->{$sig_type}}) {
            my $offset = $op->{opcode_num} - $existing_opcodes{__LASTOP__};
            push @content, sprintf("    public static final short %s = LASTOP + %d;\n",
                $op->{opcode_name}, $offset);
        }
    }

    update_file_at_markers($opcodes_file, '// GENERATED_OPCODES_START', '// GENERATED_OPCODES_END',
        join('', @content));
}

sub update_bytecode_interpreter {
    my ($operators_by_sig) = @_;

    my @content;

    for my $sig_type (sort keys %$operators_by_sig) {
        my %sig_to_class = (
            scalar_unary => 'ScalarUnaryOpcodeHandler',
            scalar_binary => 'ScalarBinaryOpcodeHandler',
            scalar_ternary => 'ScalarTernaryOpcodeHandler',
        );
        my $handler = $sig_to_class{$sig_type};

        push @content, "\n                    // $sig_type\n";
        for my $op (@{$operators_by_sig->{$sig_type}}) {
            push @content, "                    case Opcodes.$op->{opcode_name}:\n";
        }
        push @content, "                        pc = $handler.execute(opcode, bytecode, pc, registers);\n";
        push @content, "                        break;\n";
    }

    update_file_at_markers($bytecode_interpreter_file, '// GENERATED_HANDLERS_START', '// GENERATED_HANDLERS_END',
        join('', @content));
}

sub update_interpreted_code {
    my ($operators_by_sig) = @_;

    my @content;

    for my $sig_type (sort keys %$operators_by_sig) {
        my %sig_to_class = (
            scalar_unary => 'ScalarUnaryOpcodeHandler',
            scalar_binary => 'ScalarBinaryOpcodeHandler',
            scalar_ternary => 'ScalarTernaryOpcodeHandler',
        );
        my $handler = $sig_to_class{$sig_type};

        push @content, "\n                // $sig_type\n";
        for my $op (@{$operators_by_sig->{$sig_type}}) {
            push @content, "                case Opcodes.$op->{opcode_name}:\n";
        }
        push @content, "                    pc = $handler.disassemble(opcode, bytecode, pc, sb);\n";
        push @content, "                    break;\n";
    }

    update_file_at_markers($interpreted_code_file, '// GENERATED_DISASM_START', '// GENERATED_DISASM_END',
        join('', @content));
}

sub update_bytecode_compiler {
    my ($operators_by_sig) = @_;

    my @content;

    # Only generate unary operators for now (binary/ternary need different patterns)
    if (exists $operators_by_sig->{scalar_unary}) {
        for my $op (@{$operators_by_sig->{scalar_unary}}) {
            my $op_name = $op->{name};
            my $opcode_name = $op->{opcode_name};

            push @content, "        } else if (op.equals(\"$op_name\")) {\n";
            push @content, "            // $op_name(\$x) - $op->{class}.$op->{method}\n";
            push @content, "            if (node.operand instanceof ListNode) {\n";
            push @content, "                ListNode list = (ListNode) node.operand;\n";
            push @content, "                if (!list.elements.isEmpty()) {\n";
            push @content, "                    list.elements.get(0).accept(this);\n";
            push @content, "                } else {\n";
            push @content, "                    throwCompilerException(\"$op_name requires an argument\");\n";
            push @content, "                }\n";
            push @content, "            } else {\n";
            push @content, "                node.operand.accept(this);\n";
            push @content, "            }\n";
            push @content, "            int argReg = lastResultReg;\n";
            push @content, "            int rd = allocateRegister();\n";
            push @content, "            emit(Opcodes.$opcode_name);\n";
            push @content, "            emitReg(rd);\n";
            push @content, "            emitReg(argReg);\n";
            push @content, "            lastResultReg = rd;\n";
        }
    }

    update_file_at_markers($bytecode_compiler_file, '// GENERATED_OPERATORS_START', '// GENERATED_OPERATORS_END',
        join('', @content));
}
