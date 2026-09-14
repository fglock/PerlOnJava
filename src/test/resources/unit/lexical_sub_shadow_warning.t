use Test::More tests => 2;

for my $declaration ('my', 'state') {
    my $warning;
    local $SIG{__WARN__} = sub { $warning .= shift };
    eval "use warnings; use feature qw(lexical_subs state); no warnings 'experimental::lexical_subs';\n"
        . "$declaration sub duplicated; $declaration sub duplicated {}";
    like $warning,
        qr/^"\Q$declaration\E" subroutine &duplicated masks earlier declaration in same scope at /,
        "$declaration lexical sub warns when it masks an earlier declaration";
}
