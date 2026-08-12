use strict;
use warnings;

print "1..4\n";
my $number = 0;
sub check {
    my ($condition, $name) = @_;
    ++$number;
    print($condition ? "ok " : "not ok ", $number, " - ", $name, "\n");
}

my $warnings = 0;
local $SIG{__WARN__} = sub {
    ++$warnings if $_[0] =~ /^substr outside of string/;
};

my $string = 'abc';
my $read = substr($string, 99, 1);
check(!defined($read), 'out-of-range rvalue substr returns undef');
check($warnings == 1, 'out-of-range rvalue substr warns once');

$warnings = 0;
my $error = '';
eval { substr($string, 99, 1) = ''; 1 } or $error = $@;
check($warnings == 0, 'out-of-range lvalue substr does not warn before assignment');
check($error =~ /^substr outside of string/,
    'out-of-range lvalue substr dies on assignment');
