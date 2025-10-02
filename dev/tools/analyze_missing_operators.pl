#!/usr/bin/env perl
use strict;
use warnings;
use File::Find;
use JSON::PP;

# Analyze missing operator implementations in PerlOnJava
# Compares ParserTables.java, OperatorHandler.java, and searches for Java implementations

my $parser_tables_file = 'src/main/java/org/perlonjava/parser/ParserTables.java';
my $operator_handler_file = 'src/main/java/org/perlonjava/operators/OperatorHandler.java';
my $operators_dir = 'src/main/java/org/perlonjava/operators/';

unless (-f $parser_tables_file && -f $operator_handler_file && -d $operators_dir) {
    die "Error: Required files/directories not found. Run from project root.\n";
}

# Exclusion list: operators handled by parser, not runtime
# These are control flow, special blocks, and language constructs
my %parser_handled_operators = map { $_ => 1 } qw(
    if unless elsif else
    while until for foreach
    do given when default
    BEGIN END INIT CHECK UNITCHECK AUTOLOAD DESTROY
    sub package use no require
    my our local state
    return last next redo goto
    and or not xor
    q qq qr qw qx m s tr y
    __FILE__ __LINE__ __PACKAGE__ __SUB__ __END__ __DATA__ __CLASS__
    class method field defer
    try catch finally
    break continue
);

# Runtime classes that contain instance methods (not in operators/ directory)
my %runtime_classes = map { $_ => 1 } qw(
    RuntimeArray
    RuntimeScalar
    RuntimeHash
    RuntimeList
    RuntimeIO
    RuntimeRegex
    Operator
);

print "Analyzing PerlOnJava operator implementations...\n\n";

# Step 1: Parse ParserTables.java to extract defined operators
print "Step 1: Extracting operators from ParserTables.java...\n";
my %defined_operators = parse_parser_tables($parser_tables_file);
printf "  Found %d operators defined in ParserTables\n\n", scalar(keys %defined_operators);

# Step 2: Parse OperatorHandler.java to see which have handlers
print "Step 2: Checking OperatorHandler.java mappings...\n";
my %operator_handlers = parse_operator_handler($operator_handler_file);
printf "  Found %d operator handler mappings\n\n", scalar(keys %operator_handlers);

# Step 3: Search for Java implementation files
print "Step 3: Searching for Java implementation files...\n";
my %java_implementations = find_java_implementations($operators_dir);
printf "  Found %d operator implementation files\n\n", scalar(keys %java_implementations);

# Step 4: Analyze and report
print "=" x 80, "\n";
print "ANALYSIS REPORT: Missing Operator Implementations\n";
print "=" x 80, "\n\n";

my @missing_handlers;
my @missing_implementations;
my @implemented;
my @parser_handled;

for my $op (sort keys %defined_operators) {
    # Check if this is a parser-handled operator (excluded from analysis)
    if (exists $parser_handled_operators{$op}) {
        push @parser_handled, {
            operator => $op,
            status => 'Handled by parser (control flow/language construct)',
        };
        next;  # Skip further analysis for these
    }
    
    my $has_handler = exists $operator_handlers{$op};
    my $has_impl = exists $java_implementations{$op};
    
    # Check if handler points to a runtime class (instance method)
    my $is_runtime_method = 0;
    if ($has_handler) {
        my $handler_class = (split /\./, $operator_handlers{$op})[0];
        $is_runtime_method = exists $runtime_classes{$handler_class};
    }
    
    if ($has_handler && ($has_impl || $is_runtime_method)) {
        push @implemented, {
            operator => $op,
            handler => $operator_handlers{$op},
            impl_type => $is_runtime_method ? 'instance method' : 'operator file',
            impl_file => $java_implementations{$op} || "Runtime class: " . (split /\./, $operator_handlers{$op})[0],
        };
    } elsif ($has_handler && !$has_impl && !$is_runtime_method) {
        push @missing_implementations, {
            operator => $op,
            handler => $operator_handlers{$op},
            status => 'Handler defined but no implementation found',
        };
    } else {
        push @missing_handlers, {
            operator => $op,
            status => 'No handler mapping in OperatorHandler.java',
        };
    }
}

# Report: Operators without handlers
if (@missing_handlers) {
    print " OPERATORS WITHOUT HANDLERS (" . scalar(@missing_handlers) . "):\n";
    print "   (These operators are defined in ParserTables but have no handler mapping)\n\n";
    for my $item (@missing_handlers) {
        printf "   %-20s - %s\n", $item->{operator}, $item->{status};
    }
    print "\n";
}

# Report: Operators with handlers but no implementation
if (@missing_implementations) {
    print " OPERATORS WITH HANDLERS BUT MISSING IMPLEMENTATIONS (" . scalar(@missing_implementations) . "):\n";
    print "   (These have handler mappings but no implementation file found)\n\n";
    for my $item (@missing_implementations) {
        printf "   %-20s -> %-30s\n", $item->{operator}, $item->{handler};
        printf "      %s\n", $item->{status};
    }
    print "\n";
}

# Report: Fully implemented operators
print " FULLY IMPLEMENTED OPERATORS (" . scalar(@implemented) . "):\n";
my $runtime_ops = grep { $_->{impl_type} eq 'instance method' } @implemented;
my $operator_files = scalar(@implemented) - $runtime_ops;
printf "   %d via operator files, %d via runtime class methods\n", $operator_files, $runtime_ops;

# Show breakdown by implementation type
my $total_runtime_ops = scalar(keys %defined_operators) - scalar(@parser_handled);
if ($total_runtime_ops > 0) {
    printf "   %.1f%% of runtime operators have complete implementations\n\n", 
           (scalar(@implemented) / $total_runtime_ops * 100);
}

# Report: Parser-handled operators (informational)
if (@parser_handled) {
    print "  PARSER-HANDLED OPERATORS (" . scalar(@parser_handled) . "):\n";
    print "   (These are control flow and language constructs handled during parsing)\n\n";
    my @sorted = sort { $a->{operator} cmp $b->{operator} } @parser_handled;
    my $count = 0;
    print "   ";
    for my $item (@sorted) {
        printf "%-15s ", $item->{operator};
        $count++;
        print "\n   " if $count % 5 == 0;
    }
    print "\n\n";
}

# Summary statistics
print "=" x 80, "\n";
print "SUMMARY:\n";
print "=" x 80, "\n";
printf "  Total operators defined:         %4d\n", scalar(keys %defined_operators);
printf "  Parser-handled (excluded):       %4d\n", scalar(@parser_handled);
printf "  Runtime operators to analyze:    %4d\n", $total_runtime_ops;
print "\n";
printf "  Fully implemented:               %4d (%.1f%%)\n", 
       scalar(@implemented), 
       $total_runtime_ops > 0 ? (scalar(@implemented) / $total_runtime_ops * 100) : 0;
printf "  Missing handlers:                %4d (%.1f%%)\n",
       scalar(@missing_handlers),
       $total_runtime_ops > 0 ? (scalar(@missing_handlers) / $total_runtime_ops * 100) : 0;
printf "  Missing implementations:         %4d (%.1f%%)\n",
       scalar(@missing_implementations),
       $total_runtime_ops > 0 ? (scalar(@missing_implementations) / $total_runtime_ops * 100) : 0;
print "\n";

# Prioritization recommendations
if (@missing_handlers || @missing_implementations) {
    print " PRIORITIZATION RECOMMENDATIONS:\n\n";
    print "  High Priority: Operators missing handlers\n";
    print "    These need handler mappings added to OperatorHandler.java first\n\n";
    print "  Medium Priority: Operators with handlers but no implementation\n";
    print "    These have infrastructure in place but need Java code written\n\n";
}

# Save detailed JSON report
my $report = {
    summary => {
        total_operators => scalar(keys %defined_operators),
        parser_handled => scalar(@parser_handled),
        runtime_operators => $total_runtime_ops,
        fully_implemented => scalar(@implemented),
        missing_handlers => scalar(@missing_handlers),
        missing_implementations => scalar(@missing_implementations),
    },
    parser_handled => \@parser_handled,
    missing_handlers => \@missing_handlers,
    missing_implementations => \@missing_implementations,
    implemented => \@implemented,
};

open my $fh, '>', 'operator_analysis.json' or die "Cannot write operator_analysis.json: $!\n";
print $fh JSON::PP->new->pretty->encode($report);
close $fh;

print " Detailed report saved to: operator_analysis.json\n\n";

# Subroutines

sub parse_parser_tables {
    my ($file) = @_;
    my %operators;
    
    open my $fh, '<', $file or die "Cannot open $file: $!\n";
    my $content = do { local $/; <$fh> };
    close $fh;
    
    # Look for operator definitions in ParserTables
    # Pattern: "operatorName" or similar string literals that appear to be operator names
    while ($content =~ /case\s+"([^"]+)":/g) {
        $operators{$1} = 1;
    }
    
    # Also look for put() calls with operator names
    while ($content =~ /\.put\s*\(\s*"([^"]+)"\s*,/g) {
        $operators{$1} = 1;
    }
    
    return %operators;
}

sub parse_operator_handler {
    my ($file) = @_;
    my %handlers;
    
    open my $fh, '<', $file or die "Cannot open $file: $!\n";
    my $content = do { local $/; <$fh> };
    close $fh;
    
    # Look for put() calls mapping operators to handlers
    # Pattern: put("operator", "method", "class") or put("operator", "method", "class", "descriptor")
    while ($content =~ /put\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"(?:\s*,\s*"[^"]*")?\s*\)/g) {
        my ($operator, $method, $class) = ($1, $2, $3);
        # Extract simple class name from full path (e.g., org/perlonjava/operators/MathOperators -> MathOperators)
        my $simple_class = $class;
        $simple_class =~ s{.*/}{};
        $handlers{$operator} = "$simple_class.$method";
    }
    
    return %handlers;
}

sub find_java_implementations {
    my ($dir) = @_;
    my %implementations;
    
    find(sub {
        return unless /^([A-Z][A-Za-z0-9]*Operator)\.java$/;
        my $class_name = $1;
        
        # Read file to find what operators it implements
        open my $fh, '<', $_ or return;
        my $content = do { local $/; <$fh> };
        close $fh;
        
        # Extract operator name from class name (e.g., ChownOperator -> chown)
        my $op_name = $class_name;
        $op_name =~ s/Operator$//;
        $op_name = lcfirst($op_name);
        
        $implementations{$op_name} = $File::Find::name;
        
        # Also check for explicit operator names in comments or methods
        while ($content =~ /\/\/\s*(?:Implements?|Operator):\s*([a-z_]+)/gi) {
            $implementations{$1} = $File::Find::name;
        }
        
        # Check if the handler's class name matches runtime classes
        while ($content =~ /public\s+([A-Za-z0-9_]+)\s+([a-zA-Z0-9_]+)\s*\(/g) {
            my ($class, $method) = ($1, $2);
            if (exists $runtime_classes{$class}) {
                $implementations{$method} = $File::Find::name;
            }
        }
    }, $dir);
    
    return %implementations;
}
