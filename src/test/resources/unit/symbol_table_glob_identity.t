use strict;
use warnings;
use Test::More;

{
    package Bar1;
    sub bar { 'found' }
}

package Aspect::Pointcut;
sub named_code {
    no strict 'refs';
    my @named_code;
    my $package_stash;
    my ($key, $entry);
    while (($key, $entry) = each %{*{'::'}}) {
        next unless defined $entry;
        local (*ENTRY) = $entry;
        next unless defined *ENTRY{HASH};
        if ($key eq 'Bar1::') {
            $package_stash = 1;
            last;
        }
    }
    return unless $package_stash;
    while (($key, $entry) = each %{*{'Bar1::'}}) {
        next unless defined $entry;
        local (*ENTRY) = $entry;
        push @named_code, $key if defined *ENTRY{CODE};
    }
    return @named_code;
}

package main;

my @named_code = Aspect::Pointcut::named_code();
ok(grep($_ eq 'bar', @named_code),
    'root stash iteration finds named subroutines from another package');

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
