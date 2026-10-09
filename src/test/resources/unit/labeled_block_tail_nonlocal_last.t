use strict;
use warnings;
use Test::More;

# A labeled bare block in the last-statement position of a sub supplies the
# sub's return value.  A non-local `last LABEL` raised by a helper called from
# that block must still leave the labeled block, in void and scalar context.
sub helper { no warnings qw{exiting}; last SKIP }

sub named_tail { SKIP: { helper(); } }

my $after_named;
{
    my $ok = eval { named_tail(); $after_named = 1; 1 };
    ok $ok, 'void call: named sub with tail labeled block does not die';
    ok $after_named, 'void call: last SKIP leaves the tail labeled block';
}

my $anon_tail = sub { SKIP: { helper(); } };
my $after_anon;
{
    my $ok = eval { $anon_tail->(); $after_anon = 1; 1 };
    ok $ok, 'void call: anonymous sub with tail labeled block does not die';
    ok $after_anon, 'void call: last SKIP leaves the anonymous tail labeled block';
}

package Escaper {
    sub leave { no warnings qw{exiting}; last SKIP }
}

sub method_tail { SKIP: { Escaper->leave(); } }

my $after_method;
{
    my $ok = eval { method_tail(); $after_method = 1; 1 };
    ok $ok, "void method call: tail labeled block does not die";
    ok $after_method, "void method call: last SKIP leaves the tail labeled block";
}

my $after_scalar;
{
    my $ok = eval { my $x = $anon_tail->(); $after_scalar = 1; 1 };
    ok $ok, 'scalar call: tail labeled block does not die';
    ok $after_scalar, 'scalar call: last SKIP leaves the tail labeled block';
}

done_testing;
