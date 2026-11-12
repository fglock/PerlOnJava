use warnings;
use Test::More;

{
    package StrictIsaDeclaredAfterAssignment;
    package StrictIsaBase;

    package StrictIsaDeclaredAfterAssignment;
    use Pod::Simple;
    @ISA = qw(Pod::Simple);
    use strict;
    use vars qw($VERSION @ISA);

    our $VERSION = '0';
}

ok(StrictIsaDeclaredAfterAssignment->isa('Pod::Simple'),
    'use vars declaration permits earlier @ISA assignment');
is_deeply(\@StrictIsaDeclaredAfterAssignment::ISA, ['Pod::Simple'],
    '@ISA retains its assigned parent');

done_testing();
