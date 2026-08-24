use strict;
use warnings;

use Config;
use Scalar::Util qw(reftype);
use Test::More;

my $regexp = qr/foo/;
is(reftype($regexp), 'REGEXP', 'compiled regex has the standard REGEXP reference type');

my $blessed_regexp = qr/bar/;
bless $blessed_regexp, 'Local::BlessedRegexp';
is(reftype($blessed_regexp), 'REGEXP', 'blessing preserves the underlying REGEXP type');

SKIP: {
    skip 'bundled Moose constraint is available under PerlOnJava', 6
        unless $Config{archname} =~ /^java-/;

    require Moose::Util::TypeConstraints;
    my $type = Moose::Util::TypeConstraints::find_type_constraint('RegexpRef');
    ok($type, 'bundled Moose registers RegexpRef');
    ok($type->check($regexp), 'RegexpRef accepts a compiled regex');
    ok($type->check($blessed_regexp), 'RegexpRef accepts a blessed compiled regex');
    ok(!$type->check('foo'), 'RegexpRef rejects a string');

    my $compiled = eval q{
        package Local::RegexpHolder;
        use Moose;
        use Moose::Util::TypeConstraints qw(as subtype);
        subtype 'Local::RegexpRef' => as 'RegexpRef';
        has pattern => (is => 'ro', isa => 'Local::RegexpRef');
        __PACKAGE__->meta->make_immutable;
        1;
    };
    ok($compiled, 'Moose compiles an immutable accessor using a RegexpRef subtype')
        or diag($@);

    my $holder = eval { Local::RegexpHolder->new(pattern => qr/baz/) };
    ok($holder && reftype($holder->pattern) eq 'REGEXP',
        'generated constructor executes the inlined RegexpRef helper');
}

done_testing;
