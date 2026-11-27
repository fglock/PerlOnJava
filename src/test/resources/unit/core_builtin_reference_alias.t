use strict;
use warnings;
use Test::More tests => 6;

sub alias_core {
    my ($operator) = @_;
    no strict 'refs';
    *{"my$operator"} = \&{"CORE::$operator"};
    my $prototype = prototype("CORE::$operator");
    if ($prototype eq '_') {
        eval " &CORE::$operator(1,2) ";
        like($@, qr/Too many arguments/,
            "CORE::$operator rejects an extra argument through ampersand syntax");
    }
}

alias_core('__FILE__');
alias_core('__LINE__');
alias_core('__PACKAGE__');
alias_core('__SUB__');

{
    alias_core('abs');
}
is(&myabs(-5), 5, 'a CORE abs reference is callable through a glob alias');
ok(exists &{"myabs"}, 'the CORE abs alias remains defined after a call');

{
    alias_core('alarm');
}
eval { &myalarm(1, 2) };
like($@, qr/Too many arguments/, 'a CORE alarm reference validates its prototype');
ok(exists &{"myalarm"}, 'the CORE alarm alias remains defined after validation');
