use strict;
use warnings;
use Test::More tests => 11;

my @implicit_append = (1, 2);
my @seen;
for (@implicit_append) {
    push @seen, $_;
    push @implicit_append, 3 if $_ == 1;
}
is_deeply(\@seen, [1, 2, 3], 'implicit foreach visits appended elements');
is_deeply(\@implicit_append, [1, 2, 3], 'implicit append preserves array');

my @implicit_delete = (1, 2, 3);
@seen = ();
for (@implicit_delete) {
    push @seen, $_;
    pop @implicit_delete if $_ == 1;
}
is_deeply(\@seen, [1, 2], 'implicit foreach follows a shortened array');
is_deeply(\@implicit_delete, [1, 2], 'implicit deletion preserves survivors');

my @lexical_append = (1, 2);
@seen = ();
for my $item (@lexical_append) {
    push @seen, $item;
    push @lexical_append, 3 if $item == 1;
}
is_deeply(\@seen, [1, 2, 3], 'lexical foreach retains live-array behavior');

my @aliases = (1, 2);
for (@aliases) {
    $_ *= 10;
}
is_deeply(\@aliases, [10, 20], 'implicit iterator aliases array elements');

$_ = 'outside';
{
    local $_ = 'localized';
    my @outer = (1);
    my @inner = (2);
    @seen = ();
    for (@outer) {
        push @seen, "o$_";
        for (@inner) {
            push @seen, "i$_";
        }
        push @seen, "r$_";
    }
    is_deeply(\@seen, ['o1', 'i2', 'r1'],
        'nested implicit loops restore the outer alias');
    is($_, 'localized', 'implicit foreach restores localized underscore');
}
is($_, 'outside', 'local underscore restores its package value');

{
    package Local::TieArray;
    sub TIEARRAY { bless { values => [10, 20] }, shift }
    sub FETCHSIZE { scalar @{$_[0]{values}} }
    sub FETCH { $_[0]{values}[$_[1]] }
    sub STORE { $_[0]{values}[$_[1]] = $_[2] }
}

tie my @tied, 'Local::TieArray';
@seen = ();
for (@tied) {
    push @seen, $_;
}
is_deeply(\@seen, [10, 20], 'tied arrays retain fetched iteration values');

@seen = ();
for (1, 2) {
    push @seen, $_;
}
is_deeply(\@seen, [1, 2], 'non-array list expressions retain snapshot iteration');
