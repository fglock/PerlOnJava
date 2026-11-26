use strict;
use warnings;
use Test::More;
use Symbol qw(qualify_to_ref);

sub glob_reference_target { return join q{:}, @_ }

my $glob = qualify_to_ref('glob_reference_target');
my $code = \&$glob;
is(ref($code), 'CODE', 'taking a code reference reads the glob reference CODE slot');
is($code->('direct'), 'direct', 'the captured CODE slot is callable');

my $tail_caller = sub { goto &$code; };
is($tail_caller->('tail'), 'tail', 'goto through the captured CODE slot reaches the target');

done_testing();
