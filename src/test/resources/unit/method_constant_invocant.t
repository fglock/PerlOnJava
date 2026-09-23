use strict;
use warnings;
use Test::More;

sub MethodConstantInvocant::chop_self { chop $_[0] }

my $ok = eval { 'MethodConstantInvocant'->chop_self; 1 };
ok(!$ok, 'a literal class name remains read-only in a method argument');
like($@, qr/Modification of a read-only value attempted/,
    'a method cannot mutate its literal class-name invocant');

sub IO::Handle::method_glob_identity_self { $_[0] }
sub {
    $_[0] = *STDOUT;
    is($_[0]->method_glob_identity_self, \$::method_glob_identity{entry},
        'method dispatch preserves a hash element glob reference identity');
}->($::method_glob_identity{entry});

done_testing;
