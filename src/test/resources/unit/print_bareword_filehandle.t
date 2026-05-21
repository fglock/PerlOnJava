use strict;
use warnings;
use Test::More tests => 3;

package PrintBarewordTarget;

sub Dumper {
    die "Dumper method should not be called";
}

package main;

my $object = bless {}, 'PrintBarewordTarget';

{
    no warnings qw(once unopened);
    my $ok = eval {
        print Dumper $object;
        1;
    };
    ok($ok, 'unresolved print bareword is a filehandle, not an indirect method') or diag $@;
}

our $known_called = 0;

sub KnownPrintArg {
    $known_called++;
    return "";
}

print KnownPrintArg $object;
is($known_called, 1, 'known bareword after print remains a subroutine call');

sub DeclaredOnly;

{
    no warnings qw(once unopened);
    my $ok = eval {
        print DeclaredOnly "x";
        1;
    };
    my $error = $@;
    ok(!$ok && $error =~ /Undefined subroutine .*DeclaredOnly/,
        'forward-declared bareword after print remains a subroutine call');
}
