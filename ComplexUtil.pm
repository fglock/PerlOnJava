
package ComplexUtil;
use strict;
use warnings;

BEGIN {
    *MY_CONST = sub() { 1 };
}

our @EXPORT_OK = qw{
    func1
    func2
    func3
    complex_func
};
BEGIN { require Exporter; our @ISA = qw(Exporter) }

sub func1 { "func1" }
sub func2 { "func2" }
sub func3 { "func3" }

sub complex_func {
    my $val = shift;
    return "complex: " . $val . " const: " . MY_CONST();
}

1;
