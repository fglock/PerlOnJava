use strict;
use warnings;

print "1..3\n";
my @warnings;
my $code = eval q{
    use warnings;
    no strict 'vars';
    sub { $runtime_warning_value = $runtime_warning_value . "buh"; $runtime_warning_value += 42 }
};
print defined($code) ? "ok 1 - eval produced a closure\n"
                     : "not ok 1 - eval produced a closure\n";

{
    local $SIG{__WARN__} = sub { push @warnings, shift };
    $code->();
}

print grep(/Use of uninitialized value/i, @warnings)
    ? "ok 2 - eval closure preserved uninitialized warnings\n"
    : "not ok 2 - eval closure preserved uninitialized warnings\n";
print grep(/isn't numeric in addition/, @warnings)
    ? "ok 3 - eval closure dispatched numeric warning locally\n"
    : "not ok 3 - eval closure dispatched numeric warning locally\n";
