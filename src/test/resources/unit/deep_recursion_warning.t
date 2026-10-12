use strict;
use warnings;
use Test::More tests => 3;

sub recurse {
    my ($depth) = @_;
    return if $depth >= 110;
    recurse($depth + 1);
}

my $warning;
{
    local $SIG{__WARN__} = sub { $warning = join '', @_ };
    recurse(0);
}
like($warning, qr/Deep recursion on subroutine/,
    'deep recursion warning reaches a localized warning handler');

sub shallow { return }
$warning = undef;
{
    local $SIG{__WARN__} = sub { $warning = join '', @_ };
    shallow();
}
ok(!defined $warning, 'shallow calls do not emit a deep recursion warning');

sub fatal_recurse {
    use warnings FATAL => 'recursion';
    my ($depth) = @_;
    return if $depth >= 110;
    fatal_recurse($depth + 1);
}
my $ok = eval { fatal_recurse(0); 1 };
ok(!$ok && $@ =~ /Deep recursion on subroutine/,
    'fatal recursion warnings throw an exception');
