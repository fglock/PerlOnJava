use strict;
use warnings FATAL => 'uninitialized';

print "1..3\n";

{
    no warnings 'uninitialized';
    my $ok = eval q{my $value; my $text = '<' . $value . '>'; 1};
    print defined($ok)
        ? "ok 1 - eval inherits lexical no warnings\n"
        : "not ok 1 - eval inherits lexical no warnings: $@\n";
}

my @warnings;
my $code = eval q{
    use warnings NONFATAL => 'uninitialized';
    no strict 'vars';
    sub { $eval_warning_value = $eval_warning_value . 'suffix' }
};
{
    local $SIG{__WARN__} = sub { push @warnings, shift };
    $code->();
}
print grep(/uninitialized value/i, @warnings)
    ? "ok 2 - eval can enable concatenation warnings\n"
    : "not ok 2 - eval can enable concatenation warnings\n";

my $quiet = eval q{
    no warnings 'uninitialized';
    my $value;
    my $text = '<' . $value . '>';
    1
};
print defined($quiet)
    ? "ok 3 - eval can disable inherited fatal warning\n"
    : "not ok 3 - eval can disable inherited fatal warning: $@\n";
