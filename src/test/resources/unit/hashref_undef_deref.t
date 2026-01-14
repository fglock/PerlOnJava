use strict;
use warnings;
use Test::More;

# Regression test: dereferencing an undefined scalar as a hash reference
# must throw an error in rvalue context.

my $h;
my %h = ( DataPt => \"abc" );

my $ok = eval {
    my $y = $$h{DataPt};
    1;
};

ok(!$ok, 'rvalue $$h{DataPt} on undef scalar $h dies');
like($@, qr/(undefined value as a HASH reference|Not a HASH reference|HASH ref)/i,
    'error message mentions HASH reference');

# But lvalue usage must autovivify.
$h->{a} = 1;
is($h->{a}, 1, 'lvalue $h->{a}=1 autovivifies hashref');
is($$h{a}, 1, 'after vivify, $$h{a} works');

done_testing();
