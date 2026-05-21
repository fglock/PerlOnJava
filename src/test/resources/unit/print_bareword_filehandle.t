use strict;
use warnings;
use Test::More tests => 8;

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

{
    my $buffer = '';
    open my $capture, '>', \$buffer or die $!;
    my $old = select $capture;
    my $ok = eval {
        print q(a);
        print qq(b);
        print join('', 'c');
        1;
    };
    my $error = $@;
    select $old;
    close $capture;
    ok($ok, 'core operators after print are not bareword filehandles') or diag $error;
    is($buffer, 'abc', 'print parses q, qq, and join as core operators');
}

{
    my $buffer = '';
    open my $capture, '>', \$buffer or die $!;
    my $old = select $capture;
    my $ok = eval {
        print v65.66;
        1;
    };
    my $error = $@;
    select $old;
    close $capture;
    ok($ok, 'v-string after print is not a bareword filehandle') or diag $error;
    is($buffer, 'AB', 'print parses v-string operands');
}

{
    my $ok = eval {
        print UnknownPrintFunction("x");
        1;
    };
    my $error = $@;
    ok(!$ok && $error =~ /Undefined subroutine .*UnknownPrintFunction/,
        'bareword immediately followed by parens remains a subroutine call');
}

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
