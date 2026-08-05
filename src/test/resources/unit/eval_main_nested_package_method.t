use strict;
use warnings;
use Test::More;

my $source = <<'PERL';
package main::Generated::Nested;
sub answer { 42 }
1;
PERL

my $loaded = eval $source;
is($@, '', 'nested package source compiles through string eval');
ok($loaded, 'nested package source returns true');
ok(Generated::Nested->can('answer'), 'can finds method through implicit main package alias');
is(Generated::Nested->answer, 42, 'method dispatch finds explicit main package at arbitrary depth');

{
    package Generated::Parent;
    sub inherited { 'parent method' }
}

my $child_source = <<'PERL';
package main::Generated::Child;
our @ISA = ('Generated::Parent');
1;
PERL

eval $child_source;
is($@, '', 'dynamic child package and ISA compile through string eval');
my $child = bless {}, 'Generated::Child';
ok($child->isa('Generated::Parent'), 'ISA lookup follows implicit main package alias');
is($child->inherited, 'parent method', 'inherited dispatch follows aliased ISA array');

done_testing;
