use strict;
use warnings;
use threads;
use Test::More;
use Unicode::UCD ();

my $iv_max = (~0) >> 1;
is($iv_max, 9223372036854775807, '64-bit IV maximum is canonical');
is($Unicode::UCD::MAX_CP, $iv_max, 'Unicode::UCD publishes IV maximum');
is(sprintf('%X', $Unicode::UCD::MAX_CP), '7FFFFFFFFFFFFFFF',
   'MAX_CP has the canonical hexadecimal value');
(my $infinity = sprintf('%X', $Unicode::UCD::MAX_CP)) =~ s/^7/F/;
is($infinity, 'FFFFFFFFFFFFFFFF', 'derived infinity is unsigned IV maximum');
ok(Unicode::UCD::UnicodeVersion() =~ /^\d+(?:\.\d+)+$/,
   'Unicode version metadata remains available');
ok(grep($_ eq 'MAX_CP', @Unicode::UCD::EXPORT_OK),
   'MAX_CP is present in the upstream export list');
{
    no warnings 'once';
    ok(!defined $main::MAX_CP,
       'loading without imports does not create a caller scalar');
}

my $before = $Unicode::UCD::MAX_CP;
require Unicode::UCD;
is($Unicode::UCD::MAX_CP, $before, 'repeated require preserves metadata');

$Unicode::UCD::MAX_CP = 123;
my $thread = threads->create(sub {
    my $inherited = $Unicode::UCD::MAX_CP;
    $Unicode::UCD::MAX_CP = 456;
    return "$inherited:$Unicode::UCD::MAX_CP";
});
is($thread->join, '123:456', 'thread gets an isolated mutable snapshot');
is($Unicode::UCD::MAX_CP, 123, 'child mutation does not change parent scalar');
$Unicode::UCD::MAX_CP = $iv_max;

done_testing;
