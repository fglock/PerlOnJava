use strict;
use warnings;
use Test::More;

{
    package SymbolTableWalk;
    sub named_target { 'found' }
}

my @named_code;
{
    no strict 'refs';
    my $root = *{'::'};
    my $package_stash;
    while (my ($package, $entry) = each %$root) {
        if ($package eq 'SymbolTableWalk::') {
            $package_stash = *{$package};
            last;
        }
    }
    while (my ($name, $entry) = each %$package_stash) {
        push @named_code, $name if defined *{$entry}{CODE};
    }
}
ok(grep($_ eq 'named_target', @named_code),
    'root and package stash iteration expose named subroutine CODE entries');

{
    package DeletedMethod;
    our $FOO = 'scalar';
    our @FOO = ('array');
    our %FOO = (hash => 1);
    sub FOO { 'code' }
}
my $before = eval { DeletedMethod->FOO };
is($before, 'code', 'method lookup finds the installed subroutine');
{
    no strict 'refs';
    my %other_slots = map { $_ => *DeletedMethod::FOO{$_} }
        qw(SCALAR ARRAY HASH);
    my $code = *DeletedMethod::FOO{CODE};
    *DeletedMethod::FOO = $code;
    delete $DeletedMethod::{FOO};
    *DeletedMethod::FOO = $other_slots{$_} for qw(SCALAR ARRAY HASH);
}
my $after = eval { DeletedMethod->FOO };
ok(!defined $after, 'method lookup no longer finds a deleted subroutine');
is($DeletedMethod::FOO, 'scalar', 'CODE deletion retains the glob scalar slot');
is(eval { &DeletedMethod::FOO }, 'code',
    'compiled CODE remains callable after delete_sub removes its method entry');

{
    package GlobAliasCapture;
    use Tie::Handle;
    sub TIEHANDLE { bless {}, shift }
    sub TELL { 123 }
    sub PRINT { }
}
{
    no strict 'refs';
    *ORIGINAL = *STDERR;
    close *STDERR;
    my $buffer = q{};
    open *STDERR, '>', \$buffer or die "open STDERR: $!";
    tie *CAPTURE, 'GlobAliasCapture';
    {
        local *STDERR = *CAPTURE;
        is(tell ORIGINAL, 0,
            'whole-glob alias keeps original IO through localized replacement');
        {
            local *STDERR = *CAPTURE;
            is(tell ORIGINAL, 0,
                'saved alias remains bound through nested localization');
            is(tell STDERR, 123,
                'inner localized glob still uses its temporary tied IO');
        }
        is(tell STDERR, 123,
            'outer localized glob is restored after nested localization');
    }
    ok(defined *STDERR{IO}, 'localized glob restores the original STDERR binding');
    is(tell ORIGINAL, 0, 'saved alias retains its original IO after scope exit');
}

done_testing;
