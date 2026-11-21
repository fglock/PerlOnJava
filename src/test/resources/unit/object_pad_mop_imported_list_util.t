use strict;
use warnings;
use Test::More;

if (!eval { require Object::Pad; 1 }) {
    plan skip_all => 'Object::Pad is not installed in system Perl';
}

my $source = <<'PERL';
use v5.26;
use Object::Pad 0.800;
package Test::ObjectPad::MOPImportedListUtil;
use List::Util qw(any);
class Test::ObjectPad::MOPImportedListUtil :repr(HASH) {
    BEGIN {
        use Object::Pad ':experimental(mop)';
        my $allowed = [qw(GPIO SPI)];
        my $wanted = 'GPIO';
        Object::Pad::MOP::Class->for_class(__PACKAGE__)->add_method(
            matches => method {
                return any { $_ eq $wanted } @$allowed;
            });
    }
}
package main;
our $matched = Test::ObjectPad::MOPImportedListUtil->new->matches;
PERL

our $matched;
my $compiled = eval $source;
ok($compiled, 'MOP-installed method with imported List::Util function compiles')
    or diag $@;
is($matched, 1, 'MOP-installed method calls imported List::Util::any')
    if $compiled;

done_testing();
