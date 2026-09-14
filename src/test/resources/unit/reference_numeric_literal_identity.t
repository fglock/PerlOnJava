use strict;
use warnings;
use Scalar::Util qw(refaddr);
use Test::More;

my $first = \1;
my $second = \1;

isnt refaddr($first), refaddr($second), 'each numeric literal reference has its own referent';

my $error = eval { $$first = 2; 1 } ? '' : $@;
like $error, qr/read-only value/, 'numeric literal referent remains read-only';

my $large = \1_000_003;
$error = eval { $$large = 2; 1 } ? '' : $@;
like $error, qr/read-only value/, 'large numeric literal referent remains read-only';

done_testing;
